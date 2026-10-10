package com.fukuokatabi.plan;

import com.fukuokatabi.plan.PlanRequest.Answers;
import com.fukuokatabi.plan.PlanResponse.*;
import com.fukuokatabi.preference.Interpretation;
import com.fukuokatabi.preference.PreferenceInterpreter;
import com.fukuokatabi.question.QuestionCatalog;
import com.fukuokatabi.spot.Spot;
import com.fukuokatabi.spot.SpotRepository;
import com.fukuokatabi.travel.TravelTimeProvider;
import com.fukuokatabi.travel.TravelTimeProvider.Leg;
import com.fukuokatabi.travel.TravelTimeProvider.Mode;
import com.fukuokatabi.travel.TravelTimeProvider.Point;
import org.springframework.stereotype.Service;

import java.time.LocalDate;
import java.time.LocalTime;
import java.time.format.TextStyle;
import java.util.*;
import java.util.stream.Collectors;

/**
 * 回答・自由記述・旅行日から、日ごとの時間割を組み立てる。
 */
@Service
public class PlanService {

    /** 宿泊・出発の起点として博多駅を仮定する */
    static final Point BASE = new Point(33.5897, 130.4207);
    static final String BASE_NAME = "博多駅";

    private static final LocalTime LUNCH_FROM = LocalTime.of(11, 0);
    private static final LocalTime DINNER_FROM = LocalTime.of(18, 0);

    private final SpotRepository spots;
    private final PreferenceInterpreter interpreter;
    private final TravelTimeProvider travel;
    private final QuestionCatalog questions;

    public PlanService(SpotRepository spots, PreferenceInterpreter interpreter,
                       TravelTimeProvider travel, QuestionCatalog questions) {
        this.spots = spots;
        this.interpreter = interpreter;
        this.travel = travel;
        this.questions = questions;
    }

    /** 点数をつけたスポット */
    record Scored(Spot spot, double score) {}

    public PlanResponse plan(PlanRequest req) {
        Answers a = req.answers();
        List<Spot> all = spots.findAll();
        Interpretation in = interpreter.interpret(req.freeText(), all);
        LocalDate start = req.startDate() != null ? req.startDate() : LocalDate.now().plusDays(7);
        String pace = in.paceOverride() != null ? in.paceOverride() : a.pace();
        Mode mode = "car".equals(a.move()) ? Mode.DRIVE : Mode.TRANSIT;

        List<Scored> pool = all.stream()
                .filter(s -> s.getCategory() == Spot.Category.SIGHT)
                .filter(s -> allowed(s, a, in))
                .map(s -> new Scored(s, score(s, a, in, start)))
                .sorted(Comparator.comparingDouble(Scored::score).reversed())
                .toList();

        List<List<Spot>> dayPicks = pickPerDay(pool, a, in, pace, start);

        List<Day> days = new ArrayList<>();
        for (int i = 0; i < dayPicks.size(); i++) {
            days.add(buildDay(i, dayPicks.get(i), start.plusDays(i), a, in, pace, mode, all));
        }

        return new PlanResponse(
                tripType(a),
                chips(a, pace),
                days,
                tips(a, in, dayPicks),
                new InterpretationSummary(in.source(), in.notes()));
    }

    // ---- 候補の絞り込みと点数 ----

    boolean allowed(Spot s, Answers a, Interpretation in) {
        if (in.exclude().contains(s.getId())) return false;
        boolean must = in.mustInclude().contains(s.getId());
        if (!must) {
            if ("city".equals(a.range()) && s.getFarLevel() > 0) return false;
            if ("near".equals(a.range()) && s.getFarLevel() > 1) return false;
        }
        // 公共交通で行きにくい場所は、車がなければ外す（指定があっても外し、ヒントで伝える）
        return "car".equals(a.move()) || s.isTransitOk();
    }

    double score(Spot s, Answers a, Interpretation in, LocalDate start) {
        double sc = 0;
        List<String> main = a.main();
        for (int i = 0; i < main.size(); i++) {
            sc += s.tag(main.get(i)) * (i == 0 ? 3 : 2);
        }
        for (String e : a.eatOrEmpty()) sc += s.tag(e) * 2;
        sc += s.tag(a.who()) * 1.5;
        if ("slow".equals(a.pace())) sc += s.tag("slow");
        if ("family".equals(a.who()) && s.getDurationMinutes() > 180) sc -= 1;

        for (Map.Entry<String, Integer> b : in.tagBoosts().entrySet()) {
            sc += s.tag(b.getKey()) * b.getValue();
        }
        if (in.mustInclude().contains(s.getId())) sc += 20;
        if (in.preferIndoor()) sc += s.isIndoor() ? 2 : -2;

        // 旅行期間に見頃なら加点
        Set<Integer> best = s.bestMonthSet();
        if (!best.isEmpty() && (best.contains(start.getMonthValue())
                || best.contains(start.plusDays(Math.max(0, a.days() - 1)).getMonthValue()))) {
            sc += 2;
        }
        return sc;
    }

    static boolean openOn(Spot s, LocalDate date) {
        return !s.closedDaysOfWeek().contains(date.getDayOfWeek());
    }

    // ---- 日ごとの割り当て ----

    List<List<Spot>> pickPerDay(List<Scored> pool, Answers a, Interpretation in, String pace, LocalDate start) {
        int days = a.days();
        int per = switch (pace) { case "slow" -> 2; case "fast" -> 4; default -> 3; };
        int maxMinutes = per <= 2 ? 360 : per == 3 ? 450 : 540;

        // 郊外のエリアを、点数の高い順に並べる
        Deque<String> excursionZones = pool.stream()
                .filter(sc -> !sc.spot().isCityCenter() && sc.score() > 0)
                .map(sc -> sc.spot().getZone())
                .distinct()
                .collect(Collectors.toCollection(ArrayDeque::new));

        Set<String> used = new HashSet<>();
        List<List<Spot>> result = new ArrayList<>();
        for (int d = 0; d < days; d++) {
            LocalDate date = start.plusDays(d);
            // 複数日の旅の最終日は、帰りやすいよう市内中心部にする
            boolean lastOfMulti = d == days - 1 && days > 1;
            String zone = null;
            if (!lastOfMulti) {
                while (zone == null && !excursionZones.isEmpty()) {
                    String z = excursionZones.poll();
                    Optional<Scored> best = pool.stream()
                            .filter(sc -> sc.spot().getZone().equals(z) && !used.contains(sc.spot().getId())
                                    && openOn(sc.spot(), date))
                            .findFirst();
                    if (best.isPresent() && best.get().score() >= 4) zone = z;
                }
            }

            List<Spot> picks = new ArrayList<>();
            if (zone != null) {
                String z = zone;
                pool.stream()
                        .filter(sc -> sc.spot().getZone().equals(z) && !used.contains(sc.spot().getId())
                                && openOn(sc.spot(), date))
                        .limit(Math.max(1, per - 1))
                        .forEach(sc -> picks.add(sc.spot()));
            }
            int minutes = picks.stream().mapToInt(Spot::getDurationMinutes).sum();
            for (Scored sc : pool) {
                Spot s = sc.spot();
                if (picks.size() >= per) break;
                if (!s.isCityCenter() || used.contains(s.getId()) || picks.contains(s) || !openOn(s, date)) continue;
                if (minutes + s.getDurationMinutes() > maxMinutes) continue;
                picks.add(s);
                minutes += s.getDurationMinutes();
            }
            picks.forEach(s -> used.add(s.getId()));

            // 郊外が先、次に朝向き→どこでも→午後向き、同じなら早く閉まる順
            picks.sort(Comparator
                    .comparing((Spot s) -> s.isCityCenter())
                    .thenComparing(Spot::getSlot)
                    .thenComparing(s -> s.getCloseTime() == null ? LocalTime.MAX : s.getCloseTime()));
            result.add(picks);
        }
        return result;
    }

    // ---- 時間割 ----

    Day buildDay(int index, List<Spot> picks, LocalDate date, Answers a, Interpretation in,
                 String pace, Mode mode, List<Spot> all) {
        List<Item> items = new ArrayList<>();
        LocalTime t = "slow".equals(pace) ? LocalTime.of(10, 0) : LocalTime.of(9, 0);
        if (index == 0 && a.days() > 1) t = LocalTime.of(10, 30);   // 初日は到着後から

        List<String> eats = a.eatOrEmpty();
        boolean lunchDone = false;
        Point here = BASE;

        for (int k = 0; k < picks.size(); k++) {
            Spot s = picks.get(k);
            Point to = point(s);
            Leg leg = travel.between(here, to, mode);
            if (leg.minutes() >= 10) {
                String from = k == 0 ? BASE_NAME + "周辺から" : "";
                items.add(Item.move(from + moveText(leg, mode), leg.minutes()));
            }
            t = t.plusMinutes(leg.minutes());

            if (!lunchDone && !t.isBefore(LUNCH_FROM)) {
                items.add(lunch(t, eats, index));
                t = t.plusMinutes(60);
                lunchDone = true;
            }
            if (s.getOpenTime() != null && t.isBefore(s.getOpenTime())) t = s.getOpenTime();

            String warning = null;
            LocalTime end = t.plusMinutes(s.getDurationMinutes());
            if (s.getCloseTime() != null && end.isAfter(s.getCloseTime())) {
                warning = "閉館（" + s.getCloseTime() + "ごろ）が近いので早めに";
            }
            items.add(new Item("spot", fmt(t), s.getName(), s.getArea(), noteWithSeason(s, date),
                    why(s, a, in), warning, s.getLatitude(), s.getLongitude(), s.getId(), s.getDurationMinutes()));
            t = roundUp(end.plusMinutes(15));
            here = to;
        }
        if (!lunchDone) {
            items.add(lunch(t, eats, index));
            t = t.plusMinutes(60);
        }

        // 夜ごはん
        List<String> dinnerPrefs = List.of("motsu", "mizutaki", "seafood").stream()
                .filter(eats::contains).toList();
        boolean yataiAsDinner = "yatai".equals(a.night()) && index == 0 && dinnerPrefs.isEmpty();
        if (!yataiAsDinner) {
            String key = dinnerPrefs.isEmpty() ? null : dinnerPrefs.get(index % dinnerPrefs.size());
            Spot dinner = findByTag(all, Spot.Category.DINNER, key == null ? "food" : key, in);
            if (dinner != null) {
                Leg leg = travel.between(here, point(dinner), mode);
                if (leg.minutes() >= 10) items.add(Item.move(moveText(leg, mode), leg.minutes()));
                t = max(t.plusMinutes(leg.minutes()), DINNER_FROM);
                items.add(new Item("dinner", fmt(t), "夜ごはん：" + dinner.getName(), dinner.getArea(),
                        noteWithSeason(dinner, date), key == null ? null : "「" + questions.label("eat", key) + "」を選択",
                        null, dinner.getLatitude(), dinner.getLongitude(), dinner.getId(), dinner.getDurationMinutes()));
                t = roundUp(t.plusMinutes(dinner.getDurationMinutes()));
                here = point(dinner);
            }
        } else {
            t = max(t, DINNER_FROM);
        }

        // 夜の過ごし方
        String nightTag = "rest".equals(a.night()) ? (eats.contains("yatai") ? "yatai" : null) : a.night();
        if (nightTag != null) {
            Spot night = findByTag(all, Spot.Category.NIGHT, nightTag, in);
            if (night != null) {
                Leg leg = travel.between(here, point(night), mode);
                if (leg.minutes() >= 10) items.add(Item.move(moveText(leg, mode), leg.minutes()));
                t = t.plusMinutes(leg.minutes());
                String why = "rest".equals(a.night()) ? "「屋台」を選択" : "「" + questions.label("night", a.night()) + "」から";
                items.add(new Item("night", fmt(t), night.getName(), night.getArea(), night.getNote(), why,
                        null, night.getLatitude(), night.getLongitude(), night.getId(), night.getDurationMinutes()));
            }
        }

        String headline;
        Optional<Spot> excursion = picks.stream().filter(s -> !s.isCityCenter()).findFirst();
        headline = excursion.map(s -> s.getArea() + "へ足をのばす日").orElse("市内中心部をめぐる日");
        String dateLabel = date + "（" + date.getDayOfWeek().getDisplayName(TextStyle.SHORT, Locale.JAPANESE) + "）";
        return new Day(index + 1, dateLabel, headline, items);
    }

    private Item lunch(LocalTime t, List<String> eats, int dayIndex) {
        boolean ramen = eats.contains("ramen") && dayIndex == 0;
        return new Item("lunch", fmt(t),
                ramen ? "昼ごはん：博多豚骨ラーメン" : "昼ごはん", null,
                ramen ? "昼のうちに1杯。屋台と食べ比べるのも楽しい。" : "近くの定食屋やうどん店で。博多のごぼう天うどんもおすすめ。",
                ramen ? "「豚骨ラーメン」を選択" : null, null, null, null, null, 60);
    }

    /** 指定タグの店・過ごし方を選ぶ。自由記述で外された・苦手とされたものは避ける */
    private static Spot findByTag(List<Spot> all, Spot.Category cat, String tag, Interpretation in) {
        if (in.tagBoosts().getOrDefault(tag, 0) < 0) return null;
        return all.stream()
                .filter(s -> s.getCategory() == cat && s.tag(tag) > 0 && !in.exclude().contains(s.getId()))
                .max(Comparator.comparingInt(s -> s.tag(tag)))
                .orElseGet(() -> cat == Spot.Category.DINNER
                        ? all.stream().filter(s -> s.getId().equals("dinner-izakaya")).findFirst().orElse(null)
                        : null);
    }

    private String why(Spot s, Answers a, Interpretation in) {
        if (in.mustInclude().contains(s.getId())) return "自由記述でリクエスト";
        for (String m : a.main()) {
            if (s.tag(m) > 0) return "「" + questions.label("main", m) + "」を選んだので";
        }
        for (String e : a.eatOrEmpty()) {
            if (s.tag(e) > 0) return "「" + questions.label("eat", e) + "」を選んだので";
        }
        for (Map.Entry<String, Integer> b : in.tagBoosts().entrySet()) {
            if (b.getValue() > 0 && s.tag(b.getKey()) > 0) return "自由記述から";
        }
        if (s.tag(a.who()) > 0) return questions.label("who", a.who()) + "向き";
        return null;
    }

    private static String noteWithSeason(Spot s, LocalDate date) {
        if (s.getSeasonNote() != null && s.bestMonthSet().contains(date.getMonthValue())) {
            return s.getNote() + "（" + s.getSeasonNote() + "）";
        }
        return s.getNote();
    }

    private static String moveText(Leg leg, Mode mode) {
        String how = leg.km() < 1.0 ? "徒歩" : mode == Mode.DRIVE ? "車" : "電車・バス";
        String approx = "estimate".equals(leg.source()) ? "目安" : "";
        return how + "で移動（" + approx + "約" + leg.minutes() + "分）";
    }

    // ---- まとめ ----

    private static TripType tripType(Answers a) {
        return switch (a.main().get(0)) {
            case "history" -> new TripType("史", "神社と歴史をたどる旅", "千年の天神さまから城跡まで");
            case "nature" -> new TripType("海", "海と風を感じる旅", "街から少し出て、玄界灘へ");
            case "shop" -> new TripType("街", "天神・博多の街歩き旅", "歩いて回れるコンパクトさを満喫");
            case "art" -> new TripType("美", "アートと静けさの旅", "美術館で過ごす贅沢な時間");
            default -> new TripType("食", "食いしん坊の博多旅", "昼も夜も、食でつなぐ");
        };
    }

    private List<String> chips(Answers a, String pace) {
        List<String> c = new ArrayList<>();
        c.add(questions.label("days", String.valueOf(a.days())));
        c.add(questions.label("who", a.who()));
        a.main().forEach(m -> c.add(questions.label("main", m)));
        c.add(questions.label("pace", pace));
        c.add(questions.label("move", a.move()));
        return c;
    }

    private static List<String> tips(Answers a, Interpretation in, List<List<Spot>> dayPicks) {
        List<String> tips = new ArrayList<>();
        List<Spot> picked = dayPicks.stream().flatMap(List::stream).toList();
        if ("train".equals(a.move())) {
            tips.add("市内の移動は地下鉄が中心。博多駅〜天神は約5分、福岡空港〜博多駅も地下鉄で約5分です。");
        } else {
            tips.add("天神・博多の中心部は駐車場代が高め。市内観光の日は車をホテルに置いて地下鉄で動くのがおすすめです。");
        }
        if ("family".equals(a.who())) {
            tips.add("子ども連れなら、昼食は混む前の11時台に。キャナルシティや海の中道は休憩場所も多くて安心です。");
        }
        if (a.eatOrEmpty().contains("yatai") || "yatai".equals(a.night())) {
            tips.add("屋台は天候で休むことがあります。雨の日は代わりに屋台風の居酒屋を候補に。");
        }
        if (picked.stream().anyMatch(s -> s.getZone().equals("itoshima")) && !"car".equals(a.move())) {
            tips.add("糸島は電車＋バスでも行けますが本数が少なめ。時刻表を事前に確認しておきましょう。");
        }
        if (!"car".equals(a.move()) && !in.mustInclude().isEmpty()
                && in.mustInclude().stream().anyMatch(id -> picked.stream().noneMatch(s -> s.getId().equals(id)))) {
            tips.add("リクエストのうち、車がないと行きにくい場所や旅行日に休みの場所は外しています。");
        }
        if (picked.stream().anyMatch(s -> !s.closedDaysOfWeek().isEmpty())) {
            tips.add("休館日を避けて組んでいますが、祝日や臨時休館で変わることがあります。");
        }
        if (tips.size() == 1) tips.add("おみやげは博多駅でまとめて買うと荷物が少なくすみます。");
        return tips;
    }

    // ---- 小さな道具 ----

    private static Point point(Spot s) {
        return new Point(s.getLatitude(), s.getLongitude());
    }

    private static String fmt(LocalTime t) {
        return t.getHour() + ":" + String.format("%02d", t.getMinute());
    }

    private static LocalTime roundUp(LocalTime t) {
        int m = t.getMinute() % 15;
        return m == 0 ? t : t.plusMinutes(15 - m);
    }

    private static LocalTime max(LocalTime a, LocalTime b) {
        return a.isAfter(b) ? a : b;
    }
}
