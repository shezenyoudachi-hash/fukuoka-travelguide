package com.fukuokatabi.config;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fukuokatabi.preference.ClaudeInterpreter;
import com.fukuokatabi.preference.PreferenceInterpreter;
import com.fukuokatabi.preference.RuleBasedInterpreter;
import com.fukuokatabi.travel.EstimatedTravelTimeProvider;
import com.fukuokatabi.travel.GoogleRoutesTravelTimeProvider;
import com.fukuokatabi.travel.TravelTimeProvider;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * APIキーの有無で実装を切り替える。キーがなくてもアプリは最後まで動く。
 */
@Configuration
public class ProviderConfig {

    private static final Logger log = LoggerFactory.getLogger(ProviderConfig.class);

    @Bean
    PreferenceInterpreter preferenceInterpreter(AppProperties props, ObjectMapper mapper) {
        RuleBasedInterpreter rule = new RuleBasedInterpreter();
        if (props.anthropic() != null && props.anthropic().enabled()) {
            log.info("自由記述の解釈: Claude API（{}）", props.anthropic().model());
            return new ClaudeInterpreter(props.anthropic(), rule, mapper);
        }
        log.info("自由記述の解釈: ルールベース（ANTHROPIC_API_KEY 未設定）");
        return rule;
    }

    @Bean
    TravelTimeProvider travelTimeProvider(AppProperties props, ObjectMapper mapper) {
        EstimatedTravelTimeProvider estimate = new EstimatedTravelTimeProvider();
        if (props.google() != null && props.google().enabled()) {
            log.info("移動時間: Google Routes API");
            return new GoogleRoutesTravelTimeProvider(props.google(), estimate, mapper);
        }
        log.info("移動時間: 直線距離からの推定（GOOGLE_MAPS_API_KEY 未設定）");
        return estimate;
    }
}
