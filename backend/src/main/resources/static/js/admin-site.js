/* Admin: Homepage & footer builder (hero banners, promo tiles, trust badges, footer, social links) */
(() => {
"use strict";
const C = window.PABONAKI_CONFIG, $ = s => document.querySelector(s);
const esc = s => String(s ?? "").replace(/[&<>"']/g, c => ({ "&": "&amp;", "<": "&lt;", ">": "&gt;", '"': "&quot;", "'": "&#39;" }[c]));
  const token = () => (localStorage.getItem("pn_token") || "")
      .trim()
      .replace(/^(['"])(.*)\1$/, "$2");
const toast = m => { const t = $("#admin-toast"); if (!t) return alert(m); t.textContent = m; t.classList.add("show"); setTimeout(() => t.classList.remove("show"), 3200); };
async function api(path, opt = {}) {
  const r = await fetch(C.API_BASE + path, { ...opt, headers: { "Content-Type": "application/json", Authorization: "Bearer " + token(), ...(opt.headers || {}) } });
  const d = await r.json().catch(() => null);
  if (!r.ok) throw Error(d?.message || `Request failed (${r.status})`);
  return d;
}
const SEL = {
  layout: [["cover", "Photo fills the banner"], ["right", "Model / cut-out on the right"]],
  theme: [["purple", "Purple"], ["lime", "Lime"], ["rose", "Rose"], ["dark", "Dark"], ["light", "Light lavender"]],
  icon: [["truck", "Delivery truck"], ["cash", "Cash"], ["shield", "Shield / secure"], ["return", "Returns"], ["tag", "Tag / offers"], ["headphones", "Support"], ["package", "Package"], ["card", "Card"]]
};
const IMG = "image";
const SPECS = {
  heroBanners: { max: 6, label: "Banner", hint: "Recommended image: 1600×640 JPG/WebP (photo) or a transparent PNG of a model (choose “Model / cut-out on the right”).",
    fields: [["image", "Image URL (model / advertisement photo)", IMG], ["badge", "Small badge text"], ["title", "Headline"], ["subtitle", "Sub text"], ["cta", "Button text"], ["link", "Button link (#shop, #deals, #cat:Category, or https://…)"], ["layout", "Image layout", "select"], ["theme", "Colour theme (used when no photo fills the banner)", "select"]] },
  promoTiles: { max: 2, label: "Side tile", hint: "Two small tiles shown to the right of the hero. Leave empty to use the default tiles. Image ~ 600×400.",
    fields: [["image", "Image URL", IMG], ["title", "Headline"], ["subtitle", "Sub text"], ["cta", "Button text"], ["link", "Link"]] },
  promoRow: { max: 3, label: "Promo banner", hint: "Row of banners under the categories. Image ~ 700×400.",
    fields: [["image", "Image URL", IMG], ["title", "Headline"], ["subtitle", "Sub text"], ["cta", "Button text"], ["link", "Link"]] },
  trustBadges: { max: 4, label: "Badge", hint: "The four short trust messages under the hero. Leave empty for defaults.",
    fields: [["icon", "Icon", "select"], ["title", "Title"], ["text", "Short text"]] },
  footerColumns: { max: 4, label: "Footer column", hint: "One link per line in the format:  Label | URL   (e.g.  Returns policy | https://example.com/returns ).",
    fields: [["title", "Column title"], ["links", "Links (one per line: Label | URL)", "textarea"]] }
};
const SCALARS = ["footerAbout", "footerAddress", "footerCopyright", "socialFacebook", "socialInstagram", "socialYoutube", "socialTiktok", "socialWhatsapp"];
const TOGGLES = ["showBenefits", "showCategories", "showDeals", "showPromoRow"];
const data = {};

function itemHTML(key, it = {}, i) {
  const sp = SPECS[key];
  return `<div class="rep-item" data-key="${key}" data-i="${i}"><div class="rep-head"><b>${sp.label} ${i + 1}</b><button type="button" class="admin-btn secondary" data-rep-del>Remove</button></div><div class="rep-grid">${sp.fields.map(([f, label, type]) => {
    const v = it[f] ?? "";
    if (type === "select") return `<label>${label}<select data-f="${f}">${SEL[f].map(([val, t]) => `<option value="${val}" ${val === (v || SEL[f][0][0]) ? "selected" : ""}>${t}</option>`).join("")}</select></label>`;
    if (type === "textarea") return `<label class="full">${label}<textarea data-f="${f}" rows="5">${esc(v)}</textarea></label>`;
    if (type === IMG) return `<label class="full">${label}<input data-f="${f}" value="${esc(v)}" placeholder="https://… or upload below"><input type="file" accept="image/*" data-up><small></small></label>`;
    return `<label>${label}<input data-f="${f}" value="${esc(v)}"></label>`;
  }).join("")}</div></div>`;
}
function renderList(key) {
  const host = $(`#rep-${key}`); if (!host) return;
  const items = data[key] || [];
  host.innerHTML = items.map((it, i) => itemHTML(key, it, i)).join("") || '<p class="loading">Nothing added yet — the storefront shows its built-in default.</p>';
  $(`[data-rep-add="${key}"]`).hidden = items.length >= SPECS[key].max;
}
function collect(key) {
  return [...document.querySelectorAll(`#rep-${key} .rep-item`)].map(el => Object.fromEntries([...el.querySelectorAll("[data-f]")].map(f => [f.dataset.f, f.value.trim()])));
}
function syncAll() { for (const k of Object.keys(SPECS)) data[k] = collect(k); }
function build() {
  const host = $("#homepage-lists"); if (!host || host.dataset.ready) return; host.dataset.ready = "1";
  host.innerHTML = Object.entries(SPECS).map(([k, sp]) => `<div class="admin-panel"><div class="panel-head"><div><h2>${{ heroBanners: "Hero banners (slider)", promoTiles: "Hero side tiles", promoRow: "Promo banner row", trustBadges: "Trust badges", footerColumns: "Footer link columns" }[k]}</h2><p>${sp.hint}</p></div><button class="admin-btn" type="button" data-rep-add="${k}">＋ Add ${sp.label.toLowerCase()}</button></div><div id="rep-${k}" class="rep-list"></div></div>`).join("");
}
async function load() {
  build();
  try {
    const s = await api("/admin/settings");
    for (const k of Object.keys(SPECS)) data[k] = Array.isArray(s[k]) ? s[k] : [];
    const f = $("#homepage-form");
    for (const k of SCALARS) if (f.elements[k]) f.elements[k].value = s[k] ?? "";
    for (const k of TOGGLES) if (f.elements[k]) f.elements[k].value = String(s[k] === false || s[k] === "false" ? false : true);
    f.elements.freeShippingThreshold.value = s.freeShippingThreshold ?? 0;
    for (const k of Object.keys(SPECS)) renderList(k);
  } catch (e) { toast(e.message); }
}
document.addEventListener("click", e => {
  const t = e.target.closest("button"); if (!t) return;
  if (t.dataset.tab === "homepage") { $("#page-title").textContent = "Homepage & footer"; load(); }
  if (t.dataset.repAdd) { syncAll(); const k = t.dataset.repAdd; if ((data[k] || []).length < SPECS[k].max) { data[k] = [...(data[k] || []), {}]; renderList(k); } }
  if (t.dataset.repDel !== undefined) { syncAll(); const it = t.closest(".rep-item"); data[it.dataset.key].splice(Number(it.dataset.i), 1); renderList(it.dataset.key); }
});
document.addEventListener("change", async e => {
  if (!e.target.matches("[data-up]")) return;
  const file = e.target.files?.[0]; if (!file) return;
  const note = e.target.nextElementSibling, field = e.target.previousElementSibling;
  if (file.size > 8 * 1024 * 1024 || !file.type.startsWith("image/")) { note.textContent = "Choose an image up to 8 MB."; return; }
  note.textContent = "Uploading…";
  try {
    const fd = new FormData(); fd.append("file", file);
    const r = await fetch(C.API_BASE + "/admin/media/upload", { method: "POST", headers: { Authorization: "Bearer " + token() }, body: fd });
    const d = await r.json().catch(() => null);
    if (!r.ok || !d?.secure_url) throw Error(d?.message || "Upload failed");
    field.value = d.secure_url; note.textContent = "Uploaded ✓ — remember to save.";
  } catch (err) { note.textContent = "Upload failed: " + err.message; }
  e.target.value = "";
});
document.addEventListener("submit", async e => {
  if (e.target.id !== "homepage-form") return;
  e.preventDefault(); syncAll();
  const f = e.target, body = {};
  for (const k of SCALARS) body[k] = f.elements[k].value;
  for (const k of TOGGLES) body[k] = f.elements[k].value === "true";
  body.freeShippingThreshold = Number(f.elements.freeShippingThreshold.value || 0);
  for (const k of Object.keys(SPECS)) body[k] = data[k] || [];
  const btn = f.querySelector('button[type="submit"]'); btn.disabled = true;
  try { const saved = await api("/admin/settings", { method: "PUT", body: JSON.stringify(body) }); localStorage.pn_store_settings = JSON.stringify(saved); toast("Homepage & footer saved — refresh the storefront to see it."); }
  catch (err) { toast("Save failed: " + err.message); }
  finally { btn.disabled = false; }
});
})();
