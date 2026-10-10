package com.fukuokatabi.question;

import org.springframework.stereotype.Component;

import java.util.List;

/**
 * 質問の定義。Web・iOS の両方が GET /api/questions から取得して画面を組み立てる。
 */
@Component
public class QuestionCatalog {

    public record Option(String value, String label, String sub) {}

    public record Question(String id, String text, String hint, int multi, List<Option> options) {
        public String labelOf(String value) {
            return options.stream().filter(o -> o.value().equals(value))
                    .map(Option::label).findFirst().orElse("");
        }
    }

    private final List<Question> questions = List.of(
            new Question("days", "何日間の旅ですか？", null, 0, List.of(
                    new Option("1", "日帰り", "朝から夜まで1日"),
                    new Option("2", "1泊2日", "いちばん人気の長さ"),
                    new Option("3", "2泊3日", "郊外まで足をのばせる"))),
            new Question("who", "誰と行きますか？", null, 0, List.of(
                    new Option("solo", "ひとり", "自分のペースで"),
                    new Option("friends", "友だち", "わいわい食べ歩き"),
                    new Option("couple", "パートナー", "景色や雰囲気も大事"),
                    new Option("family", "家族・子ども連れ", "移動はゆとりをもって"))),
            new Question("main", "旅でいちばん楽しみなのは？", "2つまで選べます", 2, List.of(
                    new Option("food", "食べること", "ラーメン・もつ鍋・屋台"),
                    new Option("history", "神社・歴史", "太宰府や城跡"),
                    new Option("nature", "海と自然", "島・海岸・公園"),
                    new Option("shop", "買い物・街歩き", "天神・博多・カフェ"),
                    new Option("art", "アート・博物館", "美術館をゆっくり"))),
            new Question("eat", "これだけは食べたい！", "いくつでも", 5, List.of(
                    new Option("ramen", "豚骨ラーメン", "締めにも"),
                    new Option("motsu", "もつ鍋", "夜ごはんの定番"),
                    new Option("mizutaki", "水炊き", "鶏の旨みスープ"),
                    new Option("yatai", "屋台", "中洲の川沿いで"),
                    new Option("seafood", "海鮮・ごまさば", "朝の市場も"))),
            new Question("pace", "旅のペースは？", null, 0, List.of(
                    new Option("slow", "のんびり", "1日2〜3か所"),
                    new Option("mid", "ほどほど", "1日3〜4か所"),
                    new Option("fast", "とことん回る", "1日5か所以上"))),
            new Question("move", "移動手段は？", null, 0, List.of(
                    new Option("train", "電車・バス・地下鉄", "市内は地下鉄が便利"),
                    new Option("car", "レンタカー", "糸島・宗像も回りやすい"))),
            new Question("night", "夜の過ごし方は？", null, 0, List.of(
                    new Option("yatai", "屋台をはしご", "中洲・天神"),
                    new Option("bar", "落ち着いたお店で一杯", "大名・中洲"),
                    new Option("view", "夜景を見たい", "福岡タワーなど"),
                    new Option("rest", "早めにホテルへ", "翌日にそなえる"))),
            new Question("range", "市内中心部の外にも行きますか？", null, 0, List.of(
                    new Option("city", "中心部だけ", "博多・天神エリア"),
                    new Option("near", "近郊もOK", "太宰府・糸島・海の中道"),
                    new Option("far", "遠出も歓迎", "柳川・門司港も候補に")))
    );

    public List<Question> all() {
        return questions;
    }

    public String label(String questionId, String value) {
        return questions.stream().filter(q -> q.id().equals(questionId))
                .findFirst().map(q -> q.labelOf(value)).orElse("");
    }
}
