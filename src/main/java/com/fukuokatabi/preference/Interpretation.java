package com.fukuokatabi.preference;

import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * 自由記述から読み取った好み。プランの点数づけに上乗せする。
 *
 * @param source       解釈した実装名
 * @param tagBoosts    タグごとの加点（例: nature → 2）
 * @param mustInclude  必ず入れたいスポットID
 * @param exclude      外したいスポットID
 * @param preferIndoor 雨や暑さなどで屋内を優先したいか
 * @param paceOverride slow / mid / fast への変更（なければ null）
 * @param notes        画面に出す「こう受け取りました」の説明
 */
public record Interpretation(
        String source,
        Map<String, Integer> tagBoosts,
        Set<String> mustInclude,
        Set<String> exclude,
        boolean preferIndoor,
        String paceOverride,
        List<String> notes) {

    public static Interpretation empty(String source) {
        return new Interpretation(source, Map.of(), Set.of(), Set.of(), false, null, List.of());
    }
}
