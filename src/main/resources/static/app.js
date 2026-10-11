"use strict";

const app = document.getElementById("app");
let QUESTIONS = [];
let SPOTS = [];
let ans = {};
let mustVisit = [];
const MUST_MAX = 3;
let extra = { startDate: "", freeText: "" };
let step = 0;
let maps = [];

const esc = (s) => String(s ?? "").replace(/[&<>"']/g, (c) => ({ "&": "&amp;", "<": "&lt;", ">": "&gt;", '"': "&quot;", "'": "&#39;" }[c]));

const SAMPLE = {
  answers: { days: 2, who: "friends", main: ["food", "history"], eat: ["motsu", "ramen", "yatai"], pace: "mid", move: "train", night: "yatai", range: "near" },
  mustVisit: ["dazaifu-tenmangu"],
  freeText: "雨なら屋内多めで。",
};

async function init() {
  try {
    const [qr, sr] = await Promise.all([fetch("api/questions"), fetch("api/spots")]);
    if (!qr.ok || !sr.ok) throw new Error(qr.status);
    QUESTIONS = await qr.json();
    SPOTS = (await sr.json()).filter((s) => s.category === "SIGHT");
    intro();
  } catch (e) {
    app.innerHTML = `<section class="card"><p class="error">質問を読み込めませんでした。時間をおいて再読み込みしてください。</p></section>`;
  }
}

function intro() {
  app.innerHTML = `<section class="card"><div class="qnum">はじめに</div><h2 class="q">所要時間は1分ほど</h2>
  <p class="hint">まず「ここだけは絶対に行きたい場所」を選び、日数・同行者・食べたいもの・夜の過ごし方などを聞いて、行き先と時間割を組み立てます。</p>
  <div class="nav"><button class="btn ghost" id="sample">サンプル結果を見る</button><button class="btn" id="start">質問をはじめる</button></div></section>`;
  document.getElementById("start").onclick = () => { step = 0; renderQ(); };
  document.getElementById("sample").onclick = () => requestPlan({ ...SAMPLE, startDate: nextSaturday() });
}

// step 0: 絶対に行きたい場所 / 1..N: 質問 / N+1: 日付と自由記述
const total = () => QUESTIONS.length + 2;

function progress() {
  return `<div class="progress">${Array.from({ length: total() }, (_, i) => `<span class="${i <= step ? "on" : ""}"></span>`).join("")}</div>
  <div class="qnum">質問 ${step + 1} / ${total()}</div>`;
}

const FAR_GROUPS = [
  [0, "福岡市内", "博多・天神・ベイエリア"],
  [1, "近郊", "電車や車で30分〜1時間"],
  [2, "遠出", "片道1時間以上"],
];

function renderMust() {
  const selected = mustVisit.map((id) => SPOTS.find((s) => s.id === id)).filter(Boolean);
  app.innerHTML = `<section class="card" aria-live="polite">${progress()}
   <h2 class="q">ここだけは絶対に行きたい場所はありますか？</h2>
   <p class="hint">選んだ場所は必ずプランに入れ、その日はその周辺をめぐるように組みます。${MUST_MAX}か所まで選べます。</p>
   ${FAR_GROUPS.map(([lv, label, sub]) => {
     const list = SPOTS.filter((s) => s.farLevel === lv);
     if (!list.length) return "";
     return `<div class="spotgroup"><h4>${label}<small>${sub}</small></h4><div class="opts mini">${list.map((s) =>
       `<button class="opt" id="must-${esc(s.id)}" data-id="${esc(s.id)}" aria-pressed="${mustVisit.includes(s.id)}"><b>${esc(s.name)}</b><small>${esc(s.area)}</small></button>`).join("")}</div></div>`;
   }).join("")}
   <p class="picked">${selected.length ? `選択中：${selected.map((s) => esc(s.name)).join("、")}` : "選ばなくても大丈夫です。好みからおまかせで組みます。"}</p>
   <div class="nav"><button class="btn ghost" id="back">戻る</button>
   <button class="btn" id="next">${selected.length ? "この場所で決める" : "特になし・次へ"}</button></div></section>`;
  app.querySelectorAll("[data-id]").forEach((b) => (b.onclick = () => {
    const id = b.dataset.id;
    if (mustVisit.includes(id)) mustVisit = mustVisit.filter((x) => x !== id);
    else { if (mustVisit.length >= MUST_MAX) mustVisit.shift(); mustVisit.push(id); }
    const y = scrollY;
    renderMust();
    scrollTo(0, y);
    document.getElementById("must-" + id)?.focus();
  }));
  document.getElementById("back").onclick = intro;
  document.getElementById("next").onclick = () => { step++; renderQ(); };
}

function renderQ() {
  if (step === 0) return renderMust();
  if (step > QUESTIONS.length) return renderFinish();
  const Q = QUESTIONS[step - 1];
  const multi = Q.multi > 0;
  const cur = ans[Q.id] ?? (multi ? [] : null);
  app.innerHTML = `<section class="card" aria-live="polite">${progress()}
   <h2 class="q">${esc(Q.text)}</h2><p class="hint">${esc(Q.hint || "ひとつ選んでください")}</p>
   <div class="opts">${Q.options.map((o) => `<button class="opt" id="opt-${Q.id}-${o.value}" data-v="${o.value}" aria-pressed="${multi ? cur.includes(o.value) : cur === o.value}"><b>${esc(o.label)}</b><small>${esc(o.sub)}</small></button>`).join("")}</div>
   <div class="nav"><button class="btn ghost" id="back">戻る</button>
   ${multi ? `<button class="btn" id="next" ${Q.id === "main" && !cur.length ? "disabled" : ""}>次へ</button>` : ""}</div></section>`;
  app.querySelectorAll(".opt").forEach((b) => (b.onclick = () => {
    const v = b.dataset.v;
    if (multi) {
      let a = [...(ans[Q.id] || [])];
      if (a.includes(v)) a = a.filter((x) => x !== v);
      else { if (a.length >= Q.multi) a.shift(); a.push(v); }
      ans[Q.id] = a;
      renderQ();
    } else {
      ans[Q.id] = v;
      step++;
      renderQ();
    }
  }));
  document.getElementById("back").onclick = () => { step--; renderQ(); };
  const n = document.getElementById("next");
  if (n) n.onclick = () => { step++; renderQ(); };
}

function renderFinish() {
  if (!extra.startDate) extra.startDate = nextSaturday();
  app.innerHTML = `<section class="card">${progress()}
   <h2 class="q">最後に、日付とご希望を</h2><p class="hint">休館日を避けたり、季節の見どころを入れたりするのに使います。</p>
   <div class="field"><label for="startDate">出発日</label><input type="date" id="startDate" value="${esc(extra.startDate)}"></div>
   <div class="field"><label for="freeText">その他の希望（任意）</label>
     <textarea id="freeText" maxlength="500" placeholder="例：子どもが歩き疲れないように。雨なら屋内多めで。屋台は苦手。">${esc(extra.freeText)}</textarea>
     <small>行きたい場所・苦手なもの・体力面など、自由に書いてください（500文字まで）。</small></div>
   <div class="nav"><button class="btn ghost" id="back">戻る</button><button class="btn" id="make">プランを作る</button></div></section>`;
  const save = () => {
    extra.startDate = document.getElementById("startDate").value;
    extra.freeText = document.getElementById("freeText").value;
  };
  document.getElementById("back").onclick = () => { save(); step--; renderQ(); };
  document.getElementById("make").onclick = () => {
    save();
    requestPlan({
      answers: { ...ans, days: Number(ans.days), eat: ans.eat || [] },
      freeText: extra.freeText,
      startDate: extra.startDate || null,
      mustVisit,
    });
  };
}

async function requestPlan(body) {
  app.innerHTML = `<section class="card"><div class="loading">プランを組み立てています</div></section>`;
  try {
    const res = await fetch("api/plans", { method: "POST", headers: { "Content-Type": "application/json" }, body: JSON.stringify(body) });
    const data = await res.json();
    if (!res.ok) throw new Error(data.detail || data.title || res.status);
    renderResult(data);
  } catch (e) {
    app.innerHTML = `<section class="card"><p class="error">プランを作れませんでした。</p><p class="hint">${esc(e.message)}</p>
      <div class="nav"><button class="btn" id="retry">質問に戻る</button></div></section>`;
    document.getElementById("retry").onclick = () => { step = 0; renderQ(); };
  }
}

function renderResult(p) {
  maps.forEach((m) => m.remove());
  maps = [];
  let html = `<section class="card"><div class="type"><div class="stamp">${esc(p.type.mark)}</div><div><h2>${esc(p.type.title)}</h2><p>${esc(p.type.subtitle)}</p></div></div>
    <div class="chips">${p.chips.map((c) => `<span class="chip">${esc(c)}</span>`).join("")}</div></section>`;

  if (p.interpretation && p.interpretation.notes.length) {
    html += `<section class="card"><h3 style="font-family:var(--display);margin:0 0 8px">ご希望をこう受け取りました</h3>
      <ul class="heard">${p.interpretation.notes.map((n) => `<li>${esc(n)}</li>`).join("")}</ul></section>`;
  }

  p.days.forEach((day, i) => {
    const title = p.days.length === 1 ? "日帰りプラン" : `${day.dayNumber}日目`;
    html += `<section class="card day"><h3>${title}<span>${esc(day.date)}・${esc(day.headline)}</span></h3>
      <div class="map" id="map-${i}" role="img" aria-label="${title}の地図"></div>
      <ol class="tl">${day.items.map(itemHtml).join("")}</ol></section>`;
  });

  html += `<section class="card"><h3 style="font-family:var(--display);margin:0 0 8px">旅のヒント</h3>
    <ul class="tips">${p.tips.map((t) => `<li>${esc(t)}</li>`).join("")}</ul>
    <div class="nav"><button class="btn ghost" id="edit">答えを変える</button><button class="btn" id="restart">最初からやり直す</button></div></section>`;
  app.innerHTML = html;

  p.days.forEach((day, i) => drawMap(`map-${i}`, day.items));
  document.getElementById("restart").onclick = () => { ans = {}; mustVisit = []; extra = { startDate: "", freeText: "" }; step = 0; renderQ(); scrollTo(0, 0); };
  document.getElementById("edit").onclick = () => { step = 0; renderQ(); scrollTo(0, 0); };
  scrollTo({ top: app.offsetTop - 20, behavior: "smooth" });
}

function itemHtml(r) {
  if (r.kind === "move") return `<li class="move">↓ ${esc(r.name)}</li>`;
  const cls = r.kind === "night" ? "night" : r.why === "絶対に行きたい場所" ? "must" : "";
  return `<li class="${cls}"><time>${esc(r.time)}</time><div class="body">
    <div class="name">${esc(r.name)}${r.area ? `<span class="area">${esc(r.area)}</span>` : ""}</div>
    <p class="note">${esc(r.note)}</p>
    ${r.why ? `<span class="why">${esc(r.why)}</span>` : ""}${r.warning ? `<span class="warn">${esc(r.warning)}</span>` : ""}</div></li>`;
}

function drawMap(id, items) {
  const el = document.getElementById(id);
  const pts = items.filter((r) => r.lat != null);
  if (!window.L || !pts.length) { el.hidden = true; return; }
  const map = L.map(el, { scrollWheelZoom: false, attributionControl: false });
  L.tileLayer("https://{s}.tile.openstreetmap.org/{z}/{x}/{y}.png", { maxZoom: 18 }).addTo(map);
  const latlngs = pts.map((r) => [r.lat, r.lng]);
  pts.forEach((r, k) => {
    const must = r.why === "絶対に行きたい場所";
    L.circleMarker([r.lat, r.lng], { radius: must ? 11 : 8, weight: 2, color: r.kind === "night" || must ? "#d9822b" : "#1f3f73", fillOpacity: 0.85 })
      .bindTooltip(`${k + 1}. ${r.name}`).addTo(map);
  });
  if (latlngs.length > 1) L.polyline(latlngs, { color: "#1f3f73", weight: 2, dashArray: "4 6" }).addTo(map);
  map.fitBounds(latlngs, { padding: [24, 24], maxZoom: 14 });
  maps.push(map);
}

function nextSaturday() {
  const d = new Date();
  d.setDate(d.getDate() + ((6 - d.getDay() + 7) % 7 || 7));
  const p = (n) => String(n).padStart(2, "0");
  return `${d.getFullYear()}-${p(d.getMonth() + 1)}-${p(d.getDate())}`;
}

init();
