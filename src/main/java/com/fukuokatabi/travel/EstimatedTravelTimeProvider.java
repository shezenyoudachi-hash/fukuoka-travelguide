package com.fukuokatabi.travel;

import org.springframework.stereotype.Component;

/**
 * 直線距離からの所要時間の推定。外部サービスは使わない。
 * 道のりは直線の約1.3倍とし、乗り換え・待ち時間を固定で足す。
 */
@Component
public class EstimatedTravelTimeProvider implements TravelTimeProvider {

    private static final double ROAD_FACTOR = 1.3;
    private static final double WALK_KMH = 4.5;

    @Override
    public String name() {
        return "estimate";
    }

    @Override
    public Leg between(Point from, Point to, Mode mode) {
        double km = haversineKm(from, to) * ROAD_FACTOR;
        double minutes;
        if (km < 1.0) {
            minutes = km / WALK_KMH * 60;               // 1km 未満は徒歩
        } else if (mode == Mode.DRIVE) {
            minutes = 5 + km / 30.0 * 60;               // 駐車などに5分、市街地は平均30km/h
        } else {
            minutes = 12 + km / (km > 15 ? 45.0 : 22.0) * 60;  // 駅までと待ち時間で12分。遠距離は電車の速さ
        }
        return new Leg((int) Math.ceil(minutes / 5.0) * 5, Math.round(km * 10) / 10.0, mode, name());
    }

    public static double haversineKm(Point a, Point b) {
        double r = 6371.0;
        double dLat = Math.toRadians(b.lat() - a.lat());
        double dLng = Math.toRadians(b.lng() - a.lng());
        double h = Math.sin(dLat / 2) * Math.sin(dLat / 2)
                + Math.cos(Math.toRadians(a.lat())) * Math.cos(Math.toRadians(b.lat()))
                * Math.sin(dLng / 2) * Math.sin(dLng / 2);
        return 2 * r * Math.asin(Math.sqrt(h));
    }
}
