package com.fukuokatabi.travel;

public interface TravelTimeProvider {

    enum Mode { TRANSIT, DRIVE }

    record Point(double lat, double lng) {}

    /**
     * @param minutes 所要時間（分）
     * @param km      距離
     * @param source  estimate / google
     */
    record Leg(int minutes, double km, Mode mode, String source) {}

    /** 実装名（estimate / google） */
    String name();

    Leg between(Point from, Point to, Mode mode);
}
