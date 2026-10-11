package com.fukuokatabi.web;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;

import static org.hamcrest.Matchers.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@SpringBootTest
@AutoConfigureMockMvc
class PlanApiTest {

    @Autowired
    MockMvc mvc;

    private static String body(String answers, String freeText, String date) {
        return """
                {"answers": %s, "freeText": %s, "startDate": %s}
                """.formatted(answers,
                freeText == null ? "null" : "\"" + freeText + "\"",
                date == null ? "null" : "\"" + date + "\"");
    }

    private static String withMust(String answers, String date, String mustVisitJson) {
        return """
                {"answers": %s, "startDate": "%s", "mustVisit": %s}
                """.formatted(answers, date, mustVisitJson);
    }

    private static final String ART_DAY = """
            {"days":1,"who":"solo","main":["art"],"eat":[],"pace":"mid","move":"train","night":"rest","range":"city"}""";

    @Test
    void 絶対に行きたい場所は範囲外でも入り_その周辺で組む() throws Exception {
        // 2026-10-13 は火曜。範囲は「中心部だけ」でも太宰府を入れ、近くの九州国立博物館を組み合わせる
        mvc.perform(post("/api/plans").contentType(MediaType.APPLICATION_JSON)
                        .content(withMust(ART_DAY, "2026-10-13", "[\"dazaifu-tenmangu\"]")))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.days[0].items[*].spotId", hasItem("dazaifu-tenmangu")))
                .andExpect(jsonPath("$.days[0].items[*].spotId", hasItem("kyuhaku")))
                .andExpect(jsonPath("$.days[0].items[*].spotId", not(hasItem("fukuoka-art"))))
                .andExpect(jsonPath("$.days[0].headline", containsString("太宰府天満宮")))
                .andExpect(jsonPath("$.days[0].items[?(@.spotId == 'dazaifu-tenmangu')].why",
                        hasItem("絶対に行きたい場所")));
    }

    @Test
    void 車がなくても絶対に行きたい場所は入れて注意を出す() throws Exception {
        mvc.perform(post("/api/plans").contentType(MediaType.APPLICATION_JSON)
                        .content(withMust(ART_DAY, "2026-10-13", "[\"munakata-taisha\"]")))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.days[0].items[?(@.spotId == 'munakata-taisha')].warning",
                        hasItem(containsString("車がないと"))));
    }

    @Test
    void 休館日しかなくても入れて注意を出す() throws Exception {
        // 2026-10-12 は月曜（九州国立博物館の休館日として登録）
        mvc.perform(post("/api/plans").contentType(MediaType.APPLICATION_JSON)
                        .content(withMust(ART_DAY, "2026-10-12", "[\"kyuhaku\"]")))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.days[0].items[?(@.spotId == 'kyuhaku')].warning",
                        hasItem(containsString("休館日"))));
    }

    @Test
    void 休館日を避けて日を割り当てる() throws Exception {
        String twoDays = ART_DAY.replace("\"days\":1", "\"days\":2");
        // 1日目が月曜、2日目が火曜。九州国立博物館は火曜の日に入る
        mvc.perform(post("/api/plans").contentType(MediaType.APPLICATION_JSON)
                        .content(withMust(twoDays, "2026-10-12", "[\"kyuhaku\"]")))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.days[0].items[*].spotId", not(hasItem("kyuhaku"))))
                .andExpect(jsonPath("$.days[1].items[*].spotId", hasItem("kyuhaku")));
    }

    @Test
    void 離れた場所は別の日に_近い場所は同じ日にまとめる() throws Exception {
        String twoDays = ART_DAY.replace("\"days\":1", "\"days\":2");
        mvc.perform(post("/api/plans").contentType(MediaType.APPLICATION_JSON)
                        .content(withMust(twoDays, "2026-10-13", "[\"mojiko\", \"yanagawa\"]")))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.days[0].items[*].spotId",
                        anyOf(hasItem("mojiko"), hasItem("yanagawa"))))
                .andExpect(jsonPath("$.days[0].items[*].spotId",
                        not(allOf(hasItem("mojiko"), hasItem("yanagawa")))))
                .andExpect(jsonPath("$.days[1].items[*].spotId",
                        anyOf(hasItem("mojiko"), hasItem("yanagawa"))));

        mvc.perform(post("/api/plans").contentType(MediaType.APPLICATION_JSON)
                        .content(withMust(twoDays, "2026-10-13", "[\"kushida-jinja\", \"tochoji\"]")))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.days[*].items[?(@.why == '絶対に行きたい場所')].spotId",
                        hasSize(2)))
                .andExpect(jsonPath("$.days[1].items[*].spotId",
                        allOf(hasItem("kushida-jinja"), hasItem("tochoji"))));
    }

    @Test
    void 絶対に行きたい場所は3つまで_知らないIDは無視する() throws Exception {
        mvc.perform(post("/api/plans").contentType(MediaType.APPLICATION_JSON)
                        .content(withMust(ART_DAY, "2026-10-13", "[\"a\", \"b\", \"c\", \"d\"]")))
                .andExpect(status().isBadRequest());
        mvc.perform(post("/api/plans").contentType(MediaType.APPLICATION_JSON)
                        .content(withMust(ART_DAY, "2026-10-13", "[\"no-such-spot\"]")))
                .andExpect(status().isOk());
    }

    private static final String SAMPLE = """
            {"days":2,"who":"friends","main":["food","history"],"eat":["motsu","ramen","yatai"],
             "pace":"mid","move":"train","night":"yatai","range":"near"}""";

    @Test
    void 質問の一覧を返す() throws Exception {
        mvc.perform(get("/api/questions"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$", hasSize(8)))
                .andExpect(jsonPath("$[0].id").value("days"));
    }

    @Test
    void 回答の日数ぶんのプランを返す() throws Exception {
        mvc.perform(post("/api/plans").contentType(MediaType.APPLICATION_JSON)
                        .content(body(SAMPLE, null, "2026-10-17")))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.days", hasSize(2)))
                .andExpect(jsonPath("$.days[0].items[*].kind", hasItem("spot")))
                .andExpect(jsonPath("$.days[0].items[*].kind", hasItem("night")));
    }

    @Test
    void 車がなければ公共交通で行きにくい場所を入れない() throws Exception {
        String answers = """
                {"days":3,"who":"solo","main":["history"],"eat":[],"pace":"fast","move":"train","night":"rest","range":"far"}""";
        mvc.perform(post("/api/plans").contentType(MediaType.APPLICATION_JSON)
                        .content(body(answers, null, "2026-10-17")))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.days[*].items[*].spotId", not(hasItem("munakata-taisha"))));
    }

    @Test
    void 休館日のスポットはその日に入れない() throws Exception {
        String answers = """
                {"days":1,"who":"solo","main":["art"],"eat":[],"pace":"mid","move":"train","night":"rest","range":"near"}""";
        // 2026-10-12 は月曜（九州国立博物館・福岡市美術館の休館日として登録）
        mvc.perform(post("/api/plans").contentType(MediaType.APPLICATION_JSON)
                        .content(body(answers, null, "2026-10-12")))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.days[*].items[*].spotId", not(hasItem("kyuhaku"))))
                .andExpect(jsonPath("$.days[*].items[*].spotId", not(hasItem("fukuoka-art"))));
        // 翌日の火曜なら入る
        mvc.perform(post("/api/plans").contentType(MediaType.APPLICATION_JSON)
                        .content(body(answers, null, "2026-10-13")))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.days[*].items[*].spotId", hasItem("kyuhaku")));
    }

    @Test
    void 自由記述で名指しした場所は範囲外でも入れる() throws Exception {
        String answers = """
                {"days":1,"who":"couple","main":["shop"],"eat":[],"pace":"mid","move":"car","night":"view","range":"city"}""";
        mvc.perform(post("/api/plans").contentType(MediaType.APPLICATION_JSON)
                        .content(body(answers, "宗像大社にはお参りしたい", "2026-10-17")))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.days[*].items[*].spotId", hasItem("munakata-taisha")))
                .andExpect(jsonPath("$.interpretation.notes", hasItem(containsString("宗像大社"))));
    }

    @Test
    void 回答が不正なら400() throws Exception {
        String answers = """
                {"days":9,"who":"alien","main":[],"pace":"mid","move":"train","night":"rest","range":"city"}""";
        mvc.perform(post("/api/plans").contentType(MediaType.APPLICATION_JSON)
                        .content(body(answers, null, null)))
                .andExpect(status().isBadRequest());
    }
}
