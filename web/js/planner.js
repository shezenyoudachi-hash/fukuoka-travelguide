// プラン作成（Java 版 PlanService と同じ考え方）。
// 回答・自由記述・旅行日・絶対に行きたい場所から、日ごとの時間割を組み立てる。

import { interpret } from "./interpreter.js";
import { between, haversineKm } from "./travel.js";
import { label } from "./questions.js";

/** 宿泊・出発の起点として博多駅を仮定する */
const BASE = { lat: 33.5897, lng: 130.4207 };
const BASE_NAME = "博多駅";
/** 絶対に行きたい場所の日に、周辺として組み込む距離 */
export const NEARBY_KM = 12.0;
/** 絶対に行きたい場所どうしを同じ日にまとめる距離 */
export const SAME_DAY_KM = 15.0;

const LUNCH_FROM = 11 * 60;
const DINNER_FROM = 18 * 60;
const MUST = "絶対に行きたい場所";
const SLOT_ORDER = { AM: 0, ANY: 1, PM: 2 };
const DOW = ["SUN", "MON", "TUE", "WED", "THU", "FRI", "SAT"];
const DOW_JA = ["日", "月", "火", "水", "木", "金", "土"];

// ---- 日付（"YYYY-MM-DD" を UTC で扱い、端末のタイムゾーンでずれないようにする） ----

const parseDate = (s) => {
  const [y, m, d] = s.split("-").map(Number);
  return new Date(Date.UTC(y, m - 1, d));
};
const plusDays = (date, n) => new Date(date.getTime() + n * 86400000);
const isoDate = (date) => date.toISOString().slice(0, 10);
const monthOf = (date) => date.getUTCMonth() + 1;
const dowLabel = (date) => DOW_JA[date.getUTCDay()];

// ---- スポットの性質 ----

const tag = (s, key) => s.tags[key] ?? 0;
const isCityCenter = (s) => s.farLevel === 0;
const point = (s) => ({ lat: s.latitude, lng: s.longitude });
const km = (a, b) => haversineKm(a, b);
const toMinutes = (hhmm) => {
  if (!hhmm) return null;
  const [h, m] = hhmm.split(":").map(Number);
  return h * 60 + m;
};
const closedDays = (s) => (s.closedDays ? s.closedDays.split(",").map((d) => d.trim().toUpperCase().slice(0, 3)) : []);
const bestMonths = (s) => (s.bestMonths ? s.bestMonths.split(",").map((m) => Number(m.trim())) : []);
export const openOn = (s, date) => !closedDays(s).includes(DOW[date.getUTCDay()]);

// ---- 時刻（その日の0時からの分で持つ） ----

const fmt = (t) => `${Math.floor(t / 60)}:${String(t % 60).padStart(2, "0")}`;
const roundUp = (t) => (t % 15 === 0 ? t : t + (15 - (t % 15)));

/**
 * @param req   { answers, freeText, startDate, mustVisit }
 * @param spots parseSeed() で作ったスポット一覧
 * @param today 出発日の指定がないときの基準日（テスト用）
 */
export function makePlan(req, spots, today = new Date()) {
  const a = { ...req.answers, days: Number(req.answers.days), eat: req.answers.eat ?? [] };
  const mustVisit = (req.mustVisit ?? []).slice(0, 3);
  const inp = interpret(req.freeText, spots);
  const start = req.startDate ? parseDate(req.startDate) : plusDays(parseDate(isoDate(today)), 7);
  const pace = inp.paceOverride ?? a.pace;
  const mode = a.move === "car" ? "DRIVE" : "TRANSIT";

  const anchors = anchorsOf(mustVisit, inp, spots);
  const anchorIds = new Set(anchors.map((s) => s.id));

  // 絶対に行きたい場所の周辺は、行動範囲の設定に関係なく候補にする
  const nearbyPool = spots
    .filter((s) => s.category === "SIGHT" && !anchorIds.has(s.id) && reachable(s, a, inp))
    .map((s) => ({ spot: s, score: score(s, a, inp, start) }))
    .sort((x, y) => y.score - x.score);
  const pool = nearbyPool.filter((sc) => inRange(sc.spot, a));

  const dayPicks = pickPerDay(pool, nearbyPool, anchors, a, pace, start);
  if (dayPicks.length) {
    const why = dayPicks[0].why;
    for (const id of inp.mustInclude) {
      if (!mustVisit.includes(id) && why.has(id)) why.set(id, "自由記述でリクエスト");
    }
  }

  const days = dayPicks.map((pick, i) => buildDay(i, pick, plusDays(start, i), a, inp, pace, mode, spots));
  return {
    type: tripType(a),
    chips: chips(a, pace),
    days,
    tips: tips(a, dayPicks),
    interpretation: { source: inp.source, notes: inp.notes },
  };
}

// ---- 候補の絞り込みと点数 ----

/**
 * 「絶対に行きたい場所」。画面で選んだものを先に、自由記述で名指ししたものを後に並べる。
 * 範囲・移動手段・自由記述での除外に関係なく、必ずプランに入れる。
 */
function anchorsOf(mustVisit, inp, spots) {
  const byId = new Map(spots.filter((s) => s.category === "SIGHT").map((s) => [s.id, s]));
  const ids = [...new Set([...mustVisit, ...inp.mustInclude])];
  return ids.map((id) => byId.get(id)).filter(Boolean);
}

/** 自由記述で外されておらず、選んだ移動手段で行けるか */
const reachable = (s, a, inp) => !inp.exclude.includes(s.id) && (a.move === "car" || s.transitOk);

/** 「市内中心部の外にも行きますか？」の答えの範囲内か */
function inRange(s, a) {
  if (a.range === "city") return s.farLevel === 0;
  if (a.range === "near") return s.farLevel <= 1;
  return true;
}

function score(s, a, inp, start) {
  let sc = 0;
  a.main.forEach((m, i) => { sc += tag(s, m) * (i === 0 ? 3 : 2); });
  for (const e of a.eat) sc += tag(s, e) * 2;
  sc += tag(s, a.who) * 1.5;
  if (a.pace === "slow") sc += tag(s, "slow");
  if (a.who === "family" && s.durationMinutes > 180) sc -= 1;
  for (const [k, v] of Object.entries(inp.tagBoosts)) sc += tag(s, k) * v;
  if (inp.preferIndoor) sc += s.indoor ? 2 : -2;

  // 旅行期間に見頃なら加点
  const best = bestMonths(s);
  if (best.length && (best.includes(monthOf(start)) || best.includes(monthOf(plusDays(start, Math.max(0, a.days - 1)))))) {
    sc += 2;
  }
  return sc;
}

// ---- 日ごとの割り当て ----

const sortByVisitOrder = (list) =>
  list.sort((x, y) =>
    (isCityCenter(x) - isCityCenter(y))
    || (SLOT_ORDER[x.slot] - SLOT_ORDER[y.slot])
    || ((toMinutes(x.closeTime) ?? Infinity) - (toMinutes(y.closeTime) ?? Infinity)));

function pickPerDay(pool, nearbyPool, anchors, a, pace, start) {
  const days = a.days;
  const per = pace === "slow" ? 2 : pace === "fast" ? 4 : 3;
  const maxMinutes = per <= 2 ? 360 : per === 3 ? 450 : 540;
  const car = a.move === "car";

  const picks = Array.from({ length: days }, () => []);
  const dayAnchors = Array.from({ length: days }, () => []);
  const why = new Map();
  const warnings = new Map();
  const used = new Set();
  const minutesOf = (list) => list.reduce((n, s) => n + s.durationMinutes, 0);

  // 1. 絶対に行きたい場所を、近いものどうしでまとめて日に割り当てる
  for (const group of groupAnchors(anchors, days, maxMinutes)) {
    const day = chooseDay(group, dayAnchors, start);
    const date = plusDays(start, day);
    const allOpen = group.every((s) => openOn(s, date));
    for (const s of group) {
      dayAnchors[day].push(s);
      picks[day].push(s);
      used.add(s.id);
      why.set(s.id, MUST);
      if (!allOpen && !openOn(s, date)) {
        warnings.set(s.id, `${dowLabel(date)}曜は休館日の可能性があります。公式情報で確認を`);
      } else if (!car && !s.transitOk) {
        warnings.set(s.id, "車がないと行きにくい場所です。バスやタクシーを事前に確認");
      }
    }
  }

  // 2. 絶対に行きたい場所がある日は、その周辺のスポットで埋める
  for (let d = 0; d < days; d++) {
    const group = dayAnchors[d];
    if (!group.length) continue;
    const date = plusDays(start, d);
    const center = centroid(group);
    const centerName = group[0].name;
    const dayList = picks[d];
    let minutes = minutesOf(dayList);
    const nearby = nearbyPool
      .filter((sc) => !used.has(sc.spot.id) && openOn(sc.spot, date) && km(center, point(sc.spot)) <= NEARBY_KM)
      .map((sc) => ({ ...sc, adjusted: sc.score - 0.3 * km(center, point(sc.spot)) }))
      .sort((x, y) => y.adjusted - x.adjusted);
    for (const sc of nearby) {
      if (dayList.length >= per) break;
      if (minutes + sc.spot.durationMinutes > maxMinutes) continue;
      dayList.push(sc.spot);
      used.add(sc.spot.id);
      minutes += sc.spot.durationMinutes;
      why.set(`near:${sc.spot.id}`, `${centerName}の近く`);
    }
  }

  // 3. 残りの日は、好みに合う郊外エリアか市内中心部で組む
  const anchorZones = new Set(anchors.map((s) => s.zone));
  const excursionZones = [...new Set(pool
    .filter((sc) => !isCityCenter(sc.spot) && sc.score > 0)
    .map((sc) => sc.spot.zone)
    .filter((z) => !anchorZones.has(z)))];

  for (let d = 0; d < days; d++) {
    if (dayAnchors[d].length) continue;
    const date = plusDays(start, d);
    // 複数日の旅の最終日は、帰りやすいよう市内中心部にする
    const lastOfMulti = d === days - 1 && days > 1;
    let zone = null;
    if (!lastOfMulti) {
      while (zone === null && excursionZones.length) {
        const z = excursionZones.shift();
        const best = pool.find((sc) => sc.spot.zone === z && !used.has(sc.spot.id) && openOn(sc.spot, date));
        if (best && best.score >= 4) zone = z;
      }
    }

    const dayList = picks[d];
    if (zone !== null) {
      pool
        .filter((sc) => sc.spot.zone === zone && !used.has(sc.spot.id) && openOn(sc.spot, date))
        .slice(0, Math.max(1, per - 1))
        .forEach((sc) => dayList.push(sc.spot));
    }
    let minutes = minutesOf(dayList);
    for (const { spot: s } of pool) {
      if (dayList.length >= per) break;
      if (!isCityCenter(s) || used.has(s.id) || dayList.includes(s) || !openOn(s, date)) continue;
      if (minutes + s.durationMinutes > maxMinutes) continue;
      dayList.push(s);
      minutes += s.durationMinutes;
    }
    dayList.forEach((s) => used.add(s.id));
  }

  // 回る順: 郊外が先、次に朝向き→どこでも→午後向き、同じなら早く閉まる順
  return picks.map((list, d) => ({ spots: sortByVisitOrder(list), anchors: dayAnchors[d], why, warnings }));
}

/**
 * 絶対に行きたい場所を、近いものどうしで1日分ずつにまとめる。
 * まとまりが日数より多ければ、いちばん近いまとまりどうしを合わせて日数に収める。
 */
export function groupAnchors(anchors, days, maxMinutes) {
  const groups = [];
  const minutesOf = (g) => g.reduce((n, s) => n + s.durationMinutes, 0);
  for (const s of anchors) {
    const fit = groups.find((g) => km(centroid(g), point(s)) <= SAME_DAY_KM
      && minutesOf(g) + s.durationMinutes <= maxMinutes + 60);
    if (fit) fit.push(s);
    else groups.push([s]);
  }
  while (groups.length > days) {
    let bi = 0, bj = 1, best = Infinity;
    for (let i = 0; i < groups.length; i++) {
      for (let j = i + 1; j < groups.length; j++) {
        const dist = km(centroid(groups[i]), centroid(groups[j]));
        if (dist < best) { best = dist; bi = i; bj = j; }
      }
    }
    groups[bi].push(...groups.splice(bj, 1)[0]);
  }
  // 遠いまとまりから先に日を決める（最終日を遠出の日にしないため）
  const farOf = (g) => Math.max(0, ...g.map((s) => s.farLevel));
  return groups.sort((x, y) => farOf(y) - farOf(x));
}

/** まだ空いている日のうち、全員が開いている日を選ぶ。遠出は最終日を避ける */
function chooseDay(group, dayAnchors, start) {
  const days = dayAnchors.length;
  const far = group.some((s) => !isCityCenter(s));
  const order = [...Array(days).keys()];
  // 市内の場所は最終日から埋め、初日・中日を遠出に残す
  if (!far && days > 1) order.reverse();
  for (const d of order) {
    if (dayAnchors[d].length) continue;
    const date = plusDays(start, d);
    if (group.every((s) => openOn(s, date))) return d;
  }
  return order.find((d) => !dayAnchors[d].length) ?? order[0];
}

// ---- 時間割 ----

function buildDay(index, pick, date, a, inp, pace, mode, all) {
  const picks = pick.spots;
  const items = [];
  let t = pace === "slow" ? 10 * 60 : 9 * 60;
  if (index === 0 && a.days > 1) t = 10 * 60 + 30; // 初日は到着後から

  const eats = a.eat;
  let lunchDone = false;
  let here = BASE;

  picks.forEach((s, k) => {
    const to = point(s);
    const leg = between(here, to, mode);
    if (leg.minutes >= 10) items.push(moveItem((k === 0 ? `${BASE_NAME}周辺から` : "") + moveText(leg, mode), leg.minutes));
    t += leg.minutes;

    if (!lunchDone && t >= LUNCH_FROM) {
      items.push(lunch(t, eats, index));
      t += 60;
      lunchDone = true;
    }
    const open = toMinutes(s.openTime);
    if (open !== null && t < open) t = open;

    let warning = pick.warnings.get(s.id) ?? null;
    const end = t + s.durationMinutes;
    const close = toMinutes(s.closeTime);
    if (warning === null && close !== null && end > close) {
      warning = `閉館（${s.closeTime}ごろ）が近いので早めに`;
    }
    items.push(item("spot", t, s, noteWithSeason(s, date), why(s, a, inp, pick), warning));
    t = roundUp(end + 15);
    here = to;
  });
  if (!lunchDone) {
    items.push(lunch(t, eats, index));
    t += 60;
  }

  // 夜ごはん
  const dinnerPrefs = ["motsu", "mizutaki", "seafood"].filter((e) => eats.includes(e));
  const yataiAsDinner = a.night === "yatai" && index === 0 && !dinnerPrefs.length;
  if (!yataiAsDinner) {
    const key = dinnerPrefs.length ? dinnerPrefs[index % dinnerPrefs.length] : null;
    const dinner = findByTag(all, "DINNER", key ?? "food", inp);
    if (dinner) {
      const leg = between(here, point(dinner), mode);
      if (leg.minutes >= 10) items.push(moveItem(moveText(leg, mode), leg.minutes));
      t = Math.max(t + leg.minutes, DINNER_FROM);
      items.push({
        ...item("dinner", t, dinner, noteWithSeason(dinner, date), key ? `「${label("eat", key)}」を選択` : null, null),
        name: `夜ごはん：${dinner.name}`,
      });
      t = roundUp(t + dinner.durationMinutes);
      here = point(dinner);
    }
  } else {
    t = Math.max(t, DINNER_FROM);
  }

  // 夜の過ごし方
  const nightTag = a.night === "rest" ? (eats.includes("yatai") ? "yatai" : null) : a.night;
  if (nightTag) {
    const night = findByTag(all, "NIGHT", nightTag, inp);
    if (night) {
      const leg = between(here, point(night), mode);
      if (leg.minutes >= 10) items.push(moveItem(moveText(leg, mode), leg.minutes));
      t += leg.minutes;
      const reason = a.night === "rest" ? "「屋台」を選択" : `「${label("night", a.night)}」から`;
      items.push(item("night", t, night, night.note, reason, null));
    }
  }

  let headline;
  if (pick.anchors.length) {
    headline = `${pick.anchors[0].name}${pick.anchors.length > 1 ? "ほか" : ""}を中心にめぐる日`;
  } else {
    const excursion = picks.find((s) => !isCityCenter(s));
    headline = excursion ? `${excursion.area}へ足をのばす日` : "市内中心部をめぐる日";
  }
  return { dayNumber: index + 1, date: `${isoDate(date)}（${dowLabel(date)}）`, headline, items };
}

const item = (kind, t, s, note, whyText, warning) => ({
  kind, time: fmt(t), name: s.name, area: s.area, note, why: whyText, warning,
  lat: s.latitude, lng: s.longitude, spotId: s.id, minutes: s.durationMinutes,
});

const moveItem = (text, minutes) => ({ kind: "move", name: text, minutes });

function lunch(t, eats, dayIndex) {
  const ramen = eats.includes("ramen") && dayIndex === 0;
  return {
    kind: "lunch", time: fmt(t),
    name: ramen ? "昼ごはん：博多豚骨ラーメン" : "昼ごはん", area: null,
    note: ramen ? "昼のうちに1杯。屋台と食べ比べるのも楽しい。" : "近くの定食屋やうどん店で。博多のごぼう天うどんもおすすめ。",
    why: ramen ? "「豚骨ラーメン」を選択" : null, warning: null, minutes: 60,
  };
}

/** 指定タグの店・過ごし方を選ぶ。自由記述で外された・苦手とされたものは避ける */
function findByTag(all, category, key, inp) {
  if ((inp.tagBoosts[key] ?? 0) < 0) return null;
  let best = null;
  for (const s of all) {
    if (s.category !== category || tag(s, key) <= 0 || inp.exclude.includes(s.id)) continue;
    if (!best || tag(s, key) > tag(best, key)) best = s;
  }
  if (best) return best;
  return category === "DINNER" ? all.find((s) => s.id === "dinner-izakaya") ?? null : null;
}

function why(s, a, inp, pick) {
  const fixed = pick.why.get(s.id);
  if (fixed) return fixed;
  for (const m of a.main) if (tag(s, m) > 0) return `「${label("main", m)}」を選んだので`;
  for (const e of a.eat) if (tag(s, e) > 0) return `「${label("eat", e)}」を選んだので`;
  for (const [k, v] of Object.entries(inp.tagBoosts)) if (v > 0 && tag(s, k) > 0) return "自由記述から";
  if (tag(s, a.who) > 0) return `${label("who", a.who)}向き`;
  return pick.why.get(`near:${s.id}`) ?? null;
}

function noteWithSeason(s, date) {
  if (s.seasonNote && bestMonths(s).includes(monthOf(date))) return `${s.note}（${s.seasonNote}）`;
  return s.note;
}

function moveText(leg, mode) {
  const how = leg.km < 1.0 ? "徒歩" : mode === "DRIVE" ? "車" : "電車・バス";
  return `${how}で移動（目安約${leg.minutes}分）`;
}

// ---- まとめ ----

function tripType(a) {
  switch (a.main[0]) {
    case "history": return { mark: "史", title: "神社と歴史をたどる旅", subtitle: "千年の天神さまから城跡まで" };
    case "nature": return { mark: "海", title: "海と風を感じる旅", subtitle: "街から少し出て、玄界灘へ" };
    case "shop": return { mark: "街", title: "天神・博多の街歩き旅", subtitle: "歩いて回れるコンパクトさを満喫" };
    case "art": return { mark: "美", title: "アートと静けさの旅", subtitle: "美術館で過ごす贅沢な時間" };
    default: return { mark: "食", title: "食いしん坊の博多旅", subtitle: "昼も夜も、食でつなぐ" };
  }
}

function chips(a, pace) {
  return [
    label("days", a.days), label("who", a.who),
    ...a.main.map((m) => label("main", m)),
    label("pace", pace), label("move", a.move),
  ];
}

function tips(a, dayPicks) {
  const tips = [];
  const picked = dayPicks.flatMap((d) => d.spots);
  if (a.move === "train") {
    tips.push("市内の移動は地下鉄が中心。博多駅〜天神は約5分、福岡空港〜博多駅も地下鉄で約5分です。");
  } else {
    tips.push("天神・博多の中心部は駐車場代が高め。市内観光の日は車をホテルに置いて地下鉄で動くのがおすすめです。");
  }
  if (a.who === "family") {
    tips.push("子ども連れなら、昼食は混む前の11時台に。キャナルシティや海の中道は休憩場所も多くて安心です。");
  }
  if (a.eat.includes("yatai") || a.night === "yatai") {
    tips.push("屋台は天候で休むことがあります。雨の日は代わりに屋台風の居酒屋を候補に。");
  }
  if (picked.some((s) => s.zone === "itoshima") && a.move !== "car") {
    tips.push("糸島は電車＋バスでも行けますが本数が少なめ。時刻表を事前に確認しておきましょう。");
  }
  if (dayPicks.length && dayPicks[0].warnings.size) {
    tips.push("注意のついた「絶対に行きたい場所」は、休館日や交通手段を事前に確認してください。");
  }
  if (picked.some((s) => closedDays(s).length)) {
    tips.push("休館日を避けて組んでいますが、祝日や臨時休館で変わることがあります。");
  }
  if (tips.length === 1) tips.push("おみやげは博多駅でまとめて買うと荷物が少なくすみます。");
  return tips;
}

function centroid(group) {
  const avg = (f) => group.reduce((n, s) => n + f(s), 0) / group.length;
  return group.length ? { lat: avg((s) => s.latitude), lng: avg((s) => s.longitude) } : BASE;
}
