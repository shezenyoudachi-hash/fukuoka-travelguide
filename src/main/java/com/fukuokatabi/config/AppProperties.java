package com.fukuokatabi.config;

import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * 外部サービスの設定。キーが空なら、キー不要の実装（ルールベース解釈・距離からの移動時間推定）で動く。
 */
@ConfigurationProperties(prefix = "app")
public record AppProperties(Anthropic anthropic, Google google) {

    public record Anthropic(String apiKey, String model, String baseUrl) {
        public boolean enabled() {
            return apiKey != null && !apiKey.isBlank();
        }
    }

    public record Google(String apiKey, String routesUrl) {
        public boolean enabled() {
            return apiKey != null && !apiKey.isBlank();
        }
    }
}
