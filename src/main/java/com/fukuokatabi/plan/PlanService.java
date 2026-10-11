package com.fukuokatabi.plan;

import com.fukuokatabi.plan.PlanRequest.Answers;
import com.fukuokatabi.plan.PlanResponse.*;
import com.fukuokatabi.preference.Interpretation;
import com.fukuokatabi.preference.PreferenceInterpreter;
import com.fukuokatabi.question.QuestionCatalog;
import com.fukuokatabi.spot.Spot;
import com.fukuokatabi.spot.SpotRepository;
import com.fukuokatabi.travel.EstimatedTravelTimeProvider;
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

    /** 絶対に行きたい場所の日に、周辺として組み込む距離 */
    static final double NEARBY_KM = 12.0;
    /** 絶対に行きたい場所どうしを同じ日にまとめる距離 */
    static final double SAME_DAY_KM = 15.0;

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

    /**
     * 1日分の割り当て。
     *
     * @param spots    回る順のスポット
     * @param anchors  その日の「絶対に行きたい場所」
     * @param why      スポットIDごとの、選んだ理由の上書き
     * @param warnings スポットIDごとの注意
     */
    record DayPick(List<Spot> spots, List<Spot> anchors, Map<String, String> why, Map<String, String> warnings) {}

    public PlanResponse plan(PlanRequest req) {
        Answers a = req.answers();
        List<Spot> all = spots.findAll();
        Interpretation in = interpreter.interpret(req.freeText(), all);
        LocalDate start = req.startDate() != null ? req.startDate() : LocalDate.now().plusDays(7);
        String pace = in.paceOverride() != null ? in.paceOverride() : a.pace();
        Mode mode = "car".equals(a.move()) ? Mode.DRIVE : Mode.TRANSIT;

        List<Spot> anchors = anchors(req.mustVisitOrEmpty(), in, all);
        Set<String> anchorIds = anchors.stream().map(Spot::getId).collect(Collectors.toSet());

        // 絶対に行きたい場所の周辺は、行動範囲の設定に関係なく候補にする
        List<Scored> nearbyPool = all.stream()
                .filter(s -> s.getCategory() == Spot.Category.SIGHT)
                .filter(s -> !anchorIds.contains(s.getId()))
                .filter(s -> reachable(s, a, in))
                .map(s -> new Scored(s, score(s, a, in, start)))
                .sorted(Comparator.comparingDouble(Scored::score).reversed())
                .toList();
        List<Scored> pool = nearbyPool.stream().filter(sc -> inRange(sc.spot(), a)).toList();

        List<DayPick> dayPicks = pickPerDay(pool, nearbyPool, anchors, a, pace, start);
        if (!dayPicks.isEmpty()) {
            Map<String, String> why = dayPicks.get(0).why();
            in.mustInclude().stream()
                    .filter(id -> !req.mustVisitOrEmpty().contains(id))
                    .forEach(id -> why.computeIfPresent(id, (k, v) -> "自由記述でリクエスト"));
        }

        List<Day> days = new ArrayList<>();
        for (int i = 0; i < dayPicks.size(); i++) {
            days.add(buildDay(i, dayPicks.get(i), start.plusDays(i), a, in, pace, mode, all));
        }

        return new PlanResponse(
                tripType(a),
                chips(a, pace),
                days,
                tips(a, dayPicks),
                new InterpretationSummary(in.source(), in.notes()));
    }

    // ---- 候補の絞り込みと点数 ----

    /**
     * 「絶対に行きたい場所」。画面で選んだものを先に、自由記述で名指ししたものを後に並べる。
     * 範囲・移動手段・自由記述での除外に関係なく、必ずプランに入れる。
     */
    static List<Spot> anchors(List<String> mustVisit, Interpretation in, List<Spot> all) {
        Map<String, Spot> byId = all.stream()
                .filter(s -> s.getCategory() == Spot.Category.SIGHT)
                .collect(Collectors.toMap(Spot::getId, s -> s));
        LinkedHashSet<String> ids = new LinkedHashSet<>(mustVisit);
        ids.addAll(in.mustInclude());
        return ids.stream().map(byId::get).filter(Objects::nonNull).toList();
    }

    /** 自由記述で外されておらず、選んだ移動手段で行けるか（絶対に行きたい場所は別扱い） */
    static boolean reachable(Spot s, Answers a, Interpretation in) {
        if (in.exclude().contains(s.getId())) return false;
        return "car".equals(a.move()) || s.isTransitOk();
    }

    /** 「市内中心部の外にも行きますか？」の答えの範囲内か */
    static boolean inRange(Spot s, Answers a) {
        return switch (a.range()) {
            case "city" -> s.getFarLevel() == 0;
            case "near" -> s.getFarLevel() <= 1;
            default -> true;
        };
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

    /**
     * @param pool       行動範囲内の候補（点数順）
     * @param nearbyPool 行動範囲に関係ない候補（絶対に行きたい場所の周辺を埋めるのに使う）
     */
    List<DayPick> pickPerDay(List<Scored> pool, List<Scored> nearbyPool, List<Spot> anchors,
                             Answers a, String pace, LocalDate start) {
        int days = a.days();
        int per = switch (pace) { case "slow" -> 2; case "fast" -> 4; default -> 3; };
        int maxMinutes = per <= 2 ? 360 : per == 3 ? 450 : 540;
        boolean car = "car".equals(a.move());

        List<List<Spot>> picks = new ArrayList<>();
        List<List<Spot>> dayAnchors = new ArrayList<>();
        for (int d = 0; d < days; d++) {
            picks.add(new ArrayList<>());
            dayAnchors.add(new ArrayList<>());
        }
        Map<String, String> why = new HashMap<>();
        Map<String, String> warnings = new HashMap<>();
        Set<String> used = new HashSet<>();

        // 1. 絶対に行きたい場所を、近いものどうしでまとめて日に割り当てる
        for (List<Spot> group : groupAnchors(anchors, days, maxMinutes)) {
            int day = chooseDay(group, dayAnchors, start);
            boolean allOpen = group.stream().allMatch(s -> openOn(s, start.plusDays(day)));
            for (Spot s : group) {
                dayAnchors.get(day).add(s);
                picks.get(day).add(s);
                used.add(s.getId());
                why.put(s.getId(), "絶対に行きたい場所");
                if (!allOpen && !openOn(s, start.plusDays(day))) {
                    warnings.put(s.getId(), dowLabel(start.plusDays(day)) + "曜は休館日の可能性があります。公式情報で確認を");
                } else if (!car && !s.isTransitOk()) {
                    warnings.put(s.getId(), "車がないと行きにくい場所です。バスやタクシーを事前に確認");
                }
            }
        }

        // 2. 絶対に行きたい場所がある日は、その周辺のスポットで埋める
        for (int d = 0; d < days; d++) {
            List<Spot> group = dayAnchors.get(d);
            if (group.isEmpty()) continue;
            LocalDate date = start.plusDays(d);
            Point center = centroid(group);
            String centerName = group.get(0).getName();
            List<Spot> dayList = picks.get(d);
            int minutes = dayList.stream().mapToInt(Spot::getDurationMinutes).sum();
            List<Scored> nearby = nearbyPool.stream()
                    .filter(sc -> !used.contains(sc.spot().getId()) && openOn(sc.spot(), date))
                    .filter(sc -> km(center, point(sc.spot())) <= NEARBY_KM)
                    .sorted(Comparator.comparingDouble(
                            (Scored sc) -> sc.score() - 0.3 * km(center, point(sc.spot()))).reversed())
                    .toList();
            for (Scored sc : nearby) {
                if (dayList.size() >= per) break;
                if (minutes + sc.spot().getDurationMinutes() > maxMinutes) continue;
                dayList.add(sc.spot());
                used.add(sc.spot().getId());
                minutes += sc.spot().getDurationMinutes();
                why.put("near:" + sc.spot().getId(), centerName + "の近く");
            }
        }

        // 3. 残りの日は、好みに合う郊外エリアか市内中心部で組む
        Set<String> anchorZones = anchors.stream().map(Spot::getZone).collect(Collectors.toSet());
        Deque<String> excursionZones = pool.stream()
                .filter(sc -> !sc.spot().isCityCenter() && sc.score() > 0)
                .map(sc -> sc.spot().getZone())
                .filter(z -> !anchorZones.contains(z))
                .distinct()
                .collect(Collectors.toCollection(ArrayDeque::new));

        for (int d = 0; d < days; d++) {
            if (!dayAnchors.get(d).isEmpty()) continue;
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

            List<Spot> dayList = picks.get(d);
            if (zone != null) {
                String z = zone;
                pool.stream()
                        .filter(sc -> sc.spot().getZone().equals(z) && !used.contains(sc.spot().getId())
                                && openOn(sc.spot(), date))
                        .limit(Math.max(1, per - 1))
                        .forEach(sc -> dayList.add(sc.spot()));
            }
            int minutes = dayList.stream().mapToInt(Spot::getDurationMinutes).sum();
            for (Scored sc : pool) {
                Spot s = sc.spot();
                if (dayList.size() >= per) break;
                if (!s.isCityCenter() || used.contains(s.getId()) || dayList.contains(s) || !openOn(s, date)) continue;
                if (minutes + s.getDurationMinutes() > maxMinutes) continue;
                dayList.add(s);
                minutes += s.getDurationMinutes();
            }
            dayList.forEach(s -> used.add(s.getId()));
        }

        // 回る順: 郊外が先、次に朝向き→どこでも→午後向き、同じなら早く閉まる順
        List<DayPick> result = new ArrayList<>();
        for (int d = 0; d < days; d++) {
            List<Spot> dayList = picks.get(d);
            dayList.sort(Comparator
                    .comparing((Spot s) -> s.isCityCenter())
                    .thenComparing(Spot::getSlot)
                    .thenComparing(s -> s.getCloseTime() == null ? LocalTime.MAX : s.getCloseTime()));
            result.add(new DayPick(dayList, dayAnchors.get(d), why, warnings));
        }
        return result;
    }

    /**
     * 絶対に行きたい場所を、近いものどうしで1日分ずつにまとめる。
     * まとまりが日数より多ければ、いちばん近いまとまりどうしを合わせて日数に収める。
     */
    static List<List<Spot>> groupAnchors(List<Spot> anchors, int days, int maxMinutes) {
        List<List<Spot>> groups = new ArrayList<>();
        for (Spot s : anchors) {
            Optional<List<Spot>> fit = groups.stream()
                    .filter(g -> km(centroid(g), point(s)) <= SAME_DAY_KM)
                    .filter(g -> g.stream().mapToInt(Spot::getDurationMinutes).sum()
                            + s.getDurationMinutes() <= maxMinutes + 60)
                    .findFirst();
            if (fit.isPresent()) {
                fit.get().add(s);
            } else {
                groups.add(new ArrayList<>(List.of(s)));
            }
        }
        while (groups.size() > days) {
            int bi = 0, bj = 1;
            double best = Double.MAX_VALUE;
            for (int i = 0; i < groups.size(); i++) {
                for (int j = i + 1; j < groups.size(); j++) {
                    double dist = km(centroid(groups.get(i)), centroid(groups.get(j)));
                    if (dist < best) {
                        best = dist;
                        bi = i;
                        bj = j;
                    }
                }
            }
            groups.get(bi).addAll(groups.remove(bj));
        }
        // 遠いまとまりから先に日を決める（最終日を遠出の日にしないため）
        groups.sort(Comparator.comparingInt((List<Spot> g) ->
                g.stream().mapToInt(Spot::getFarLevel).max().orElse(0)).reversed());
        return groups;
    }

    /** まだ空いている日のうち、全員が開いている日を選ぶ。遠出は最終日を避ける */
    private static int chooseDay(List<Spot> group, List<List<Spot>> dayAnchors, LocalDate start) {
        int days = dayAnchors.size();
        boolean far = group.stream().anyMatch(s -> !s.isCityCenter());
        List<Integer> order = new ArrayList<>();
        for (int d = 0; d < days; d++) order.add(d);
        if (!far && days > 1) {
            // 市内の場所は最終日から埋め、初日・中日を遠出に残す
            Collections.reverse(order);
        }
        for (int d : order) {
            if (!dayAnchors.get(d).isEmpty()) continue;
            LocalDate date = start.plusDays(d);
            if (group.stream().allMatch(s -> openOn(s, date))) return d;
        }
        for (int d : order) {
            if (dayAnchors.get(d).isEmpty()) return d;
        }
        return order.get(0);
    }

    // ---- 時間割 ----

    Day buildDay(int index, DayPick pick, LocalDate date, Answers a, Interpretation in,
                 String pace, Mode mode, List<Spot> all) {
        List<Spot> picks = pick.spots();
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

            String warning = pick.warnings().get(s.getId());
            LocalTime end = t.plusMinutes(s.getDurationMinutes());
            if (warning == null && s.getCloseTime() != null && end.isAfter(s.getCloseTime())) {
                warning = "閉館（" + s.getCloseTime() + "ごろ）が近いので早めに";
            }
            items.add(new Item("spot", fmt(t), s.getName(), s.getArea(), noteWithSeason(s, date),
                    why(s, a, in, pick), warning, s.getLatitude(), s.getLongitude(), s.getId(), s.getDurationMinutes()));
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
        if (!pick.anchors().isEmpty()) {
            headline = pick.anchors().get(0).getName() + (pick.anchors().size() > 1 ? "ほか" : "") + "を中心にめぐる日";
        } else {
            Optional<Spot> excursion = picks.stream().filter(s -> !s.isCityCenter()).findFirst();
            headline = excursion.map(s -> s.getArea() + "へ足をのばす日").orElse("市内中心部をめぐる日");
        }
        String dateLabel = date + "（" + dowLabel(date) + "）";
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

    private String why(Spot s, Answers a, Interpretation in, DayPick pick) {
        String fixed = pick.why().get(s.getId());
        if (fixed != null) return fixed;
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
        return pick.why().get("near:" + s.getId());
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

    private static List<String> tips(Answers a, List<DayPick> dayPicks) {
        List<String> tips = new ArrayList<>();
        List<Spot> picked = dayPicks.stream().flatMap(d -> d.spots().stream()).toList();
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
        if (!dayPicks.isEmpty() && !dayPicks.get(0).warnings().isEmpty()) {
            tips.add("注意のついた「絶対に行きたい場所」は、休館日や交通手段を事前に確認してください。");
        }
        if (picked.stream().anyMatch(s -> !s.closedDaysOfWeek().isEmpty())) {
            tips.add("休館日を避けて組んでいますが、祝日や臨時休館で変わることがあります。");
        }
        if (tips.size() == 1) tips.add("おみやげは博多駅でまとめて買うと荷物が少なくすみます。");
        return tips;
    }

    // ---- 小さな道具 ----

    private static double km(Point a, Point b) {
        return EstimatedTravelTimeProvider.haversineKm(a, b);
    }

    private static Point centroid(List<Spot> group) {
        double lat = group.stream().mapToDouble(Spot::getLatitude).average().orElse(BASE.lat());
        double lng = group.stream().mapToDouble(Spot::getLongitude).average().orElse(BASE.lng());
        return new Point(lat, lng);
    }

    private static String dowLabel(LocalDate date) {
        return date.getDayOfWeek().getDisplayName(TextStyle.SHORT, Locale.JAPANESE);
    }

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
