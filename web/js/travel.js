// 直線距離からの所要時間の推定（Java 版 EstimatedTravelTimeProvider と同じ計算）。
// 道のりは直線の約1.3倍とし、乗り換え・待ち時間を固定で足す。

const ROAD_FACTOR = 1.3;
const WALK_KMH = 4.5;

export function haversineKm(a, b) {
  const r = 6371.0;
  const rad = (d) => (d * Math.PI) / 180;
  const dLat = rad(b.lat - a.lat);
  const dLng = rad(b.lng - a.lng);
  const h = Math.sin(dLat / 2) ** 2 + Math.cos(rad(a.lat)) * Math.cos(rad(b.lat)) * Math.sin(dLng / 2) ** 2;
  return 2 * r * Math.asin(Math.sqrt(h));
}

/** @param mode "TRANSIT" | "DRIVE" */
export function between(from, to, mode) {
  const km = haversineKm(from, to) * ROAD_FACTOR;
  let minutes;
  if (km < 1.0) {
    minutes = (km / WALK_KMH) * 60; // 1km 未満は徒歩
  } else if (mode === "DRIVE") {
    minutes = 5 + (km / 30.0) * 60; // 駐車などに5分、市街地は平均30km/h
  } else {
    minutes = 12 + (km / (km > 15 ? 45.0 : 22.0)) * 60; // 駅までと待ち時間で12分。遠距離は電車の速さ
  }
  return { minutes: Math.ceil(minutes / 5.0) * 5, km: Math.round(km * 10) / 10, mode, source: "estimate" };
}
