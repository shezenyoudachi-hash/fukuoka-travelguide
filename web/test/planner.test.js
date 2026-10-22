// Java 版のテスト（PlanApiTest など）と同じシナリオを、Web 版のプラン作成で確認する。
// 実行: node --test web/test/

import { test } from "node:test";
import assert from "node:assert/strict";
import { readFileSync } from "node:fs";
import { fileURLToPath } from "node:url";
import { parseSeed } from "../js/seed.js";
import { makePlan } from "../js/planner.js";
import { interpret } from "../js/interpreter.js";
import { between } from "../js/travel.js";
import { QUESTIONS } from "../js/questions.js";

const sql = readFileSync(fileURLToPath(new URL("../../src/main/resources/db/migration/V2__seed_spots.sql", import.meta.url)), "utf8");
const SPOTS = parseSeed(sql);

const SAMPLE = { days: 2, who: "friends", main: ["food", "history"], eat: ["motsu", "ramen", "yatai"], pace: "mid", move: "train", night: "yatai", range: "near" };
const ART_DAY = { days: 1, who: "solo", main: ["art"], eat: [], pace: "mid", move: "train", night: "rest", range: "city" };

const plan = (answers, opts = {}) => makePlan({ answers, ...opts }, SPOTS);
const ids = (day) => day.items.map((i) => i.spotId).filter(Boolean);
const allIds = (p) => p.days.flatMap(ids);
const itemOf = (p, id) => p.days.flatMap((d) => d.items).find((i) => i.spotId === id);

test("初期データから全スポットを読み込む", () => {
  assert.equal(SPOTS.length, 30);
  assert.equal(SPOTS.filter((s) => s.category === "SIGHT").length, 22);
  assert.deepEqual(SPOTS.find((s) => s.id === "kyuhaku").tags, { art: 3, history: 2 });
});

test("質問は8つ", () => {
  assert.equal(QUESTIONS.length, 8);
  assert.equal(QUESTIONS[0].id, "days");
});

test("回答の日数ぶんのプランを返す", () => {
  const p = plan(SAMPLE, { startDate: "2026-10-17" });
  assert.equal(p.days.length, 2);
  assert.ok(p.days[0].items.some((i) => i.kind === "spot"));
  assert.ok(p.days[0].items.some((i) => i.kind === "night"));
});

test("車がなければ公共交通で行きにくい場所を入れない", () => {
  const p = plan({ days: 3, who: "solo", main: ["history"], eat: [], pace: "fast", move: "train", night: "rest", range: "far" }, { startDate: "2026-10-17" });
  assert.ok(!allIds(p).includes("munakata-taisha"));
});

test("休館日のスポットはその日に入れない", () => {
  const answers = { ...ART_DAY, range: "near" };
  const mon = plan(answers, { startDate: "2026-10-12" });
  assert.ok(!allIds(mon).includes("kyuhaku"));
  assert.ok(!allIds(mon).includes("fukuoka-art"));
  const tue = plan(answers, { startDate: "2026-10-13" });
  assert.ok(allIds(tue).includes("kyuhaku"));
});

test("自由記述で名指しした場所は範囲外でも入れる", () => {
  const p = plan({ days: 1, who: "couple", main: ["shop"], eat: [], pace: "mid", move: "car", night: "view", range: "city" },
    { startDate: "2026-10-17", freeText: "宗像大社にはお参りしたい" });
  assert.ok(allIds(p).includes("munakata-taisha"));
  assert.ok(p.interpretation.notes.some((n) => n.includes("宗像大社")));
  assert.equal(itemOf(p, "munakata-taisha").why, "自由記述でリクエスト");
});

test("絶対に行きたい場所は範囲外でも入り、その周辺で組む", () => {
  const p = plan(ART_DAY, { startDate: "2026-10-13", mustVisit: ["dazaifu-tenmangu"] });
  const day = ids(p.days[0]);
  assert.ok(day.includes("dazaifu-tenmangu"));
  assert.ok(day.includes("kyuhaku"));
  assert.ok(!day.includes("fukuoka-art"));
  assert.match(p.days[0].headline, /太宰府天満宮/);
  assert.equal(itemOf(p, "dazaifu-tenmangu").why, "絶対に行きたい場所");
});

test("車がなくても絶対に行きたい場所は入れて注意を出す", () => {
  const p = plan(ART_DAY, { startDate: "2026-10-13", mustVisit: ["munakata-taisha"] });
  assert.match(itemOf(p, "munakata-taisha").warning, /車がないと/);
});

test("休館日しかなくても入れて注意を出す", () => {
  const p = plan(ART_DAY, { startDate: "2026-10-12", mustVisit: ["kyuhaku"] });
  assert.match(itemOf(p, "kyuhaku").warning, /月曜は休館日/);
});

test("休館日を避けて日を割り当てる", () => {
  const p = plan({ ...ART_DAY, days: 2 }, { startDate: "2026-10-12", mustVisit: ["kyuhaku"] });
  assert.ok(!ids(p.days[0]).includes("kyuhaku"));
  assert.ok(ids(p.days[1]).includes("kyuhaku"));
});

test("離れた場所は別の日に、近い場所は同じ日にまとめる", () => {
  const far = plan({ ...ART_DAY, days: 2 }, { startDate: "2026-10-13", mustVisit: ["mojiko", "yanagawa"] });
  const d0 = ids(far.days[0]);
  const d1 = ids(far.days[1]);
  assert.ok(d0.includes("mojiko") !== d0.includes("yanagawa"));
  assert.ok(d1.includes("mojiko") || d1.includes("yanagawa"));

  const near = plan({ ...ART_DAY, days: 2 }, { startDate: "2026-10-13", mustVisit: ["kushida-jinja", "tochoji"] });
  const must = near.days.flatMap((d) => d.items).filter((i) => i.why === "絶対に行きたい場所");
  assert.equal(must.length, 2);
  assert.ok(ids(near.days[1]).includes("kushida-jinja") && ids(near.days[1]).includes("tochoji"));
});

test("知らないIDは無視し、4つ目以降は使わない", () => {
  assert.doesNotThrow(() => plan(ART_DAY, { startDate: "2026-10-13", mustVisit: ["no-such-spot"] }));
  const p = plan({ ...ART_DAY, days: 3, range: "far" },
    { startDate: "2026-10-13", mustVisit: ["kushida-jinja", "tochoji", "canal-city", "mojiko"] });
  assert.ok(!p.days.flatMap((d) => d.items).some((i) => i.spotId === "mojiko" && i.why === "絶対に行きたい場所"));
});

test("時刻は朝から夜へ並ぶ", () => {
  const p = plan(SAMPLE, { startDate: "2026-10-17", mustVisit: ["dazaifu-tenmangu"] });
  for (const day of p.days) {
    const mins = day.items.filter((i) => i.time).map((i) => {
      const [h, m] = i.time.split(":").map(Number);
      return h * 60 + m;
    });
    assert.deepEqual(mins, [...mins].sort((x, y) => x - y), day.headline);
  }
});

// ---- 自由記述の解釈（RuleBasedInterpreterTest と同じ） ----

const sight = (id, name, area) => ({ id, category: "SIGHT", name, area, tags: {} });
const FEW = [sight("dazaifu-tenmangu", "太宰府天満宮", "太宰府"), sight("futamigaura", "糸島・二見ヶ浦の夫婦岩", "糸島"), sight("canal-city", "キャナルシティ博多", "博多")];

test("解釈: 空なら何もしない", () => {
  const r = interpret("  ", FEW);
  assert.deepEqual(r.tagBoosts, {});
  assert.deepEqual(r.notes, []);
});

test("解釈: 地名や名前の一部でもスポットを拾う", () => {
  assert.deepEqual(interpret("太宰府と二見ヶ浦に行きたい", FEW).mustInclude.sort(), ["dazaifu-tenmangu", "futamigaura"]);
});

test("解釈: 否定の文に出てきた場所は外す", () => {
  const r = interpret("キャナルシティは行かない。神社が好き", FEW);
  assert.deepEqual(r.exclude, ["canal-city"]);
  assert.equal(r.tagBoosts.history, 2);
});

test("解釈: 苦手なものは下げる", () => {
  assert.equal(interpret("屋台は苦手です", FEW).tagBoosts.yatai, -3);
});

test("解釈: 雨と体力の希望を読み取る", () => {
  const r = interpret("雨予報なので屋内多めで。子どもが歩き疲れないように", FEW);
  assert.equal(r.preferIndoor, true);
  assert.equal(r.paceOverride, "slow");
  assert.equal(r.tagBoosts.family, 2);
});

test("解釈: 海鮮は海の景色と取り違えない", () => {
  const r = interpret("海鮮が食べたい", FEW);
  assert.equal(r.tagBoosts.seafood, 2);
  assert.ok(!("nature" in r.tagBoosts));
});

// ---- 移動時間（EstimatedTravelTimeProviderTest と同じ） ----

test("移動: 博多駅から太宰府は30分から1時間ほど", () => {
  const leg = between({ lat: 33.5897, lng: 130.4207 }, { lat: 33.5215, lng: 130.5349 }, "TRANSIT");
  assert.ok(leg.minutes >= 30 && leg.minutes <= 60);
  assert.equal(leg.minutes % 5, 0);
});

test("移動: 近い場所は徒歩の時間になる", () => {
  const leg = between({ lat: 33.5897, lng: 130.4207 }, { lat: 33.593, lng: 130.4106 }, "TRANSIT");
  assert.ok(leg.km < 1.5);
  assert.ok(leg.minutes <= 25);
});
