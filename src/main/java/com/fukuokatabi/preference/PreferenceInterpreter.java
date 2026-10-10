package com.fukuokatabi.preference;

import com.fukuokatabi.spot.Spot;

import java.util.List;

public interface PreferenceInterpreter {

    /** 実装名（rule / claude） */
    String name();

    /**
     * @param freeText 利用者の自由記述（空なら空の解釈を返す）
     * @param spots    候補スポット。名前の指定をIDに結びつけるのに使う
     */
    Interpretation interpret(String freeText, List<Spot> spots);

    /** 解釈で使えるタグ。Claude への指示にも使う */
    List<String> KNOWN_TAGS = List.of(
            "food", "history", "nature", "shop", "art",
            "ramen", "motsu", "mizutaki", "yatai", "seafood",
            "family", "couple", "friends", "slow");
}
