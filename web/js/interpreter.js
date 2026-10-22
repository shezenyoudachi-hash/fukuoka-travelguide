// 自由記述の解釈（Java 版 RuleBasedInterpreter と同じ規則）。外部サービスは使わない。

const rule = (re, note, boosts) => ({ re: new RegExp(re), note, boosts });

const RULES = [
  rule("子ども|子供|こども|キッズ|赤ちゃん|ベビー", "子ども連れで楽しめる場所を優先", { family: 2 }),
  rule("夕日|夕焼け|サンセット|海(?!鮮)|ビーチ|絶景", "海や景色のきれいな場所を優先", { nature: 2, couple: 1 }),
  rule("写真|映え|インスタ", "写真映えする場所を優先", { nature: 1, couple: 1, shop: 1 }),
  rule("神社|お寺|寺|歴史|御朱印", "神社・歴史スポットを優先", { history: 2 }),
  rule("美術|アート|博物館|展示", "美術館・博物館を優先", { art: 2 }),
  rule("買い物|ショッピング|お土産|雑貨|古着", "買い物・街歩きを優先", { shop: 2 }),
  rule("海鮮|魚|寿司|刺身|ごまさば", "海鮮を優先", { seafood: 2 }),
  rule("ラーメン|豚骨", "ラーメンを優先", { ramen: 2 }),
  rule("もつ鍋", "もつ鍋を優先", { motsu: 2 }),
  rule("水炊き", "水炊きを優先", { mizutaki: 2 }),
  rule("屋台", "屋台を優先", { yatai: 2 }),
  rule("自然|花|公園|散歩|島", "自然や公園を優先", { nature: 2 }),
  rule("カフェ|喫茶|スイーツ", "カフェのあるエリアを優先", { shop: 1, couple: 1 }),
];

const INDOOR = /雨|暑い|暑さ|寒い|屋内|室内/;
const SLOW = /ゆっくり|のんびり|疲れ|歩きたくない|歩くのが|足が|ベビーカー|車いす|車椅子|高齢/;
const FAST = /たくさん回|詰め込|欲張|全部/;
const NEGATION = /(以外|は不要|はいらない|行かない|行きたくない|苦手|なし|嫌い)/;
const NEGATIVE_NOTE = "苦手・不要とのことなので控えめにしました";

/** 広すぎて特定のスポットを指さない地名 */
const GENERIC = new Set(["博多", "天神", "福岡", "中洲", "中洲川端", "渡辺通", "赤坂", "博多・天神", "中道"]);

export const empty = () => ({
  source: "rule", tagBoosts: {}, mustInclude: [], exclude: [], preferIndoor: false, paceOverride: null, notes: [],
});

/**
 * 「太宰府天満宮」も「太宰府」も、「二見ヶ浦」も拾えるよう、
 * 名前・名前の区切り（・や「の」）ごとの語・エリア名・名前の先頭4文字で照合する。
 */
export function mentions(sentence, s) {
  if (sentence.includes(s.name)) return true;
  if (s.category !== "SIGHT") return false;
  const tokens = s.name.split(/[・の]/);
  tokens.push(s.area);
  if (s.name.length > 4) tokens.push(s.name.slice(0, 4));
  return tokens.some((t) => t.length >= 2 && !GENERIC.has(t) && sentence.includes(t));
}

export function interpret(freeText, spots) {
  if (!freeText || !freeText.trim()) return empty();
  const text = freeText.trim();
  const boosts = {};
  const notes = [];
  const must = [];
  const exclude = [];
  const addNote = (n) => { if (!notes.includes(n)) notes.push(n); };

  // 文ごとに見て、否定語を含む文のスポット名は除外として扱う
  for (const sentence of text.split(/[。．.!！?？\n、,]/)) {
    if (!sentence.trim()) continue;
    const negative = NEGATION.test(sentence);
    for (const s of spots) {
      if (!mentions(sentence, s)) continue;
      if (negative) {
        if (!exclude.includes(s.id)) { exclude.push(s.id); notes.push(`${s.name}は外しました`); }
      } else if (s.category === "SIGHT" && !must.includes(s.id)) {
        must.push(s.id);
        notes.push(`${s.name}を必ず入れました`);
      }
    }
    for (const r of RULES) {
      if (!r.re.test(sentence)) continue;
      if (negative) {
        // 「屋台は苦手」のような文は、その好みを下げる
        for (const k of Object.keys(r.boosts)) boosts[k] = -3;
        addNote(NEGATIVE_NOTE);
      } else {
        for (const [k, v] of Object.entries(r.boosts)) boosts[k] = Math.max(boosts[k] ?? -Infinity, v);
        addNote(r.note);
      }
    }
  }

  const preferIndoor = INDOOR.test(text);
  if (preferIndoor) notes.push("屋内で楽しめる場所を多めにしました");
  let paceOverride = null;
  if (SLOW.test(text)) {
    paceOverride = "slow";
    notes.push("移動と見学をゆったりめにしました");
  } else if (FAST.test(text)) {
    paceOverride = "fast";
    notes.push("回る場所を多めにしました");
  }
  return { source: "rule", tagBoosts: boosts, mustInclude: must, exclude, preferIndoor, paceOverride, notes };
}
