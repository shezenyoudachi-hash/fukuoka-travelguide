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
