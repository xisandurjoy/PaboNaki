/* PaboNaki AI features: visual search, smart (semantic) search, recommendations.
   Talks only to the PaboNaki backend (no API keys here). Loaded after store.js and reuses its product cards,
   cart, wishlist and quick-view handlers. If the backend or AI is unavailable the storefront keeps working. */
(() => {
"use strict";
const P = window.PaboNakiStore;
if (!P) return;
const { S, card, esc, money, toast, openModal, closeModal } = P;
const API = (window.PABONAKI_CONFIG || {}).API_BASE || "/api/v1";
const $ = (s, r = document) => r.querySelector(s);
const ID_OK = /^[A-Za-z0-9-]{16,64}$/;

/* ---- anonymous browser id (guests); signed-in users are identified by their token on the server ---- */
const sid = (() => {
  try {
    let v = localStorage.getItem("pn_sid");
    if (!v || !ID_OK.test(v)) {
      v = (crypto.randomUUID ? crypto.randomUUID() : (Date.now().toString(36) + Math.random().toString(36).slice(2) + Math.random().toString(36).slice(2) + "0000000000000000").slice(0, 32));
      localStorage.setItem("pn_sid", v);
    }
    return v;
  } catch { return null; }
})();

async function call(path, opt = {}) {
  const headers = { ...(opt.headers || {}) };
  if (S.token) headers.Authorization = "Bearer " + S.token;
  let r;
  try { r = await fetch(API + path, { ...opt, headers }); }
  catch { const e = new Error("Can't reach the server. Please check your connection and try again."); e.network = true; throw e; }
  const text = await r.text(); let d = null;
  try { d = text ? JSON.parse(text) : null; } catch {}
  if (!r.ok) { const e = new Error((d && d.message) || `Something went wrong (${r.status}). Please try again.`); e.status = r.status; throw e; }
  return d;
}

function track(type, productId, query) {
  if (!sid && !S.token) return;
  const body = { type, sessionId: sid };
  if (productId) body.productId = String(productId);
  if (query) body.query = query;
  call("/activity", { method: "POST", headers: { "Content-Type": "application/json" }, body: JSON.stringify(body), keepalive: true }).catch(() => {});
}

/* ---- section containers (inserted above the shop grid; nothing existing is moved or removed) ---- */
function mk(id, cls) {
  let el = document.getElementById(id);
  if (!el) { el = document.createElement("section"); el.id = id; el.className = cls; el.hidden = true; const shop = $("#shop"); if (shop) shop.before(el); }
  return el;
}
const vis = () => mk("ai-visual", "container section ai-section");
const sem = () => mk("ai-semantic", "container section ai-section");
const reco = () => mk("ai-reco", "container section ai-section");

const inCatalogue = p => S.products.some(x => String(x.id) === String(p.id));
const grid = (items, cls = "pgrid") => `<div class="${cls}">${items.map(card).join("")}</div>`;
const skeleton = n => `<div class="pgrid">${'<div class="skeleton sk-card"></div>'.repeat(n)}</div>`;
const svgCam = `<svg class="ic" viewBox="0 0 24 24" aria-hidden="true"><path d="M23 19a2 2 0 0 1-2 2H3a2 2 0 0 1-2-2V8a2 2 0 0 1 2-2h4l2-3h6l2 3h4a2 2 0 0 1 2 2z"/><circle cx="12" cy="13" r="4"/></svg>`;

/* ============================ Capabilities ============================ */
let caps = { semantic: false, visual: false, maxImageBytes: 4194304 };

/* ============================ Visual search ============================ */
const V = { blob: null, url: "", busy: false };
const OK_TYPES = ["image/jpeg", "image/png", "image/webp"];
const MAX_PICK = 12 * 1024 * 1024;   // picked file; it is shrunk to a small JPEG before upload

function installCamera() {
  const form = $("#search");
  if (!form || $("#ai-cam") || !caps.visual) return;
  const btn = document.createElement("button");
  btn.type = "button"; btn.id = "ai-cam"; btn.className = "ai-cam"; btn.title = "Search by photo"; btn.setAttribute("aria-label", "Search by photo");
  btn.innerHTML = svgCam;
  const input = document.createElement("input");
  input.type = "file"; input.id = "ai-file"; input.accept = "image/jpeg,image/png,image/webp"; input.hidden = true; input.setAttribute("aria-label", "Choose a product photo");
  const submit = form.querySelector("button[type=submit]");
  form.insertBefore(btn, submit); form.appendChild(input);
  btn.addEventListener("click", () => input.click());
  input.addEventListener("change", () => { const f = input.files && input.files[0]; input.value = ""; if (f) pickFile(f); });
}

function pickFile(f) {
  if (!OK_TYPES.includes(f.type)) { toast("Please choose a JPG, PNG or WEBP photo.", "err"); return; }
  if (f.size > MAX_PICK) { toast("That photo is too large. Please choose one under 12 MB.", "err"); return; }
  toJpeg(f).then(blob => {
    if (blob.size > caps.maxImageBytes) { toast("That photo is still too large after resizing. Try a different one.", "err"); return; }
    if (V.url) URL.revokeObjectURL(V.url);
    V.blob = blob; V.url = URL.createObjectURL(blob);
    previewModal();
  }).catch(() => toast("We couldn't read that image. Try a different JPG or PNG.", "err"));
}

function toJpeg(file, max = 768) {
  return new Promise((resolve, reject) => {
    const url = URL.createObjectURL(file), img = new Image();
    img.onload = () => {
      const k = Math.min(1, max / Math.max(img.naturalWidth, img.naturalHeight));
      const c = document.createElement("canvas");
      c.width = Math.max(16, Math.round(img.naturalWidth * k)); c.height = Math.max(16, Math.round(img.naturalHeight * k));
      const g = c.getContext("2d"); g.fillStyle = "#fff"; g.fillRect(0, 0, c.width, c.height); g.drawImage(img, 0, 0, c.width, c.height);
      URL.revokeObjectURL(url);
      c.toBlob(b => b ? resolve(b) : reject(new Error("encode")), "image/jpeg", 0.85);
    };
    img.onerror = () => { URL.revokeObjectURL(url); reject(new Error("decode")); };
    img.src = url;
  });
}

function previewModal() {
  openModal(`<div class="modal-pad ai-preview"><h2>Search with a photo</h2>
    <p class="ai-sub">We'll look for PaboNaki products that look like this.</p>
    <img src="${esc(V.url)}" alt="Preview of your photo">
    <div class="ai-actions"><button class="btn btn-primary btn-lg" data-ai-go>${svgCam} Find similar products</button>
    <button class="btn btn-ghost" data-ai-pick>Choose another photo</button></div></div>`);
}

async function runVisual() {
  if (!V.blob || V.busy) return;
  V.busy = true; closeModal();
  const host = vis(); host.hidden = false;
  const head = `<div class="ai-head"><div class="ai-headline"><img class="ai-thumb" src="${esc(V.url)}" alt="Your photo"><div><h2>Products similar to your photo</h2>`;
  host.innerHTML = `${head}<p class="ai-sub" role="status">Looking through our catalogue…</p></div></div></div>${skeleton(4)}`;
  host.scrollIntoView({ behavior: "smooth", block: "start" });
  try {
    const fd = new FormData(); fd.append("image", V.blob, "photo.jpg");
    const res = await call("/search/visual", { method: "POST", body: fd });
    const items = (res.results || []).filter(inCatalogue);
    const actions = `<div class="ai-actions"><button class="btn btn-ghost" data-ai-pick>Try another photo</button><button class="btn btn-ghost" data-ai-clear>Clear</button></div>`;
    if (!items.length) {
      host.innerHTML = `${head}<p class="ai-sub">No close matches</p></div></div>${actions}</div>
        <div class="empty"><h3>No similar products found</h3><p>${esc(res.message || "Try a clearer photo of a single item on a plain background, or search by name instead.")}</p></div>`;
    } else {
      host.innerHTML = `${head}<p class="ai-sub">${items.length} ${items.length === 1 ? "match" : "matches"}, best first</p></div></div>${actions}</div>${grid(items)}`;
      items.forEach(p => {
        const media = host.querySelector(`.pcard[data-id="${CSS.escape(String(p.id))}"] .pcard-media`);
        if (media && p.similarity != null) media.insertAdjacentHTML("beforeend", `<span class="ai-sim" title="Visual similarity">${esc(p.similarity)}% match</span>`);
      });
    }
  } catch (e) {
    host.innerHTML = `${head}<p class="ai-sub">Something went wrong</p></div></div></div>
      <div class="alert error" role="alert"><span>${esc(e.message)}</span></div>
      <div class="ai-actions"><button class="btn btn-primary" data-ai-retry>Try again</button><button class="btn btn-ghost" data-ai-pick>Choose another photo</button><button class="btn btn-ghost" data-ai-clear>Clear</button></div>`;
  } finally { V.busy = false; }
}

function clearVisual() { const h = vis(); h.hidden = true; h.innerHTML = ""; V.blob = null; if (V.url) URL.revokeObjectURL(V.url); V.url = ""; }

/* ============================ Smart (semantic) search ============================ */
let semSeq = 0, semQuery = "";
function hideSemantic() { semSeq++; semQuery = ""; const h = sem(); h.hidden = true; h.innerHTML = ""; }

async function runSemantic(raw) {
  const q = String(raw || "").trim();
  if (!caps.semantic || q.length < 3) { hideSemantic(); return; }
  const my = ++semSeq, host = sem(); semQuery = q;
  host.hidden = false;
  host.innerHTML = `<div class="ai-head"><div><h2>Smart matches</h2><p class="ai-sub" role="status">Understanding “${esc(q)}”…</p></div></div>${skeleton(4)}`;
  const f = S.f || {}, params = new URLSearchParams({ q, size: "8", inStock: "true" });
  if (f.cat) params.set("category", f.cat);
  if (f.min !== "" && f.min != null) params.set("minPrice", f.min);
  if (f.max !== "" && f.max != null) params.set("maxPrice", f.max);
  try {
    const res = await call("/search/semantic?" + params.toString());
    if (my !== semSeq) return;
    const items = (res.results || []).filter(inCatalogue);
    if (!items.length) { hideSemantic(); return; }
    const it = res.interpreted || {}, notes = [];
    if (it.maxPrice != null) notes.push("up to " + money(it.maxPrice));
    if (it.minPrice != null) notes.push("from " + money(it.minPrice));
    if (it.affordable) notes.push("budget-friendly first");
    host.innerHTML = `<div class="ai-head"><div><h2>Smart matches for “${esc(res.query || q)}”</h2>
      <p class="ai-sub">${notes.length ? "Understood: " + esc(notes.join(" · ")) + ". " : ""}${res.mode === "keyword" && res.notice ? esc(res.notice) : "Matched by meaning as well as keywords."}</p></div></div>${grid(items)}`;
  } catch (e) {
    if (my === semSeq) hideSemantic();   // the normal keyword results below still work
  }
}

/* ============================ Recommendations ============================ */
let homeSeq = 0;
const block = (title, sub, items) => `<div class="ai-block"><div class="ai-head"><div><h2>${esc(title)}</h2>${sub ? `<p class="ai-sub">${esc(sub)}</p>` : ""}</div></div><div class="ai-row">${items.map(card).join("")}</div></div>`;

async function safe(path) {
  try { return await call(path); } catch (e) { if (e.status === 401 && path.includes("/for-you")) return null; return null; }
}

async function loadHome() {
  if (!S.loaded || S.isDemo) return;
  const my = ++homeSeq, host = reco();
  const signed = !!S.token, q = sid ? `&sessionId=${encodeURIComponent(sid)}` : "";
  let main = signed ? await safe(`/recommendations/for-you?limit=8${q}`) : null;
  if (!main) main = await safe(`/recommendations/discover?limit=8${q}`);
  const [viewed, trend] = await Promise.all([
    safe(`/recommendations/because-you-viewed?limit=8${q}`),
    main && main.personalized ? safe("/recommendations/trending?limit=8") : Promise.resolve(null)
  ]);
  if (my !== homeSeq) return;
  const parts = [], shown = new Set();
  const take = r => (r && r.items ? r.items.filter(inCatalogue) : []);
  const m = take(main);
  if (m.length) {
    m.forEach(p => shown.add(String(p.id)));
    parts.push(main.personalized
      ? block(signed ? "Recommended for You" : "Picked for You", "Based on what you've looked at and bought", m)
      : block("Trending Products", "Popular with PaboNaki shoppers right now", m));
  }
  const v = take(viewed).filter(p => !shown.has(String(p.id)));
  if (v.length >= 2) { v.forEach(p => shown.add(String(p.id))); parts.push(block("Because You Viewed", viewed.basedOn ? viewed.basedOn.name : "", v)); }
  const t = take(trend).filter(p => !shown.has(String(p.id)));
  if (t.length >= 2) parts.push(block("Trending Products", "Popular with PaboNaki shoppers right now", t));
  host.innerHTML = parts.join("");
  host.hidden = parts.length === 0;
}

/* Similar Products + Frequently Bought Together inside the existing product quick-view */
function watchQuickView() {
  const target = $("#modal-content");
  if (!target) return;
  new MutationObserver(async () => {
    const add = target.querySelector("[data-qv-add]");
    if (!add) return;
    const id = add.dataset.qvAdd;
    if (!id || target.querySelector(`.ai-qv-reco[data-for="${CSS.escape(id)}"]`) || S.isDemo) return;
    const box = document.createElement("div");
    box.className = "ai-qv-reco"; box.dataset.for = id;
    target.appendChild(box);
    const [fbt, sim] = await Promise.all([safe(`/recommendations/frequently-bought-together/${encodeURIComponent(id)}?limit=4`), safe(`/recommendations/similar/${encodeURIComponent(id)}?limit=6`)]);
    if (!box.isConnected) return;
    const f = fbt && fbt.items ? fbt.items.filter(inCatalogue) : [];
    const s = (sim && sim.items ? sim.items.filter(inCatalogue) : []).filter(p => String(p.id) !== String(id));
    box.innerHTML = (f.length ? block("Frequently Bought Together", "Other customers often add these", f) : "") + (s.length ? block("Similar Products", "", s) : "");
  }).observe(target, { childList: true });
}

/* ============================ Wiring ============================ */
document.addEventListener("click", e => {          // capture phase: record signals, never block the store's own handlers
  const t = e.target; let el;
  if ((el = t.closest("[data-add]"))) track("CART_ADD", el.dataset.add);
  else if ((el = t.closest("[data-qv-add]"))) track("CART_ADD", el.dataset.qvAdd);
  else if ((el = t.closest("[data-wish]"))) { const id = el.dataset.wish; setTimeout(() => { if (S.wish.includes(String(id))) track("WISHLIST_ADD", id); }, 0); }
  else if ((el = t.closest("[data-quick]"))) track("VIEW", el.dataset.quick);
}, true);

document.addEventListener("click", e => {
  const t = e.target; let el;
  if (t.closest("[data-ai-go]")) { runVisual(); return; }
  if (t.closest("[data-ai-retry]")) { runVisual(); return; }
  if (t.closest("[data-ai-pick]")) { closeModal(); $("#ai-file")?.click(); return; }
  if (t.closest("[data-ai-clear]")) { clearVisual(); return; }
  if ((el = t.closest("[data-search-all]"))) { setTimeout(() => { const q = $("#query")?.value; track("SEARCH", null, q); runSemantic(q); }, 0); return; }
  if (t.closest("[data-clear-filters]") || t.closest("[data-chip='q']")) hideSemantic();
});

document.addEventListener("submit", e => {
  if (e.target && e.target.id === "search") { const q = $("#query")?.value || ""; if (q.trim().length >= 2) track("SEARCH", null, q.trim()); runSemantic(q); }
});

document.addEventListener("input", e => {
  if (e.target && e.target.id === "query" && semQuery && e.target.value.trim() !== semQuery) hideSemantic();
});

function watchAccount() {
  const el = $("#acct-hello");
  if (el) new MutationObserver(() => loadHome()).observe(el, { childList: true, characterData: true, subtree: true });
}

async function boot() {
  for (let i = 0; i < 100 && !S.loaded; i++) await new Promise(r => setTimeout(r, 100));
  try { const c = await call("/search/capabilities"); if (c) caps = { ...caps, ...c }; } catch {}
  installCamera(); watchQuickView(); watchAccount();
  loadHome();
}
boot();
})();
