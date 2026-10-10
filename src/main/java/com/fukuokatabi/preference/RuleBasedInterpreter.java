package com.fukuokatabi.preference;

import com.fukuokatabi.spot.Spot;

import java.util.*;
import java.util.regex.Pattern;

/**
 * APIキーなしで動く、キーワード辞書による解釈。Claude が使えないときの代替にもなる。
 */
public class RuleBasedInterpreter implements PreferenceInterpreter {

    private record Rule(Pattern pattern, Map<String, Integer> boosts, String note) {
        static Rule of(String regex, String note, Object... tagWeight) {
            Map<String, Integer> m = new LinkedHashMap<>();
            for (int i = 0; i < tagWeight.length; i += 2) {
                m.put((String) tagWeight[i], (Integer) tagWeight[i + 1]);
            }
            return new Rule(Pattern.compile(regex), m, note);
        }
    }

    private static final List<Rule> RULES = List.of(
            Rule.of("子ども|子供|こども|キッズ|赤ちゃん|ベビー", "子ども連れで楽しめる場所を優先", "family", 2),
            Rule.of("夕日|夕焼け|サンセット|海(?!鮮)|ビーチ|絶景", "海や景色のきれいな場所を優先", "nature", 2, "couple", 1),
            Rule.of("写真|映え|インスタ", "写真映えする場所を優先", "nature", 1, "couple", 1, "shop", 1),
            Rule.of("神社|お寺|寺|歴史|御朱印", "神社・歴史スポットを優先", "history", 2),
            Rule.of("美術|アート|博物館|展示", "美術館・博物館を優先", "art", 2),
            Rule.of("買い物|ショッピング|お土産|雑貨|古着", "買い物・街歩きを優先", "shop", 2),
            Rule.of("海鮮|魚|寿司|刺身|ごまさば", "海鮮を優先", "seafood", 2),
            Rule.of("ラーメン|豚骨", "ラーメンを優先", "ramen", 2),
            Rule.of("もつ鍋", "もつ鍋を優先", "motsu", 2),
            Rule.of("水炊き", "水炊きを優先", "mizutaki", 2),
            Rule.of("屋台", "屋台を優先", "yatai", 2),
            Rule.of("自然|花|公園|散歩|島", "自然や公園を優先", "nature", 2),
            Rule.of("カフェ|喫茶|スイーツ", "カフェのあるエリアを優先", "shop", 1, "couple", 1)
    );

    private static final Pattern INDOOR = Pattern.compile("雨|暑い|暑さ|寒い|屋内|室内");
    private static final Pattern SLOW = Pattern.compile("ゆっくり|のんびり|疲れ|歩きたくない|歩くのが|足が|ベビーカー|車いす|車椅子|高齢");
    private static final Pattern FAST = Pattern.compile("たくさん回|詰め込|欲張|全部");
    private static final Pattern NEGATION = Pattern.compile("(以外|は不要|はいらない|行かない|行きたくない|苦手|なし|嫌い)");

    @Override
    public String name() {
        return "rule";
    }

    @Override
    public Interpretation interpret(String freeText, List<Spot> spots) {
        if (freeText == null || freeText.isBlank()) {
            return Interpretation.empty(name());
        }
        String text = freeText.strip();
        Map<String, Integer> boosts = new LinkedHashMap<>();
        List<String> notes = new ArrayList<>();
        Set<String> must = new LinkedHashSet<>();
        Set<String> exclude = new LinkedHashSet<>();

        // 文ごとに見て、否定語を含む文のスポット名は除外として扱う
        for (String sentence : text.split("[。．.!！?？\\n、,]")) {
            if (sentence.isBlank()) continue;
            boolean negative = NEGATION.matcher(sentence).find();
            for (Spot s : spots) {
                if (mentions(sentence, s)) {
                    if (negative) {
                        if (exclude.add(s.getId())) notes.add(s.getName() + "は外しました");
                    } else if (s.getCategory() == Spot.Category.SIGHT) {
                        if (must.add(s.getId())) notes.add(s.getName() + "を必ず入れました");
                    }
                }
            }
            for (Rule r : RULES) {
                if (!r.pattern().matcher(sentence).find()) continue;
                if (negative) {
                    // 「屋台は苦手」のような文は、その好みを下げる
                    r.boosts().keySet().forEach(k -> boosts.put(k, -3));
                    String note = "苦手・不要とのことなので控えめにしました";
                    if (!notes.contains(note)) notes.add(note);
                } else {
                    r.boosts().forEach((k, v) -> boosts.merge(k, v, Math::max));
                    if (!notes.contains(r.note())) notes.add(r.note());
                }
            }
        }

        boolean indoor = INDOOR.matcher(text).find();
        if (indoor) notes.add("屋内で楽しめる場所を多めにしました");
        String pace = null;
        if (SLOW.matcher(text).find()) {
            pace = "slow";
            notes.add("移動と見学をゆったりめにしました");
        } else if (FAST.matcher(text).find()) {
            pace = "fast";
            notes.add("回る場所を多めにしました");
        }
        return new Interpretation(name(), boosts, must, exclude, indoor, pace, notes);
    }

    /** 広すぎて特定のスポットを指さない地名 */
    private static final Set<String> GENERIC = Set.of(
            "博多", "天神", "福岡", "中洲", "中洲川端", "渡辺通", "赤坂", "博多・天神", "中道");

    /**
     * 「太宰府天満宮」も「太宰府」も、「二見ヶ浦」も拾えるよう、
     * 名前・名前の区切り（・や「の」）ごとの語・エリア名・名前の先頭4文字で照合する。
     */
    static boolean mentions(String sentence, Spot s) {
        String name = s.getName();
        if (sentence.contains(name)) return true;
        if (s.getCategory() != Spot.Category.SIGHT) return false;
        List<String> tokens = new ArrayList<>(Arrays.asList(name.split("[・の]")));
        tokens.add(s.getArea());
        if (name.length() > 4) tokens.add(name.substring(0, 4));
        for (String t : tokens) {
            if (t.length() >= 2 && !GENERIC.contains(t) && sentence.contains(t)) return true;
        }
        return false;
    }
}
