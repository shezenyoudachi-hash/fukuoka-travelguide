// Flyway の初期データ SQL（V2__seed_spots.sql）からスポット一覧を作る。
// Java 版と Web 版で、スポットデータの元を1か所にするため。

const COLUMNS_RE = /INSERT INTO (\w+) \(([^)]*)\) VALUES/g;

/** 'a''b' のような SQL 文字列・NULL・TRUE/FALSE・数値を値に変換しながらタプルを読む */
function parseTuples(text) {
  const rows = [];
  let i = 0;
  while (i < text.length) {
    const ch = text[i];
    if (ch === ";") break;
    if (ch !== "(") { i++; continue; }
    i++;
    const row = [];
    let token = "";
    let inStr = false;
    let isStr = false;
    for (; i < text.length; i++) {
      const c = text[i];
      if (inStr) {
        if (c === "'" && text[i + 1] === "'") { token += "'"; i++; }
        else if (c === "'") inStr = false;
        else token += c;
        continue;
      }
      if (c === "'") { inStr = true; isStr = true; continue; }
      if (c === "," || c === ")") {
        row.push(toValue(token.trim(), isStr));
        token = "";
        isStr = false;
        if (c === ")") { i++; break; }
        continue;
      }
      token += c;
    }
    rows.push(row);
  }
  return rows;
}

function toValue(raw, isStr) {
  if (isStr) return raw;
  if (raw.toUpperCase() === "NULL") return null;
  if (raw.toUpperCase() === "TRUE") return true;
  if (raw.toUpperCase() === "FALSE") return false;
  return Number(raw);
}

const camel = (s) => s.replace(/_([a-z])/g, (_, c) => c.toUpperCase());

/** SQL 文字列からスポット（タグつき）の配列を返す */
export function parseSeed(sql) {
  const body = sql.replace(/--[^\n]*/g, "");
  const tables = {};
  let m;
  COLUMNS_RE.lastIndex = 0;
  while ((m = COLUMNS_RE.exec(body))) {
    const cols = m[2].split(",").map((c) => camel(c.trim()));
    const rows = parseTuples(body.slice(COLUMNS_RE.lastIndex));
    (tables[m[1]] ||= []).push(...rows.map((r) => Object.fromEntries(cols.map((c, k) => [c, r[k]]))));
  }
  const spots = (tables.spot || []).map((s) => ({ ...s, tags: {} }));
  const byId = new Map(spots.map((s) => [s.id, s]));
  for (const t of tables.spot_tag || []) {
    const s = byId.get(t.spotId);
    if (s) s.tags[t.tag] = t.weight;
  }
  return spots;
}
