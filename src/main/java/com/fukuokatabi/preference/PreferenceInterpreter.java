package com.fukuokatabi.preference;

import com.fukuokatabi.spot.Spot;

import java.util.List;

public interface PreferenceInterpreter {

    /** 実装名 */
    String name();

    /**
     * @param freeText 利用者の自由記述（空なら空の解釈を返す）
     * @param spots    候補スポット。名前の指定をIDに結びつけるのに使う
     */
    Interpretation interpret(String freeText, List<Spot> spots);

}
