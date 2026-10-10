-- 観光スポット・食事・夜の過ごし方をすべて spot として持つ
CREATE TABLE spot (
    id               VARCHAR(64)  PRIMARY KEY,
    category         VARCHAR(16)  NOT NULL,      -- SIGHT / DINNER / NIGHT
    name             VARCHAR(128) NOT NULL,
    zone             VARCHAR(32)  NOT NULL,      -- 同じ日にまとめる移動ブロック
    area             VARCHAR(64)  NOT NULL,      -- 画面に出す地名
    latitude         DOUBLE PRECISION NOT NULL,
    longitude        DOUBLE PRECISION NOT NULL,
    slot             VARCHAR(8)   NOT NULL,      -- AM / PM / ANY
    duration_minutes INTEGER      NOT NULL,
    far_level        INTEGER      NOT NULL DEFAULT 0,  -- 0:市内 1:近郊 2:遠出
    car_recommended  BOOLEAN      NOT NULL DEFAULT FALSE,
    transit_ok       BOOLEAN      NOT NULL DEFAULT TRUE,
    indoor           BOOLEAN      NOT NULL DEFAULT FALSE,
    open_time        TIME,
    close_time       TIME,
    closed_days      VARCHAR(64),                -- 例: MON,WED
    best_months      VARCHAR(64),                -- 例: 3,4
    season_note      VARCHAR(256),
    note             VARCHAR(512) NOT NULL,
    hours_verified   BOOLEAN      NOT NULL DEFAULT FALSE,  -- 営業情報を公式で確認済みか
    official_url     VARCHAR(256)
);

CREATE TABLE spot_tag (
    spot_id VARCHAR(64) NOT NULL REFERENCES spot(id),
    tag     VARCHAR(32) NOT NULL,
    weight  INTEGER     NOT NULL,
    PRIMARY KEY (spot_id, tag)
);
