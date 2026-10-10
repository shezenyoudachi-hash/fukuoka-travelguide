package com.fukuokatabi.travel;

import com.fukuokatabi.travel.TravelTimeProvider.Mode;
import com.fukuokatabi.travel.TravelTimeProvider.Point;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class EstimatedTravelTimeProviderTest {

    private final EstimatedTravelTimeProvider p = new EstimatedTravelTimeProvider();
    private final Point hakata = new Point(33.5897, 130.4207);
    private final Point dazaifu = new Point(33.5215, 130.5349);
    private final Point kushida = new Point(33.5930, 130.4106);

    @Test
    void 博多駅から太宰府は30分から1時間ほど() {
        var leg = p.between(hakata, dazaifu, Mode.TRANSIT);
        assertThat(leg.minutes()).isBetween(30, 60);
        assertThat(leg.minutes() % 5).isZero();
    }

    @Test
    void 近い場所は徒歩の時間になる() {
        var leg = p.between(hakata, kushida, Mode.TRANSIT);
        assertThat(leg.km()).isLessThan(1.5);
        assertThat(leg.minutes()).isLessThanOrEqualTo(25);
    }
}
