// GitHub Pages に置くファイルを _site/ に作る。
// スポットは Flyway の初期データ SQL から JSON に変換する（データの元は1か所）。
// 実行: node web/build.js（リポジトリのルートで）

import { cpSync, mkdirSync, readFileSync, rmSync, writeFileSync } from "node:fs";
import { dirname, join } from "node:path";
import { fileURLToPath } from "node:url";
import { parseSeed } from "./js/seed.js";

const here = dirname(fileURLToPath(import.meta.url));
const root = join(here, "..");
const out = join(root, "_site");
const seed = join(root, "src/main/resources/db/migration/V2__seed_spots.sql");

rmSync(out, { recursive: true, force: true });
mkdirSync(join(out, "data"), { recursive: true });
for (const f of ["index.html", "styles.css", "app.js", "js"]) {
  cpSync(join(here, f), join(out, f), { recursive: true });
}

const spots = parseSeed(readFileSync(seed, "utf8"));
writeFileSync(join(out, "data/spots.json"), JSON.stringify(spots));
console.log(`_site/ を作成しました（スポット ${spots.length} 件）`);
