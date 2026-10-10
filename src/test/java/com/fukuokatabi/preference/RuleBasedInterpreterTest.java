package com.fukuokatabi.preference;

import com.fukuokatabi.spot.Spot;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

class RuleBasedInterpreterTest {

    private final RuleBasedInterpreter interpreter = new RuleBasedInterpreter();

    private static Spot sight(String id, String name, String area) {
        return new Spot(id, Spot.Category.SIGHT, name, "z", area, 33.5, 130.4, Spot.Slot.ANY,
                60, 0, false, true, false, "", Map.of());
    }

    private final List<Spot> spots = List.of(
            sight("dazaifu-tenmangu", "太宰府天満宮", "太宰府"),
            sight("futamigaura", "糸島・二見ヶ浦の夫婦岩", "糸島"),
            sight("canal-city", "キャナルシティ博多", "博多"));

    @Test
    void 空なら何もしない() {
        Interpretation r = interpreter.interpret("  ", spots);
        assertThat(r.tagBoosts()).isEmpty();
        assertThat(r.notes()).isEmpty();
    }

    @Test
    void 地名や名前の一部でもスポットを拾う() {
        Interpretation r = interpreter.interpret("太宰府と二見ヶ浦に行きたい", spots);
        assertThat(r.mustInclude()).containsExactlyInAnyOrder("dazaifu-tenmangu", "futamigaura");
    }

    @Test
    void 否定の文に出てきた場所は外す() {
        Interpretation r = interpreter.interpret("キャナルシティは行かない。神社が好き", spots);
        assertThat(r.exclude()).containsExactly("canal-city");
        assertThat(r.tagBoosts()).containsEntry("history", 2);
    }

    @Test
    void 苦手なものは下げる() {
        Interpretation r = interpreter.interpret("屋台は苦手です", spots);
        assertThat(r.tagBoosts()).containsEntry("yatai", -3);
    }

    @Test
    void 雨と体力の希望を読み取る() {
        Interpretation r = interpreter.interpret("雨予報なので屋内多めで。子どもが歩き疲れないように", spots);
        assertThat(r.preferIndoor()).isTrue();
        assertThat(r.paceOverride()).isEqualTo("slow");
        assertThat(r.tagBoosts()).containsEntry("family", 2);
    }

    @Test
    void 海鮮は海の景色と取り違えない() {
        Interpretation r = interpreter.interpret("海鮮が食べたい", spots);
        assertThat(r.tagBoosts()).containsEntry("seafood", 2).doesNotContainKey("nature");
    }
}
