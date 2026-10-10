package com.fukuokatabi.travel;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.fukuokatabi.config.AppProperties;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Google Routes API（computeRoutes）で実際の所要時間を取る。
 * 同じ区間は結果を使い回し、失敗したときは推定値に切り替える。
 */
public class GoogleRoutesTravelTimeProvider implements TravelTimeProvider {

    private static final Logger log = LoggerFactory.getLogger(GoogleRoutesTravelTimeProvider.class);

    private final AppProperties.Google config;
    private final TravelTimeProvider fallback;
    private final ObjectMapper mapper;
    private final HttpClient http = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(5)).build();
    private final Map<String, Leg> cache = new ConcurrentHashMap<>();

    public GoogleRoutesTravelTimeProvider(AppProperties.Google config, TravelTimeProvider fallback, ObjectMapper mapper) {
        this.config = config;
        this.fallback = fallback;
        this.mapper = mapper;
    }

    @Override
    public String name() {
        return "google";
    }

    @Override
    public Leg between(Point from, Point to, Mode mode) {
        String key = "%.4f,%.4f>%.4f,%.4f/%s".formatted(from.lat(), from.lng(), to.lat(), to.lng(), mode);
        return cache.computeIfAbsent(key, k -> fetch(from, to, mode));
    }

    private Leg fetch(Point from, Point to, Mode mode) {
        try {
            ObjectNode body = mapper.createObjectNode();
            latLng(body.putObject("origin"), from);
            latLng(body.putObject("destination"), to);
            body.put("travelMode", mode == Mode.DRIVE ? "DRIVE" : "TRANSIT");
            body.put("languageCode", "ja");

            HttpRequest req = HttpRequest.newBuilder(URI.create(config.routesUrl()))
                    .timeout(Duration.ofSeconds(10))
                    .header("Content-Type", "application/json")
                    .header("X-Goog-Api-Key", config.apiKey())
                    .header("X-Goog-FieldMask", "routes.duration,routes.distanceMeters")
                    .POST(HttpRequest.BodyPublishers.ofString(mapper.writeValueAsString(body)))
                    .build();
            HttpResponse<String> res = http.send(req, HttpResponse.BodyHandlers.ofString());
            if (res.statusCode() != 200) {
                throw new IllegalStateException("Routes API status " + res.statusCode() + ": " + res.body());
            }
            JsonNode route = mapper.readTree(res.body()).path("routes").path(0);
            if (route.isMissingNode()) {
                // 近すぎて公共交通の経路が出ない場合など
                return fallback.between(from, to, mode);
            }
            int seconds = Integer.parseInt(route.path("duration").asText("0s").replace("s", ""));
            double km = route.path("distanceMeters").asDouble() / 1000.0;
            return new Leg((int) Math.ceil(seconds / 60.0 / 5.0) * 5, Math.round(km * 10) / 10.0, mode, name());
        } catch (Exception e) {
            log.warn("Routes API の取得に失敗したため推定値を使います: {}", e.toString());
            return fallback.between(from, to, mode);
        }
    }

    private static void latLng(ObjectNode waypoint, Point p) {
        ObjectNode ll = waypoint.putObject("location").putObject("latLng");
        ll.put("latitude", p.lat());
        ll.put("longitude", p.lng());
    }
}
