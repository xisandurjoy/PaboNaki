/* PaboNaki storefront — all content (hero, banners, footer, ads) is driven by /api/v1/store/settings */
(() => {
"use strict";
const C = window.PABONAKI_CONFIG || { API_BASE: "/api/v1", USE_DEMO_FALLBACK: true };
const $ = (s, r = document) => r.querySelector(s);
const $$ = (s, r = document) => [...r.querySelectorAll(s)];
const esc = s => String(s ?? "").replace(/[&<>"']/g, c => ({ "&": "&amp;", "<": "&lt;", ">": "&gt;", '"': "&quot;", "'": "&#39;" }[c]));
const money = n => "৳" + Number(n || 0).toLocaleString("en-BD", { maximumFractionDigits: 2 });
const icon = (n, cls = "") => `<svg class="ic ${cls}" aria-hidden="true"><use href="#i-${n}"/></svg>`;
const store = {
  get(k, d) { try { const v = localStorage.getItem(k); return v == null ? d : JSON.parse(v); } catch { return d; } },
  set(k, v) { try { localStorage.setItem(k, JSON.stringify(v)); } catch {} },
  del(k) { try { localStorage.removeItem(k); } catch {} }
};
const safeUrl = u => {
  u = String(u || "").trim();
  if (!u) return "";
  if (/^(https?:|mailto:|tel:)/i.test(u)) return u;
  if (/^(\/|#|\.\/)/.test(u)) return u;
  if (/^[a-z][a-z0-9+.-]*:/i.test(u)) return "";
  return u;
};
const isExternal = u => /^https?:/i.test(u);
const linkAttrs = u => { const h = safeUrl(u); return h ? `href="${esc(h)}"${isExternal(h) ? ' target="_blank" rel="noopener noreferrer"' : ""}` : 'href="#shop"'; };
const initial = s => (String(s || "P").trim()[0] || "P").toUpperCase();
const debounce = (fn, ms = 200) => { let t; return (...a) => { clearTimeout(t); t = setTimeout(() => fn(...a), ms); }; };

/* Demo catalogue — only shown when the API cannot be reached (so the page never looks empty while developing) */
const DEFAULT_CATS = ["Fashion & Clothing", "Beauty & Personal Care", "Electronics & Gadgets", "Home & Lifestyle", "Bags, Jewellery & Accessories"];
const demo = [
  ["Everyday Tote Bag", 4, 850, 18, "photo-1548036328-c9fa89d128fa", "Your everyday carry-all, with room for the little things."],
  ["Soft Knit Cardigan", 0, 1650, 12, "photo-1434389677669-e08b4cac3105", "A cosy layer for slow mornings and late evenings."],
  ["Daily Glow Skincare Set", 1, 1250, 15, "photo-1608248543803-ba4f8c70ae0b", "A simple self-care ritual for your everyday routine."],
  ["Wireless Earbuds", 2, 2190, 9, "photo-1606220945770-b5b6c2c55bf1", "A compact companion for music and calls."],
  ["Minimal Desk Lamp", 3, 1390, 8, "photo-1507473885765-e6ed057f782c", "A warm glow for your reading or work corner."],
  ["Everyday Crossbody", 4, 1150, 11, "photo-1584917865442-de89df76afd3", "Lightweight, versatile, and ready to go anywhere."],
  ["Classic Cotton Shirt", 0, 990, 20, "photo-1598033129183-c4f50c736f10", "An easy-to-style staple for your weekly rotation."],
  ["Ceramic Mug Duo", 3, 690, 14, "photo-1514228742587-6b1558fcca3d", "A small daily joy for tea, coffee, and slow breaks."]
].map((d, i) => ({ id: "demo" + (i + 1), name: d[0], category: DEFAULT_CATS[d[1]], price: d[2], discountPercent: 0, stock: d[3], description: d[5], imageUrl: `https://images.unsplash.com/${d[4]}?auto=format&fit=crop&w=800&q=80` }));

/* ---------- State ---------- */
const S = {
  settings: {}, products: [], categories: [], isDemo: false, loaded: false,
  f: { cat: "", q: "", sort: "featured", min: "", max: "", inStock: false, deals: false },
  shown: 12,
  cart: store.get("pn_cart", []), wish: store.get("pn_wish", []).map(String),
  token: store.get("pn_token", ""), user: store.get("pn_user", null),
  slide: 0, timer: null, placing: false, drawer: "", authCtx: null
};
if (!Array.isArray(S.cart)) S.cart = [];
S.cart = S.cart.filter(x => x && x.p && x.p.id != null && x.qty > 0);

const cfg = (k, d = "") => { const v = S.settings[k]; return v === undefined || v === null || v === "" ? d : v; };
const on = (k, d = true) => { const v = S.settings[k]; if (v === undefined || v === null || v === "") return d; return !(v === false || v === "false"); };
const list = k => Array.isArray(S.settings[k]) ? S.settings[k] : [];
const priceOf = p => Number(p.finalPrice ?? (Number(p.price || 0) * (1 - Number(p.discountPercent || 0) / 100)));
const isSale = p => Number(p.discountPercent) > 0;
const maxQty = p => Math.max(0, Math.min(99, Number(p.stock ?? 99)));
const wishOn = () => on("wishlistEnabled");
const aiOn = () => on("aiEnabled");
const storeName = () => cfg("storeName", "PaboNaki");
const freeFrom = () => Math.max(0, Number(cfg("freeShippingThreshold", 0)) || 0);
const cartCount = () => S.cart.reduce((n, x) => n + x.qty, 0);
const cartSub = () => S.cart.reduce((n, x) => n + priceOf(x.p) * x.qty, 0);
const feeFor = (area, sub) => {
  if (freeFrom() > 0 && sub >= freeFrom()) return 0;
  const d = Number(cfg("dhakaDelivery", 80)), o = Number(cfg("outsideDelivery", 130));
  return area === "OUTSIDE_DHAKA" ? (isNaN(o) ? 130 : o) : (isNaN(d) ? 80 : d);
};
const normPhone = raw => {
  let d = String(raw || "").replace(/[\s\-()]/g, "");
  if (d.startsWith("+88")) d = d.slice(3); else if (d.startsWith("88") && d.length === 13) d = d.slice(2);
  return /^01[3-9]\d{8}$/.test(d) ? d : null;
};

/* ---------- API ---------- */
async function api(path, opt = {}) {
  const h = { ...(opt.body ? { "Content-Type": "application/json" } : {}), ...(opt.headers || {}) };
  if (S.token) h.Authorization = "Bearer " + S.token;
  let r;
  try { r = await fetch(C.API_BASE + path, { ...opt, headers: h }); }
  catch { const e = new Error("Can't reach the server. Please check your connection and try again."); e.network = true; throw e; }
  const text = await r.text(); let d = null;
  try { d = text ? JSON.parse(text) : null; } catch {}
  if (!r.ok) {
    if (r.status === 401 && S.token && !path.startsWith("/auth/")) clearSession(true);
    const e = new Error((d && (d.message || d.error)) || (r.status === 401 ? "Please log in to continue." : `Something went wrong (${r.status}). Please try again.`));
    e.status = r.status; throw e;
  }
  return d;
}

/* ---------- Toast / overlays ---------- */
let toastTimer;
function toast(msg, type = "") {
  const t = $("#toast");
  t.className = "toast" + (type === "err" ? " err" : "");
  t.innerHTML = (type === "err" ? icon("alert") : icon("check-circle")) + `<span>${esc(msg)}</span>`;
  requestAnimationFrame(() => t.classList.add("show"));
  clearTimeout(toastTimer);
  toastTimer = setTimeout(() => t.classList.remove("show"), type === "err" ? 5200 : 2800);
}
const anyOverlay = () => !$("#drawer").hidden || !$("#modal-host").hidden || !$("#checkout-host").hidden;
const syncLock = () => document.body.classList.toggle("lock", anyOverlay() || $("#filters")?.classList.contains("open"));
function openDrawer(kind) {
  closeModal(); closeAcctMenu();
  S.drawer = kind; $("#scrim").hidden = false; $("#drawer").hidden = false; renderDrawer(); syncLock();
}
function closeDrawer() { S.drawer = ""; $("#drawer").hidden = true; if ($("#modal-host").hidden) $("#scrim").hidden = true; closeFilters(); syncLock(); }
function openModal(html, cls = "") {
  closeDrawer(); closeAcctMenu();
  $("#modal").className = "modal " + cls; $("#modal-content").innerHTML = html;
  $("#modal-host").hidden = false; $("#scrim").hidden = true; syncLock();
  setTimeout(() => { const f = $("#modal-content input:not([type=hidden]),#modal-content button.btn"); f?.focus({ preventScroll: true }); }, 60);
}
function closeModal() { $("#modal-host").hidden = true; $("#modal-content").innerHTML = ""; syncLock(); }
function closeFilters() { $("#filters")?.classList.remove("open"); }

/* ---------- Session ---------- */
function setSession(token, user) {
  S.token = token; S.user = user; store.set("pn_token", token); store.set("pn_user", user); renderAccountBtn();
}
function clearSession(expired) {
  S.token = ""; S.user = null; store.del("pn_token"); store.del("pn_user"); renderAccountBtn();
  if (expired) toast("Your session expired. Please log in again.", "err");
}
function renderAccountBtn() {
  const u = S.user;
  $("#acct-hello").textContent = u ? "Hi, " + (u.firstName || (u.name || "there").split(" ")[0]) : "Hello, sign in";
}
function closeAcctMenu() { $("#acct-menu").hidden = true; }
function toggleAcctMenu() {
  const m = $("#acct-menu");
  if (!m.hidden) { m.hidden = true; return; }
  const u = S.user;
  m.innerHTML = `<div class="who"><b>${esc(u.name || "Customer")}</b><small>${esc(u.email || "")}</small></div>
    ${u.role === "ADMIN" ? `<a href="/admin.html">${icon("grid")} Admin dashboard</a>` : `<a href="/customer.html#my-orders">${icon("package")} My orders</a><a href="/customer.html">${icon("user")} My account</a>`}
    ${wishOn() ? `<button data-open="wish">${icon("heart")} Wishlist</button>` : ""}
    <button data-logout>${icon("logout")} Log out</button>`;
  m.hidden = false;
}

/* ---------- Header / brand / top bar ---------- */
const CAT_ICONS = [[/fashion|cloth|wear|apparel/i, "shirt"], [/beauty|care|skin|cosmetic/i, "sparkle"], [/electronic|gadget|tech/i, "headphones"], [/home|life|kitchen|furniture/i, "home"], [/bag|jewel|access/i, "briefcase"]];
const catIcon = n => (CAT_ICONS.find(([re]) => re.test(n)) || [0, "grid"])[1];
const catNames = () => S.categories.length ? S.categories : DEFAULT_CATS;

function renderBrand() {
  const name = storeName();
  $("#brand-name").textContent = name;
  $("#brand-tag").textContent = cfg("tagline", "Ja Khujcho, Peye Jabe.");
  $("#brand-mark").textContent = name === "PaboNaki" ? "Pn" : name.slice(0, 2).replace(/^./, c => c.toUpperCase());
  document.title = `${name} — ${cfg("tagline", "Ja Khujcho, Peye Jabe.")}`;
}
function renderTopbar() {
  const phone = cfg("supportPhone"), mail = cfg("supportEmail");
  const msg = cfg("notice", "A little something for every kind of you · Cash on delivery available");
  const left = phone ? `<a href="tel:${esc(phone.replace(/[^0-9+]/g, ""))}">${icon("phone")} ${esc(phone)}</a>` : mail ? `<a href="mailto:${esc(mail)}">${icon("mail")} ${esc(mail)}</a>` : "";
  let html = "";
  if (on("maintenance", false)) html += `<div class="maint">The store is being updated — some features may be temporarily unavailable.</div>`;
  html += `<div class="container topbar-in"><div class="topbar-side">${left}</div><div class="topbar-msg">${esc(msg)}</div><div class="topbar-side" style="justify-content:flex-end"><a href="/customer.html#my-orders">Track order</a><a href="/customer.html#support">Help</a></div></div>`;
  $("#topbar").innerHTML = html;
}
function adActive() { return on("adEnabled", false) && (cfg("adTitle") || cfg("adText")) && !sessionStorage.getItem("pn_ad_x"); }
function renderAdStrip() {
  const host = $("#adstrip");
  if (!adActive() || cfg("adPlacement", "announcement") !== "announcement") { host.innerHTML = ""; return; }
  const link = safeUrl(cfg("adLinkUrl"));
  host.innerHTML = `<div class="adstrip"><span><b>${esc(cfg("adTitle"))}</b> ${esc(cfg("adText"))}</span>${link ? `<a class="adstrip-cta" ${linkAttrs(link)}>Shop now</a>` : ""}<button class="adstrip-x" data-ad-close aria-label="Dismiss">${icon("x")}</button></div>`;
}
function renderAdBand() {
  const host = $("#adband");
  if (!adActive() || cfg("adPlacement") !== "hero") { host.innerHTML = ""; return; }
  const img = safeUrl(cfg("adImageUrl")), link = safeUrl(cfg("adLinkUrl"));
  host.innerHTML = `<div class="adband ${img ? "has-img" : ""}">${img ? `<img src="${esc(img)}" alt="" loading="lazy" data-hide>` : ""}
    <div class="ad-main"><span class="ad-tag">Sponsored</span><h3>${esc(cfg("adTitle", "Special offer"))}</h3>${cfg("adText") ? `<p>${esc(cfg("adText"))}</p>` : ""}</div>
    ${link ? `<a class="btn btn-accent" ${linkAttrs(link)}>Shop now ${icon("right")}</a>` : ""}
    <button class="adband-x" data-ad-close aria-label="Dismiss">${icon("x")}</button></div>`;
}
function renderCatNav() {
  const hasDeals = S.products.some(isSale);
  const row = $("#cat-nav-row");
  row.innerHTML = `<button data-cat="" class="${!S.f.cat && !S.f.deals ? "on" : ""}">${icon("grid")} All products</button>` +
    catNames().map(c => `<button data-cat="${esc(c)}" class="${S.f.cat === c ? "on" : ""}">${icon(catIcon(c))} ${esc(c)}</button>`).join("") +
    `<span class="sp"></span>` +
    (hasDeals ? `<a class="deal" href="#deals">${icon("tag")} Deals</a>` : "") +
    (aiOn() ? `<a href="#ai">${icon("zap")} AI assistant</a>` : "");
  const sel = $("#search-cat"), cur = sel.value;
  sel.innerHTML = `<option value="">All</option>` + catNames().map(c => `<option value="${esc(c)}">${esc(c)}</option>`).join("");
  sel.value = catNames().includes(cur) ? cur : "";
}
function renderBadges() {
  const c = cartCount();
  const el = $("#count"); const prev = el.textContent; el.textContent = c; el.hidden = false;
  if (String(prev) !== String(c)) { el.classList.remove("bump"); void el.offsetWidth; el.classList.add("bump"); }
  $("#bag-total").textContent = money(cartSub());
  const w = $("#wish-count"); w.textContent = S.wish.length; w.hidden = !S.wish.length || !wishOn();
  $("#wishlist").hidden = !wishOn();
}

/* ---------- Hero ---------- */
const THEMES = ["purple", "lime", "rose", "dark", "light"];
function defaultSlides() {
  const cats = catNames();
  return [
    { badge: "New arrivals", title: cfg("heroTitle", "Good finds. Great feelings."), subtitle: cfg("heroSubtitle", "Little upgrades, thoughtful gifts and everyday essentials — all in one place."), cta: cfg("heroCta", "Shop now"), link: "#shop", theme: "purple" },
    { badge: "Top picks", title: "Style that fits your everyday", subtitle: "Easy layers, wardrobe staples and finishing touches you'll reach for again and again.", cta: "Shop fashion", link: "#cat:" + (cats[0] || ""), theme: "lime" },
    { badge: "Smart & useful", title: "Gadgets worth having", subtitle: "Compact tech and home upgrades to make daily life a little easier.", cta: "Explore gadgets", link: "#cat:" + (cats[2] || ""), theme: "dark" }
  ];
}
function slideHTML(b) {
  const img = safeUrl(b.image);
  const theme = THEMES.includes(b.theme) ? b.theme : "purple";
  const layout = img ? (b.layout === "right" ? "l-right" : "l-cover") : "";
  return `<div class="slide t-${theme} ${layout}">
    <div class="slide-art"><i></i><i></i><i></i></div>
    ${img ? `<img class="s-img" src="${esc(img)}" alt="${esc(b.title || "")}" data-hide>` : ""}
    <div class="slide-body">
      ${b.badge ? `<span class="s-badge">${esc(b.badge)}</span>` : ""}
      <h2 class="s-title">${esc(b.title || "")}</h2>
      ${b.subtitle ? `<p class="s-sub">${esc(b.subtitle)}</p>` : ""}
      ${b.cta ? `<div class="s-cta"><a class="btn btn-accent" ${linkAttrs(b.link)}>${esc(b.cta)} ${icon("right")}</a></div>` : ""}
    </div></div>`;
}
function tileHTML(t, alt) {
  const img = safeUrl(t.image);
  return `<a class="tile ${alt ? "alt" : ""} ${img ? "has-img" : ""}" ${linkAttrs(t.link)}>
    ${img ? `<img src="${esc(img)}" alt="" loading="lazy" data-hide>` : `<i class="t-deco"></i>`}
    <div>${t.badge ? `<small>${esc(t.badge)}</small>` : ""}<h3>${esc(t.title || "")}</h3>${t.subtitle ? `<p>${esc(t.subtitle)}</p>` : ""}${t.cta ? `<span class="t-link">${esc(t.cta)} ${icon("right")}</span>` : ""}</div></a>`;
}
function renderHero() {
  const banners = list("heroBanners").filter(b => b && (b.title || b.image));
  const slides = banners.length ? banners : defaultSlides();
  const adminTiles = list("promoTiles").filter(t => t && (t.title || t.image));
  const hasDeals = S.products.some(isSale);
  const tiles = adminTiles.length ? adminTiles : [
    { badge: "Today's deals", title: "Great prices, right now", subtitle: hasDeals ? "Save on selected finds." : "Browse everything in one place.", cta: hasDeals ? "Shop deals" : "Browse all", link: hasDeals ? "#deals" : "#shop" },
    aiOn() ? { badge: "PaboNaki AI", title: "Not sure what to buy?", subtitle: "Tell us your budget and vibe.", cta: "Ask the assistant", link: "#ai" } : { badge: "Just in", title: "Fresh finds, picked for you", cta: "Explore", link: "#shop" }
  ];
  S.slide = 0;
  $("#hero").innerHTML = `<div class="carousel" id="carousel" aria-roledescription="carousel">
      <div class="slides" id="slides">${slides.map(slideHTML).join("")}</div>
      ${slides.length > 1 ? `<button class="car-btn car-prev" data-car="-1" aria-label="Previous slide">${icon("left")}</button><button class="car-btn car-next" data-car="1" aria-label="Next slide">${icon("right")}</button>
      <div class="car-dots">${slides.map((_, i) => `<button data-slide="${i}" class="${i ? "" : "on"}" aria-label="Go to slide ${i + 1}"></button>`).join("")}</div>` : ""}
    </div>
    <div class="hero-side">${tiles.slice(0, 2).map((t, i) => tileHTML(t, i === 1 && !safeUrl(t.image))).join("")}</div>`;
  startCarousel(slides.length);
}
function goSlide(i) {
  const n = $$("#slides .slide").length; if (!n) return;
  S.slide = (i + n) % n;
  $("#slides").style.transform = `translateX(-${S.slide * 100}%)`;
  $$(".car-dots button").forEach((b, k) => b.classList.toggle("on", k === S.slide));
}
function startCarousel(n) {
  clearInterval(S.timer);
  const car = $("#carousel"); if (!car || n < 2) return;
  const play = () => { clearInterval(S.timer); if (!matchMedia("(prefers-reduced-motion: reduce)").matches) S.timer = setInterval(() => goSlide(S.slide + 1), 6000); };
  play();
  car.addEventListener("mouseenter", () => clearInterval(S.timer));
  car.addEventListener("mouseleave", play);
  let x0 = null;
  car.addEventListener("pointerdown", e => { x0 = e.clientX; });
  car.addEventListener("pointerup", e => { if (x0 != null && Math.abs(e.clientX - x0) > 50) goSlide(S.slide + (e.clientX < x0 ? 1 : -1)); x0 = null; });
}

/* ---------- Benefits / categories / deals / promo row ---------- */
function renderBenefits() {
  const host = $("#benefits");
  if (!on("showBenefits")) { host.innerHTML = ""; host.hidden = true; return; }
  host.hidden = false;
  let items = list("trustBadges").filter(b => b && b.title);
  if (!items.length) {
    items = [
      freeFrom() > 0 ? { icon: "truck", title: "Free delivery", text: `On orders over ${money(freeFrom())}` } : { icon: "truck", title: "Fast delivery", text: "Dhaka & nationwide" },
      { icon: "cash", title: "Cash on delivery", text: "Pay when it arrives" },
      { icon: "shield", title: "Secure checkout", text: "Your details stay safe" },
      { icon: "return", title: "Friendly support", text: "We're here to help" }
    ];
  }
  const ok = ["truck", "cash", "shield", "return", "tag", "headphones", "package", "card", "heart", "zap"];
  host.innerHTML = items.slice(0, 4).map(b => `<div class="benefit">${icon(ok.includes(b.icon) ? b.icon : "shield")}<div><b>${esc(b.title)}</b><small>${esc(b.text || "")}</small></div></div>`).join("");
}
function renderCategories() {
  const host = $("#categories-section");
  if (!on("showCategories")) { host.innerHTML = ""; return; }
  const names = catNames();
  host.innerHTML = `<div class="sec-head"><div><h2>Shop by category</h2><p>Whatever you're looking for, start here.</p></div><button class="link-btn" data-cat="">View all ${icon("right")}</button></div>
    <div class="cat-grid">${names.map(c => { const n = S.products.filter(p => p.category === c).length; return `<button class="cat-tile ${S.f.cat === c ? "on" : ""}" data-cat="${esc(c)}"><span class="cat-ico">${icon(catIcon(c))}</span><b>${esc(c)}</b><small>${n} ${n === 1 ? "item" : "items"}</small></button>`; }).join("")}</div>`;
}
function renderDeals() {
  const host = $("#deals-section");
  const deals = S.products.filter(p => isSale(p) && p.stock > 0).sort((a, b) => b.discountPercent - a.discountPercent).slice(0, 12);
  if (!on("showDeals") || !deals.length) { host.innerHTML = ""; host.hidden = true; return; }
  host.hidden = false;
  host.innerHTML = `<div class="sec-head"><div><h2>Today's deals</h2><p>Limited-time savings on selected finds.</p></div>
    <div class="deals-head-tools"><button data-scroll="-1" aria-label="Scroll left">${icon("left")}</button><button data-scroll="1" aria-label="Scroll right">${icon("right")}</button></div></div>
    <div class="scroller" id="deals-scroller">${deals.map(card).join("")}</div>`;
}
function renderPromoRow() {
  const host = $("#promo-row");
  const items = list("promoRow").filter(t => t && (t.title || t.image)).slice(0, 3);
  if (!on("showPromoRow") || !items.length) { host.innerHTML = ""; host.className = "container promo-row"; return; }
  host.className = "container promo-row n" + items.length;
  host.innerHTML = items.map((t, i) => tileHTML(t, i === 1 && !safeUrl(t.image))).join("");
}
function renderAiBanner() {
  const host = $("#ai-banner");
  if (!aiOn()) { host.innerHTML = ""; return; }
  host.innerHTML = `<div class="ai-card"><span class="ai-ico">${icon("zap")}</span><div class="ai-main"><h2>Not sure what you're looking for?</h2><p>Tell us your budget, the occasion or the vibe — our AI shopping assistant will suggest products from the catalogue.</p></div><button class="btn btn-accent btn-lg" data-open="ai">Help me find it ${icon("right")}</button></div>`;
}

/* ---------- AI virtual try-on ---------- */
let tryOnResultUrl = "";
function canTryOn(p) {
  const text = `${p.category || ""} ${p.name || ""}`.toLowerCase();
  return /fashion|cloth|apparel|wear|shirt|jersey|kurta|dress|jacket|coat|trouser|pant|jeans|hoodie|sweater|cardigan|top\b|skirt|saree|shalwar|kameez/.test(text);
}
function openTryOn(productId) {
  const p = S.products.find(x => String(x.id) === String(productId));
  if (!p) { toast("This product could not be found.", "err"); return; }
  if (tryOnResultUrl) { URL.revokeObjectURL(tryOnResultUrl); tryOnResultUrl = ""; }
  const image = safeUrl(p.imageUrl);
  openModal(`<div class="tryon-modal">
    <div class="tryon-head"><div><span class="tryon-kicker">AI VIRTUAL TRY-ON</span><h2>See it on you</h2><p>Upload a clear photo of yourself to preview this item.</p></div><button class="icon-btn" type="button" data-close aria-label="Close">${icon("x")}</button></div>
    <div class="tryon-product">${image ? `<img src="${esc(image)}" alt="${esc(p.name)}">` : `<div class="noimg">${esc(initial(p.name))}</div>`}<div><b>${esc(p.name)}</b><small>${esc(p.category || "Selected product")}</small></div></div>
    <form id="tryon-form" class="tryon-form">
      <label for="tryon-person-photo">Your photo (JPG or PNG, max 5 MB)</label>
      <input id="tryon-person-photo" name="person_image" type="file" accept="image/jpeg,image/png" required>
      <p class="tryon-hint">For best results, use a clear, front-facing photo with the outfit area visible.</p>
      <button class="btn btn-primary btn-block" type="submit" data-tryon-submit>Generate try-on image</button>
      <p id="tryon-status" class="tryon-status" role="status" aria-live="polite"></p>
      <div id="tryon-result" class="tryon-result" hidden>
        <img id="tryon-result-image" alt="AI virtual try-on result">
        <a id="tryon-download" class="btn btn-accent btn-block" download="pabonaki-try-on.png">${icon("download")} Download image</a>
      </div>
    </form>
    <p class="tryon-privacy">Your uploaded photo is sent to the AI try-on provider to generate this result.</p>
  </div>`, "tryon-modal-shell");
  const form = $("#tryon-form");
  form.dataset.productId = String(p.id);
  form.dataset.garmentUrl = p.imageUrl || "";
}
async function submitTryOn(form) {
  const fileInput = $("#tryon-person-photo");
  const status = $("#tryon-status");
  const button = form.querySelector("[data-tryon-submit]");
  const result = $("#tryon-result");
  const file = fileInput.files && fileInput.files[0];
  if (!file) { status.textContent = "Please choose your photo first."; return; }
  if (!["image/jpeg", "image/png"].includes(file.type)) { status.textContent = "Please select a JPG or PNG image."; return; }
  if (file.size > 5 * 1024 * 1024) { status.textContent = "Your photo must be smaller than 5 MB."; return; }
  const oldLabel = button.textContent;
  button.disabled = true;
  button.textContent = "Generating image…";
  status.textContent = "This can take a little while. Please keep this window open.";
  result.hidden = true;
  try {
    const body = new FormData();
    body.append("person_image", file);
    body.append("productId", form.dataset.productId || "");
    body.append("garmentUrl", form.dataset.garmentUrl || "");
    const response = await fetch(C.API_BASE + "/tryon/generate", {
      method: "POST",
      headers: S.token ? { Authorization: "Bearer " + S.token } : {},
      body
    });
    if (!response.ok) {
      let data = {};
      try { data = await response.json(); } catch {}
      throw new Error(data.message || data.error || `Try-on failed (${response.status}). Please try again.`);
    }
    const blob = await response.blob();
    if (!blob.type.startsWith("image/")) throw new Error("The try-on service did not return an image. Please try again.");
    if (tryOnResultUrl) URL.revokeObjectURL(tryOnResultUrl);
    tryOnResultUrl = URL.createObjectURL(blob);
    $("#tryon-result-image").src = tryOnResultUrl;
    const download = $("#tryon-download");
    download.href = tryOnResultUrl;
    download.download = "pabonaki-try-on.png";
    result.hidden = false;
    status.textContent = "Your try-on image is ready!";
  } catch (error) {
    status.textContent = error.message || "Could not generate the image. Please try again.";
  } finally {
    button.disabled = false;
    button.textContent = oldLabel;
  }
}

/* ---------- Product card + shop ---------- */
function card(p) {
  const price = priceOf(p), sale = isSale(p), out = p.stock <= 0, low = !out && p.stock <= 5, id = esc(p.id);
  const img = safeUrl(p.imageUrl), w = S.wish.includes(String(p.id));
  return `<article class="pcard" data-id="${id}">
    <div class="pcard-media" data-quick="${id}">
      <div class="badges">${sale ? `<span class="badge">-${Math.round(p.discountPercent)}%</span>` : ""}${out ? `<span class="badge dark">Sold out</span>` : low ? `<span class="badge warn">Only ${p.stock} left</span>` : ""}</div>
      ${wishOn() ? `<button class="wish-btn ${w ? "on" : ""}" data-wish="${id}" aria-label="${w ? "Remove from" : "Add to"} wishlist" aria-pressed="${w}">${icon("heart")}</button>` : ""}
      ${img ? `<img src="${esc(img)}" alt="${esc(p.name)}" loading="lazy" data-fb="${esc(initial(p.name))}">` : `<div class="noimg">${esc(initial(p.name))}</div>`}
      <button class="quick" data-quick="${id}" tabindex="-1">Quick view</button>
    </div>
    <div class="pcard-body">
      <span class="pcard-cat">${esc(p.category || "PaboNaki find")}</span>
      <h3 class="pcard-name" data-quick="${id}">${esc(p.name)}</h3>
      <div class="pcard-price">${sale ? `<b class="sale">${money(price)}</b><del>${money(p.price)}</del>` : `<b>${money(price)}</b>`}</div>
      <button class="btn-add" data-add="${id}" ${out ? "disabled" : ""}>${out ? "Sold out" : `${icon("bag")} Add to bag`}</button>
      ${canTryOn(p) && img ? `<button class="btn-tryon" data-tryon="${id}" type="button">${icon("shirt")} Try it on</button>` : ""}
    </div></article>`;
}
function filtered() {
  const f = S.f, words = f.q.toLowerCase().split(/\s+/).filter(Boolean);
  let a = S.products.filter(p => {
    if (f.cat && p.category !== f.cat) return false;
    const hay = `${p.name} ${p.category} ${p.description || ""} ${p.sku || ""}`.toLowerCase();
    if (words.some(w => !hay.includes(w))) return false;
    const pr = priceOf(p);
    if (f.min !== "" && pr < Number(f.min)) return false;
    if (f.max !== "" && pr > Number(f.max)) return false;
    if (f.inStock && p.stock <= 0) return false;
    if (f.deals && !isSale(p)) return false;
    return true;
  });
  if (f.sort === "low") a.sort((x, y) => priceOf(x) - priceOf(y));
  else if (f.sort === "high") a.sort((x, y) => priceOf(y) - priceOf(x));
  else if (f.sort === "discount") a.sort((x, y) => (y.discountPercent || 0) - (x.discountPercent || 0));
  else if (f.sort === "name") a.sort((x, y) => x.name.localeCompare(y.name));
  return a;
}
function renderShopShell() {
  $("#shop").innerHTML = `<div class="shop-layout">
    <aside class="filters" id="filters" aria-label="Filters"></aside>
    <div>
      <div class="shop-bar"><div><h2 id="shop-title">All products</h2><div class="res" id="shop-res"></div></div>
        <div class="shop-tools"><button class="btn btn-ghost filter-toggle" data-open-filters>${icon("grid")} Filters</button>
          <select id="sort" aria-label="Sort products"><option value="featured">Sort: Featured</option><option value="low">Price: low to high</option><option value="high">Price: high to low</option><option value="discount">Biggest discount</option><option value="name">Name: A–Z</option></select></div></div>
      <div class="chips" id="chips"></div>
      <div class="pgrid" id="products">${'<div class="skeleton sk-card"></div>'.repeat(8)}</div>
      <div class="load-more" id="more"></div>
    </div></div>`;
  $("#sort").value = S.f.sort;
}
function renderFilters() {
  const f = S.f, host = $("#filters"); if (!host) return;
  const names = catNames();
  host.innerHTML = `<h3>Filters <button class="link-btn" data-clear-filters>Clear all</button></h3>
    <div class="f-group"><h4>Category</h4><div class="f-cats">
      <button data-cat="" class="${!f.cat ? "on" : ""}">All products <small>${S.products.length}</small></button>
      ${names.map(c => `<button data-cat="${esc(c)}" class="${f.cat === c ? "on" : ""}">${esc(c)} <small>${S.products.filter(p => p.category === c).length}</small></button>`).join("")}</div></div>
    <div class="f-group"><h4>Price (৳)</h4><div class="f-price"><input id="f-min" type="number" min="0" inputmode="numeric" placeholder="Min" value="${esc(f.min)}"><span>–</span><input id="f-max" type="number" min="0" inputmode="numeric" placeholder="Max" value="${esc(f.max)}"></div></div>
    <div class="f-group"><h4>Availability</h4><label class="f-check"><input type="checkbox" id="f-stock" ${f.inStock ? "checked" : ""}> In stock only</label><label class="f-check"><input type="checkbox" id="f-deals" ${f.deals ? "checked" : ""}> On sale</label></div>
    <button class="btn btn-primary btn-block filter-done" data-close-filters style="display:none">Show results</button>`;
  if (matchMedia("(max-width:900px)").matches) $(".filter-done", host).style.display = "inline-flex";
}
function renderGrid() {
  const grid = $("#products"); if (!grid) return;
  const f = S.f, all = filtered(), page = all.slice(0, S.shown);
  $("#shop-title").textContent = f.q ? `Results for “${f.q}”` : f.deals ? "On sale" : f.cat || cfg("featuredTitle", "All products");
  $("#shop-res").textContent = S.loaded ? `${all.length} ${all.length === 1 ? "product" : "products"}` : "";
  const chips = [];
  if (f.cat) chips.push(["cat", f.cat]); if (f.q) chips.push(["q", `“${f.q}”`]);
  if (f.min !== "" || f.max !== "") chips.push(["price", `${f.min !== "" ? money(f.min) : "৳0"} – ${f.max !== "" ? money(f.max) : "any"}`]);
  if (f.inStock) chips.push(["inStock", "In stock"]); if (f.deals) chips.push(["deals", "On sale"]);
  $("#chips").innerHTML = chips.map(([k, v]) => `<span class="chip">${esc(v)}<button data-chip="${k}" aria-label="Remove filter">${icon("x")}</button></span>`).join("");
  if (!S.loaded) return;
  grid.innerHTML = page.length ? page.map(card).join("") : `<div class="empty">${icon("search")}<h3>No products found</h3><p>Try a different search, category or price range.</p><p style="margin-top:14px"><button class="btn btn-primary" data-clear-filters>Clear filters</button></p></div>`;
  $("#more").innerHTML = all.length > page.length ? `<button class="btn btn-ghost btn-lg" data-more>Load more (${all.length - page.length} left)</button>` : "";
}
function refreshShop() { renderCatNav(); renderCategories(); renderFilters(); renderGrid(); }
function setCategory(c, scroll = true) {
  S.f.cat = c; S.f.deals = false; S.shown = 12; closeFilters(); syncLock(); refreshShop();
  if (scroll) $("#shop").scrollIntoView({ behavior: "smooth", block: "start" });
}

/* ---------- Footer ---------- */
function parseLinks(txt) {
  return String(txt || "").split(/\r?\n/).map(l => l.trim()).filter(Boolean).map(l => { const [label, ...rest] = l.split("|"); return { label: label.trim(), url: (rest.join("|") || "#shop").trim() }; }).filter(x => x.label);
}
function renderFooter() {
  const name = storeName(), year = new Date().getFullYear();
  const about = cfg("footerAbout") || cfg("footerText") || "Good finds for your everyday.";
  const social = [["facebook", "socialFacebook"], ["instagram", "socialInstagram"], ["youtube", "socialYoutube"], ["tiktok", "socialTiktok"]]
    .map(([ic, k]) => [ic, safeUrl(cfg(k))]).filter(x => x[1]);
  const wa = String(cfg("socialWhatsapp")).replace(/[^0-9]/g, "");
  let cols = list("footerColumns").filter(c => c && c.title);
  if (!cols.length) cols = [
    { title: "Shop", links: "All products | #shop\nToday's deals | #deals\nShop by category | #categories" + (aiOn() ? "\nAI shopping assistant | #ai" : "") },
    { title: "My account", links: "Log in / Register | #account\nMy orders | /customer.html#my-orders\n" + (wishOn() ? "Wishlist | #wishlist\n" : "") + "Contact support | /customer.html#support" }
  ];
  const phone = cfg("supportPhone"), mail = cfg("supportEmail"), addr = cfg("footerAddress");
  const contact = (phone || mail || addr) ? `<div class="f-col"><h4>Contact us</h4><ul class="f-contact">
    ${addr ? `<li>${icon("pin")}<span>${esc(addr)}</span></li>` : ""}${phone ? `<li>${icon("phone")}<a href="tel:${esc(phone.replace(/[^0-9+]/g, ""))}">${esc(phone)}</a></li>` : ""}${mail ? `<li>${icon("mail")}<a href="mailto:${esc(mail)}">${esc(mail)}</a></li>` : ""}</ul></div>` : "";
  const pay = [];
  if (on("codEnabled")) pay.push(`<span>${icon("cash")} Cash on delivery</span>`);
  if (on("onlinePaymentEnabled")) pay.push(`<span>${icon("card")} Online payment</span>`);
  pay.push(`<span>${icon("lock")} Secure checkout</span>`);
  const copy = String(cfg("footerCopyright", "© {year} {store}. All rights reserved.")).replace(/\{year\}/gi, year).replace(/\{store\}/gi, name);
  $("#site-footer").innerHTML = `<div class="container f-top">
      <div class="f-brand"><a class="brand" href="/"><span class="brand-mark">${esc($("#brand-mark").textContent)}</span><span class="brand-text"><b>${esc(name)}</b><small>${esc(cfg("tagline", ""))}</small></span></a>
        <p>${esc(about)}</p>
        <div class="f-social">${social.map(([ic, u]) => `<a ${linkAttrs(u)} aria-label="${ic}">${icon(ic)}</a>`).join("")}${wa ? `<a href="https://wa.me/${esc(wa)}" target="_blank" rel="noopener noreferrer" aria-label="WhatsApp">${icon("chat")}</a>` : ""}</div></div>
      <div class="f-cols">${cols.slice(0, 4).map(c => `<div class="f-col"><h4>${esc(c.title)}</h4><ul>${parseLinks(c.links).map(l => `<li><a ${linkAttrs(l.url)}>${esc(l.label)}</a></li>`).join("")}</ul></div>`).join("")}${contact}</div>
    </div>
    <div class="f-pay"><div class="container f-pay-in"><div class="f-pay-list">${pay.join("")}</div></div></div>
    <div class="f-bottom"><div class="container f-bottom-in"><span>${esc(copy)}</span><span>${cfg("footerAbout") && cfg("footerText") ? esc(cfg("footerText")) : ""}</span></div></div>`;
}

/* ---------- Cart ---------- */
function saveCart() { store.set("pn_cart", S.cart); renderBadges(); if (S.drawer) renderDrawer(); }
function addToCart(id, qty = 1, silent = false) {
  const p = S.products.find(x => String(x.id) === String(id)); if (!p) return false;
  if (p.stock <= 0) { toast("Sorry, this item is sold out.", "err"); return false; }
  const line = S.cart.find(x => String(x.p.id) === String(p.id));
  const cap = maxQty(p), have = line ? line.qty : 0;
  if (have >= cap) { toast(`Only ${cap} available in stock.`, "err"); return false; }
  const add = Math.min(qty, cap - have);
  if (line) { line.qty += add; line.p = p; } else S.cart.push({ p, qty: add });
  saveCart();
  if (!silent) toast(add < qty ? `Added ${add} — that's all we have in stock.` : "Added to your bag");
  return true;
}
function setQty(id, qty) {
  const line = S.cart.find(x => String(x.p.id) === String(id)); if (!line) return;
  const cap = maxQty(line.p);
  if (qty <= 0) S.cart = S.cart.filter(x => x !== line);
  else { if (qty > cap) { toast(`Only ${cap} available in stock.`, "err"); qty = cap; } line.qty = qty; }
  saveCart();
}
function syncCartWithCatalogue() {
  if (S.isDemo || !S.cart.length) return;
  let removed = 0, changed = 0;
  S.cart = S.cart.filter(x => {
    const live = S.products.find(p => String(p.id) === String(x.p.id));
    if (!live || live.stock <= 0) { removed++; return false; }
    if (x.qty > maxQty(live)) { x.qty = maxQty(live); changed++; }
    x.p = live; return true;
  });
  if (removed || changed) { store.set("pn_cart", S.cart); toast(removed ? "Some items in your bag are no longer available and were removed." : "We updated quantities to match current stock."); }
}
function thumb(p, cls = "") {
  const img = safeUrl(p.imageUrl);
  return img ? `<img src="${esc(img)}" alt="${esc(p.name)}" loading="lazy" data-fb="${esc(initial(p.name))}">` : `<div class="noimg">${esc(initial(p.name))}</div>`;
}
function renderDrawer() {
  const d = $("#drawer");
  if (S.drawer === "wish") {
    const items = S.products.filter(p => S.wish.includes(String(p.id)));
    d.setAttribute("aria-label", "Wishlist");
    d.innerHTML = `<div class="dr-head"><h2>Wishlist <span>${items.length} ${items.length === 1 ? "item" : "items"}</span></h2><button class="icon-btn" data-close aria-label="Close">${icon("x")}</button></div>
      <div class="dr-body">${items.length ? items.map(p => `<div class="ci"><div class="ci-img" data-quick="${esc(p.id)}" style="cursor:pointer">${thumb(p)}</div><div class="ci-main">
        <div class="ci-top"><div><div class="ci-name">${esc(p.name)}</div><div class="ci-cat">${esc(p.category || "")}</div></div><button class="ci-rm" data-wish="${esc(p.id)}" aria-label="Remove from wishlist">${icon("trash")}</button></div>
        <div class="ci-bot"><div class="ci-price"><b>${money(priceOf(p))}</b>${isSale(p) ? `<del>${money(p.price)}</del>` : ""}</div><button class="btn btn-primary" style="height:38px;padding:0 16px" data-add="${esc(p.id)}" ${p.stock <= 0 ? "disabled" : ""}>${p.stock <= 0 ? "Sold out" : "Add to bag"}</button></div></div></div>`).join("")
        : `<div class="dr-empty"><span class="ico">${icon("heart")}</span><h3>Your wishlist is empty</h3><p>Tap the heart on any product to save it for later.</p><button class="btn btn-primary" data-close>Start browsing</button></div>`}</div>`;
    return;
  }
  d.setAttribute("aria-label", "Shopping bag");
  const sub = cartSub(), n = cartCount(), thr = freeFrom();
  const ship = thr > 0 && n ? (sub >= thr ? `<div class="ship-bar">${icon("truck")} <b>You've unlocked free delivery!</b><div class="ship-track"><i style="width:100%"></i></div></div>` : `<div class="ship-bar">Add <b>${money(thr - sub)}</b> more for <b>free delivery</b><div class="ship-track"><i style="width:${Math.min(100, sub / thr * 100)}%"></i></div></div>`) : "";
  d.innerHTML = `<div class="dr-head"><h2>Shopping bag <span>${n} ${n === 1 ? "item" : "items"}</span></h2><button class="icon-btn" data-close aria-label="Close">${icon("x")}</button></div>${ship}
    <div class="dr-body">${n ? S.cart.map(x => { const p = x.p, cap = maxQty(p), price = priceOf(p); return `<div class="ci"><div class="ci-img" data-quick="${esc(p.id)}" style="cursor:pointer">${thumb(p)}</div><div class="ci-main">
      <div class="ci-top"><div><div class="ci-name">${esc(p.name)}</div><div class="ci-cat">${esc(p.category || "")}</div></div><button class="ci-rm" data-rm="${esc(p.id)}" aria-label="Remove ${esc(p.name)}">${icon("trash")}</button></div>
      <div class="ci-bot"><div class="qty"><button data-dec="${esc(p.id)}" aria-label="Decrease quantity">${icon("minus")}</button><span>${x.qty}</span><button data-inc="${esc(p.id)}" aria-label="Increase quantity" ${x.qty >= cap ? "disabled" : ""}>${icon("plus")}</button></div>
      <div class="ci-price"><b>${money(price * x.qty)}</b>${isSale(p) ? `<del>${money(p.price * x.qty)}</del>` : ""}</div></div>
      ${cap <= 5 ? `<div class="ci-warn">Only ${cap} left in stock</div>` : ""}</div></div>`; }).join("")
      : `<div class="dr-empty"><span class="ico">${icon("bag")}</span><h3>Your bag is empty</h3><p>Looks like you haven't added anything yet. Let's find something you'll love.</p><button class="btn btn-primary" data-close>Continue shopping</button></div>`}</div>
    ${n ? `<div class="dr-foot"><div class="sum-line"><span>Subtotal</span><b>${money(sub)}</b></div>
      <div class="sum-line"><span>Delivery</span><span>${thr > 0 && sub >= thr ? '<span class="free">FREE</span>' : "Calculated at checkout"}</span></div>
      <p class="dr-note">Taxes included. Final delivery charge depends on your area.</p>
      <button class="btn btn-primary btn-lg btn-block" data-checkout>Checkout ${icon("right")}</button>
      <button class="btn btn-ghost btn-block" style="margin-top:10px" data-close>Continue shopping</button>
      <div class="secure-line">${icon("lock")} Secure checkout · Cash on delivery available</div></div>` : ""}`;
}

/* ---------- Wishlist ---------- */
function toggleWish(id) {
  id = String(id);
  S.wish = S.wish.includes(id) ? S.wish.filter(x => x !== id) : [...S.wish, id];
  store.set("pn_wish", S.wish); renderBadges();
  $$(`[data-wish="${CSS.escape(id)}"]`).forEach(b => { const on_ = S.wish.includes(id); b.classList.toggle("on", on_); b.setAttribute("aria-pressed", on_); });
  if (S.drawer === "wish") renderDrawer();
}

/* ---------- Quick view ---------- */
function openQuickView(id) {
  const p = S.products.find(x => String(x.id) === String(id)); if (!p) return;
  const price = priceOf(p), out = p.stock <= 0, w = S.wish.includes(String(p.id));
  openModal(`<div class="qv"><div class="qv-img">${thumb(p)}</div><div class="qv-info">
    <span class="pcard-cat">${esc(p.category || "")}</span><h2>${esc(p.name)}</h2>
    <div class="qv-price">${isSale(p) ? `<b class="sale">${money(price)}</b><del>${money(p.price)}</del><span class="badge">-${Math.round(p.discountPercent)}%</span>` : `<b>${money(price)}</b>`}</div>
    <div class="qv-stock ${out ? "out" : p.stock <= 5 ? "low" : "ok"}">${out ? "Out of stock" : p.stock <= 5 ? `Only ${p.stock} left — order soon` : "In stock"}</div>
    <p class="qv-desc">${esc(p.description || "A thoughtful find for your everyday.")}</p>
    <div class="qv-actions"><div class="qty" id="qv-qty" data-max="${maxQty(p)}"><button data-qv="-1" aria-label="Decrease">${icon("minus")}</button><span id="qv-n">1</span><button data-qv="1" aria-label="Increase">${icon("plus")}</button></div>
      <button class="btn btn-primary btn-lg" data-qv-add="${esc(p.id)}" ${out ? "disabled" : ""}>${out ? "Sold out" : `${icon("bag")} Add to bag`}</button>
      ${wishOn() ? `<button class="wish-btn ${w ? "on" : ""}" data-wish="${esc(p.id)}" aria-label="Wishlist">${icon("heart")}</button>` : ""}</div>
    <div class="qv-meta"><span>${icon("truck")} Delivery across Bangladesh</span><span>${icon("cash")} Cash on delivery available</span><span>${icon("shield")} Secure checkout</span></div></div></div>`, "wide");
}

/* ---------- Auth ---------- */
const pwScore = pw => { let s = 0; if (pw.length >= 8) s++; if (pw.length >= 12) s++; if (/[a-z]/.test(pw) && /[A-Z]/.test(pw)) s++; if (/\d/.test(pw) && /[^A-Za-z0-9]/.test(pw)) s++; return /[A-Za-z]/.test(pw) && /\d/.test(pw) && pw.length >= 8 ? Math.max(s, 1) : Math.min(s, 1); };
const pwOk = pw => pw.length >= 8 && pw.length <= 72 && /[A-Za-z]/.test(pw) && /\d/.test(pw);
const pwField = (name, label, ac, ph = "") => `<div class="field" data-f="${name}"><label for="a-${name}">${label} <i>*</i></label><div class="pw-wrap"><input class="inp" id="a-${name}" name="${name}" type="password" autocomplete="${ac}" placeholder="${ph}" required><button type="button" class="pw-eye" data-eye aria-label="Show password">${icon("eye")}</button></div><span class="msg"></span></div>`;
const txtField = (name, label, type, ac, ph, req = true, extra = "") => `<div class="field" data-f="${name}"><label for="a-${name}">${label} ${req ? "<i>*</i>" : '<span style="color:var(--muted);font-weight:500">(optional)</span>'}</label><input class="inp" id="a-${name}" name="${name}" type="${type}" autocomplete="${ac}" placeholder="${ph}" ${req ? "required" : ""} ${extra}><span class="msg"></span></div>`;
function authVisual(mode) {
  const raw = safeUrl(cfg(mode === "register" ? "authRegisterImageUrl" : "authLoginImageUrl")), has = /^https?:/i.test(raw);
  return `<aside class="auth-visual ${has ? "has-img" : ""}" ${has ? `style="background-image:url('${esc(raw).replace(/'/g, "%27")}')"` : ""}>
    <span class="brand-mark">${esc($("#brand-mark").textContent)}</span>
    <h2>${mode === "register" ? "Join " + esc(storeName()) : "Welcome back"}</h2>
    <ul><li>${icon("check")} Track every order in one place</li><li>${icon("check")} Save favourites to your wishlist</li><li>${icon("check")} Faster, easier checkout</li></ul></aside>`;
}
function openAuth(mode = "login", ctx = null) {
  S.authCtx = { mode, stage: "form", ctx, data: null, cooldown: 0 };
  renderAuth();
}
function renderAuth() {
  const A = S.authCtx, reg = A.mode === "register";
  let body;
  if (A.stage === "otp") {
    body = `<div class="otp-box"><span class="ico">${icon("mail")}</span><h2>Check your inbox</h2><p class="sub" style="margin:0">We sent a 6-digit code to <b>${esc(A.data.email)}</b>. It expires in 10 minutes.</p></div>
      <form class="auth-form" id="otp-form" novalidate><div id="auth-alert"></div>
        <div class="field"><input class="inp otp-input" name="otp" inputmode="numeric" pattern="[0-9]{6}" maxlength="6" autocomplete="one-time-code" placeholder="000000" required aria-label="Verification code"><span class="msg"></span></div>
        <button class="btn btn-primary btn-lg btn-block" type="submit">Verify &amp; create account</button>
        <div class="auth-foot"><button type="button" class="link-btn" id="resend" ${A.cooldown > 0 ? "disabled" : ""}>${A.cooldown > 0 ? `Resend code in ${A.cooldown}s` : "Resend code"}</button> · <button type="button" class="link-btn" data-auth-back>Change details</button></div></form>`;
  } else if (reg) {
    const d = A.data || {};
    body = `<div><h2>Create your account</h2><p class="sub">It only takes a minute.</p></div><div id="google-signin"></div><div class="divider">or sign up with email</div>
      <form class="auth-form" id="reg-form" novalidate><div id="auth-alert"></div>
        <div class="grid-2">${txtField("firstName", "First name", "text", "given-name", "Rahim")}${txtField("lastName", "Last name", "text", "family-name", "Uddin")}</div>
        ${txtField("email", "Email address", "email", "email", "you@example.com")}
        ${txtField("phone", "Mobile number", "tel", "tel", "01XXXXXXXXX")}
        <div class="grid-2"><div class="field" data-f="gender"><label for="a-gender">Gender <span style="color:var(--muted);font-weight:500">(optional)</span></label><select class="inp" id="a-gender" name="gender"><option value="">Prefer not to say</option><option value="FEMALE">Female</option><option value="MALE">Male</option><option value="OTHER">Other</option></select></div>
          ${txtField("dateOfBirth", "Date of birth", "date", "bday", "", false, `max="${new Date().toISOString().slice(0, 10)}"`)}</div>
        <div>${pwField("password", "Password", "new-password", "At least 8 characters")}<div class="pw-meter" id="pw-meter" data-s="0"><i></i><i></i><i></i><i></i></div><div class="pw-label" id="pw-label">Use 8+ characters with letters and numbers.</div></div>
        ${pwField("confirmPassword", "Confirm password", "new-password", "Re-enter your password")}
        <div class="field" data-f="acceptTerms"><label class="check"><input type="checkbox" name="acceptTerms"> <span>I agree to the terms of service and privacy policy.</span></label><span class="msg"></span></div>
        <button class="btn btn-primary btn-lg btn-block" type="submit">Create account</button></form>
      <div class="auth-foot">Already have an account? <button class="link-btn" data-auth-mode="login">Log in</button></div>`;
  } else {
    body = `<div><h2>Log in</h2><p class="sub">Welcome back — sign in to continue.</p></div><div id="google-signin"></div><div class="divider">or continue with email</div>
      <form class="auth-form" id="login-form" novalidate><div id="auth-alert"></div>
        ${txtField("email", "Email address", "email", "email", "you@example.com")}${pwField("password", "Password", "current-password", "Your password")}
        <button class="btn btn-primary btn-lg btn-block" type="submit">Log in</button></form>
      <div class="auth-foot">New to ${esc(storeName())}? <button class="link-btn" data-auth-mode="register">Create an account</button></div>`;
  }
  openModal(`<div class="auth-grid">${authVisual(A.mode)}<section class="auth-panel">
    ${A.stage === "form" ? `<div class="auth-tabs" role="tablist"><button class="${reg ? "" : "on"}" data-auth-mode="login">Log in</button><button class="${reg ? "on" : ""}" data-auth-mode="register">Sign up</button></div>` : ""}
    ${body}<div class="auth-foot">${icon("lock")} Your details are encrypted and kept secure</div></section></div>`, "auth");
  if (A.stage === "form") setupGoogle();
  if (A.stage === "otp") $("#otp-form [name=otp]")?.focus();
}
function setErr(form, name, msg) {
  const f = $(`[data-f="${name}"]`, form); if (!f) return;
  f.classList.toggle("err", !!msg); const m = $(".msg", f); if (m) m.textContent = msg || "";
}
function formAlert(el, msg, type = "error") { if (el) el.innerHTML = msg ? `<div class="alert ${type}" role="alert">${icon(type === "error" ? "alert" : "check-circle")}<span>${esc(msg)}</span></div>` : ""; }
function busy(btn, on_, label) {
  if (!btn) return;
  if (on_) { btn.dataset.l = btn.innerHTML; btn.disabled = true; btn.innerHTML = `<span class="spin"></span> ${label || "Please wait…"}`; }
  else { btn.disabled = false; if (btn.dataset.l) btn.innerHTML = btn.dataset.l; }
}
async function finishAuth(r, msg) {
  setSession(r.token, r.user);
  const ctx = S.authCtx?.ctx; S.authCtx = null; closeModal();
  if (r.user.role === "ADMIN") { location.href = "/admin.html"; return; }
  toast(msg);
  if (ctx === "checkout") openCheckout();
}
async function submitLogin(form) {
  const btn = $("button[type=submit]", form), d = Object.fromEntries(new FormData(form));
  let bad = false;
  setErr(form, "email", ""); setErr(form, "password", "");
  if (!/^\S+@\S+\.\S+$/.test(d.email.trim())) { setErr(form, "email", "Enter a valid email address."); bad = true; }
  if (!d.password) { setErr(form, "password", "Enter your password."); bad = true; }
  if (bad) return;
  busy(btn, true, "Logging in…"); formAlert($("#auth-alert"), "");
  try { finishAuth(await api("/auth/login", { method: "POST", body: JSON.stringify({ email: d.email.trim(), password: d.password }) }), "Welcome back!"); }
  catch (e) { formAlert($("#auth-alert"), e.status === 401 ? "Email or password is incorrect." : e.message); busy(btn, false); }
}
function validateReg(d) {
  const e = {};
  if (d.firstName.trim().length < 2) e.firstName = "Enter your first name.";
  if (d.lastName.trim().length < 1) e.lastName = "Enter your last name.";
  if (!/^\S+@\S+\.\S+$/.test(d.email.trim())) e.email = "Enter a valid email address.";
  if (!normPhone(d.phone)) e.phone = "Enter a valid mobile number, e.g. 01XXXXXXXXX.";
  if (d.dateOfBirth && (isNaN(Date.parse(d.dateOfBirth)) || new Date(d.dateOfBirth) > new Date())) e.dateOfBirth = "Enter a valid date of birth.";
  if (!pwOk(d.password)) e.password = "Use 8+ characters with at least one letter and one number.";
  if (d.confirmPassword !== d.password || !d.confirmPassword) e.confirmPassword = "Passwords don't match.";
  if (!d.acceptTerms) e.acceptTerms = "Please accept the terms to continue.";
  return e;
}
async function submitRegister(form) {
  const d = Object.fromEntries(new FormData(form)); d.acceptTerms = !!form.elements.acceptTerms.checked;
  const errs = validateReg(d);
  ["firstName", "lastName", "email", "phone", "dateOfBirth", "password", "confirmPassword", "acceptTerms"].forEach(k => setErr(form, k, errs[k] || ""));
  const first = Object.keys(errs)[0]; if (first) { form.elements[first]?.focus(); return; }
  await sendOtp(form, d);
}
async function sendOtp(form, d) {
  const btn = $("button[type=submit]", form || document); busy(btn, true, "Sending code…"); formAlert($("#auth-alert"), "");
  const payload = { firstName: d.firstName.trim(), lastName: d.lastName.trim(), email: d.email.trim(), phone: normPhone(d.phone), gender: d.gender || "", dateOfBirth: d.dateOfBirth || "", password: d.password, confirmPassword: d.confirmPassword, acceptTerms: true };
  try {
    await api("/auth/register/request-otp", { method: "POST", body: JSON.stringify(payload) });
    S.authCtx.data = { ...d, email: payload.email }; S.authCtx.stage = "otp"; S.authCtx.cooldown = 60; renderAuth(); startCooldown();
    toast("Verification code sent. Check your email.");
  } catch (e) { busy(btn, false); formAlert($("#auth-alert"), e.message); }
}
let cdTimer;
function startCooldown() {
  clearInterval(cdTimer);
  cdTimer = setInterval(() => {
    const A = S.authCtx; if (!A || A.stage !== "otp") { clearInterval(cdTimer); return; }
    A.cooldown = Math.max(0, A.cooldown - 1);
    const b = $("#resend"); if (b) { b.disabled = A.cooldown > 0; b.textContent = A.cooldown > 0 ? `Resend code in ${A.cooldown}s` : "Resend code"; }
    if (!A.cooldown) clearInterval(cdTimer);
  }, 1000);
}
async function submitOtp(form) {
  const otp = String(form.elements.otp.value || "").trim(), btn = $("button[type=submit]", form);
  if (!/^\d{6}$/.test(otp)) { setErr(form, "otp", ""); formAlert($("#auth-alert"), "Enter the 6-digit code from your email."); return; }
  busy(btn, true, "Verifying…"); formAlert($("#auth-alert"), "");
  try { finishAuth(await api("/auth/register/verify-otp", { method: "POST", body: JSON.stringify({ email: S.authCtx.data.email, otp }) }), "Email verified! Your account is ready."); }
  catch (e) { formAlert($("#auth-alert"), e.message); busy(btn, false); }
}
async function setupGoogle() {
  try {
    const c = await api("/config/public"); const host = $("#google-signin");
    if (!c.googleClientId || !window.google?.accounts?.id || !host) return;
    window.google.accounts.id.initialize({ client_id: c.googleClientId, callback: async resp => {
      try { finishAuth(await api("/auth/google", { method: "POST", body: JSON.stringify({ idToken: resp.credential }) }), "Signed in with Google"); }
      catch (e) { toast(e.message, "err"); } } });
    window.google.accounts.id.renderButton(host, { theme: "outline", size: "large", text: "continue_with", shape: "pill", width: 300 });
  } catch { /* Google sign-in stays hidden until configured */ }
}
window.addEventListener("google-identity-loaded", () => { if (S.authCtx?.stage === "form") setupGoogle(); });

/* ---------- AI assistant ---------- */
function openAi() {
  if (!aiOn()) return;
  openModal(`<div class="ai-head"><span class="ai-ico">${icon("zap")}</span><div><h2>Shopping assistant</h2><p>Tell me your budget, the occasion or the kind of thing you need.</p></div></div>
    <div class="ai-chips"><button data-ai="Suggest a thoughtful gift under ৳1500">Gift under ৳1500</button><button data-ai="Find everyday fashion under ৳1200">Everyday fashion</button><button data-ai="Recommend useful tech accessories">Useful tech</button></div>
    <div class="ai-messages" id="ai-messages"><div class="ai-b bot"><b>Hi! 👋</b><br>I can help you discover products from our catalogue. What are you looking for?</div></div>
    <form class="ai-form" id="ai-form"><input class="inp" name="message" required maxlength="500" placeholder="e.g. Birthday gift under ৳2000" autocomplete="off"><button class="btn btn-primary" type="submit">Ask</button></form>
    <p class="ai-foot">Suggestions are based on the current catalogue. Always check price and stock before ordering.</p>`);
}
async function askAi(message) {
  const box = $("#ai-messages"); if (!box || !message.trim()) return;
  box.insertAdjacentHTML("beforeend", `<div class="ai-b me">${esc(message)}</div><div class="ai-b bot" id="ai-wait"><span class="dots"><i></i><i></i><i></i></span></div>`);
  box.scrollTop = box.scrollHeight;
  let answer = "", items = [];
  try { answer = String((await api("/ai/ask", { method: "POST", body: JSON.stringify({ message }) })).answer || "").trim(); }
  catch {
    const m = message.match(/(?:under|below|within|budget|৳|tk|taka)\s*৳?\s*(\d[\d,]*)/i), budget = m ? Number(m[1].replace(/,/g, "")) : null, l = message.toLowerCase();
    const cat = /fashion|shirt|cloth/.test(l) ? DEFAULT_CATS[0] : /skin|beauty/.test(l) ? DEFAULT_CATS[1] : /tech|earbud|charger|gadget/.test(l) ? DEFAULT_CATS[2] : "";
    items = S.products.filter(p => (!budget || priceOf(p) <= budget) && (!cat || p.category === cat) && p.stock > 0).slice(0, 4);
    answer = items.length ? "Here are a few catalogue matches. The live AI service isn't available right now, so these are simple catalogue-based suggestions." : "I couldn't reach the AI service and found no clear match. Try something like “gift under ৳1500”.";
  }
  $("#ai-wait")?.remove();
  box.insertAdjacentHTML("beforeend", `<div class="ai-b bot"><div>${esc(answer).replace(/\n/g, "<br>")}</div>${items.length ? `<div class="ai-prods">${items.map(p => `<div class="ai-prod">${thumb(p)}<div><b>${esc(p.name)}</b><span>${money(priceOf(p))}</span></div><button data-add="${esc(p.id)}" aria-label="Add to bag">${icon("plus")}</button></div>`).join("")}</div>` : ""}</div>`);
  box.scrollTop = box.scrollHeight;
}

/* ---------- Checkout ---------- */
function openCheckout() {
  if (!S.cart.length) { toast("Your bag is empty."); return; }
  if (S.isDemo) { toast("The product catalogue couldn't be loaded, so orders are unavailable right now. Please refresh the page.", "err"); return; }
  if (on("maintenance", false) && S.user?.role !== "ADMIN") { toast("The store is temporarily under maintenance. Please try again soon.", "err"); return; }
  if (!S.token) { closeDrawer(); openAuth("login", "checkout"); return; }
  closeDrawer(); closeModal(); renderCheckout(); $("#checkout-host").hidden = false; $("#checkout-host").scrollTop = 0; syncLock();
}
function closeCheckout() { $("#checkout-host").hidden = true; $("#checkout-host").innerHTML = ""; syncLock(); }
function renderCheckout() {
  const u = S.user || {}, saved = store.get("pn_ship", {});
  const codOn = on("codEnabled"), onlineOn = on("onlinePaymentEnabled");
  const noPay = !codOn && !onlineOn;
  const field = (name, label, type, val, ph, ac, extra = "") => `<div class="field ${extra.includes("wide") ? "wide" : ""}" data-f="${name}"><label for="c-${name}">${label} <i>*</i></label><input class="inp" id="c-${name}" name="${name}" type="${type}" value="${esc(val || "")}" placeholder="${ph}" autocomplete="${ac}" required><span class="msg"></span></div>`;
  $("#checkout-host").innerHTML = `
    <div class="co-bar"><div class="container co-bar-in"><button class="link-btn" data-co-back style="display:flex;align-items:center;gap:6px">${icon("left")} Back to bag</button>
      <div class="co-steps"><span>Bag</span>${icon("right")}<span class="on">Checkout</span>${icon("right")}<span>Done</span></div>
      <div class="co-secure">${icon("lock")} Secure checkout</div></div></div>
    <div class="container co-wrap" id="co-wrap">
      <form id="co-form" novalidate>
        <div id="co-alert"></div>
        <div class="co-card"><h2><span class="n">1</span> Contact &amp; delivery</h2><div class="co-fields">
          ${field("customerName", "Full name", "text", saved.name || u.name, "Your full name", "name", "wide")}
          ${field("email", "Email address", "email", u.email, "you@example.com", "email")}
          ${field("phone", "Mobile number", "tel", saved.phone || u.phone, "01XXXXXXXXX", "tel")}
          <div class="field" data-f="deliveryArea"><label for="c-area">Delivery area <i>*</i></label><select class="inp" id="c-area" name="deliveryArea"><option value="DHAKA" ${saved.area === "DHAKA" || !saved.area ? "selected" : ""}>Dhaka city</option><option value="OUTSIDE_DHAKA" ${saved.area === "OUTSIDE_DHAKA" ? "selected" : ""}>Outside Dhaka</option></select></div>
          <div class="field wide" data-f="address"><label for="c-address">Full delivery address <i>*</i></label><textarea class="inp" id="c-address" name="address" rows="3" placeholder="House, road, area, district" autocomplete="street-address" required>${esc(saved.address || "")}</textarea><span class="msg"></span></div>
        </div></div>
        <div class="co-card"><h2><span class="n">2</span> Payment method</h2>
          ${noPay ? `<div class="alert error">${icon("alert")}<span>No payment method is currently available. Please contact support.</span></div>` : `<div class="pay-opts">
            <label class="pay-opt"><input type="radio" name="paymentMethod" value="COD" ${codOn ? "checked" : "disabled"}><span class="ico">${icon("cash")}</span><span><b>Cash on delivery</b><small>Pay in cash when your parcel arrives</small></span></label>
            <label class="pay-opt"><input type="radio" name="paymentMethod" value="ONLINE" ${onlineOn ? (codOn ? "" : "checked") : "disabled"}><span class="ico">${icon("card")}</span><span><b>Pay online</b><small>Cards &amp; mobile banking via SSLCOMMERZ</small></span></label></div>`}
        </div>
        <button class="btn btn-primary btn-lg btn-block" type="submit" id="place-order" ${noPay ? "disabled" : ""}><span id="place-label">Place order</span></button>
        <p class="co-terms">By placing your order you agree to our order and delivery policy.</p>
      </form>
      <aside class="co-sum" id="co-sum"></aside>
    </div>`;
  renderSummary();
}
function currentArea() { return $("#co-form [name=deliveryArea]")?.value || "DHAKA"; }
function renderSummary() {
  const sub = cartSub(), fee = feeFor(currentArea(), sub), total = sub + fee, n = cartCount();
  const method = $("#co-form [name=paymentMethod]:checked")?.value;
  const lbl = $("#place-label"); if (lbl && !S.placing) lbl.textContent = method === "ONLINE" ? `Continue to payment · ${money(total)}` : `Place order · ${money(total)}`;
  $("#co-sum").innerHTML = `<h3>Order summary <span>${n} ${n === 1 ? "item" : "items"}</span></h3>
    <div class="co-items">${S.cart.map(x => `<div class="co-item"><div class="im">${thumb(x.p)}<i>${x.qty}</i></div><div><b>${esc(x.p.name)}</b><small>${esc(x.p.category || "")}</small></div><span class="pr">${money(priceOf(x.p) * x.qty)}</span></div>`).join("")}</div>
    <div class="sum-line"><span>Subtotal</span><span>${money(sub)}</span></div>
    <div class="sum-line"><span>Delivery</span>${fee === 0 ? '<span class="free">FREE</span>' : `<span>${money(fee)}</span>`}</div>
    <div class="sum-line total"><span>Total</span><span>${money(total)}</span></div>
    <div class="co-trust"><span>${icon("shield")} Your details are sent securely</span><span>${icon("truck")} Delivery within Bangladesh</span><span>${icon("return")} We're here if anything goes wrong</span></div>`;
}
async function placeOrder(form) {
  if (S.placing) return;
  const d = Object.fromEntries(new FormData(form)), errs = {};
  if (d.customerName.trim().length < 2) errs.customerName = "Enter your full name.";
  if (!/^\S+@\S+\.\S+$/.test((d.email || "").trim())) errs.email = "Enter a valid email address.";
  const phone = normPhone(d.phone); if (!phone) errs.phone = "Enter a valid mobile number, e.g. 01XXXXXXXXX.";
  if ((d.address || "").trim().length < 8) errs.address = "Enter your full delivery address (at least 8 characters).";
  ["customerName", "email", "phone", "address"].forEach(k => setErr(form, k, errs[k] || ""));
  const alertBox = $("#co-alert"); formAlert(alertBox, "");
  if (!d.paymentMethod) { formAlert(alertBox, "Please choose a payment method."); return; }
  const firstErr = Object.keys(errs)[0];
  if (firstErr) { form.elements[firstErr]?.focus(); formAlert(alertBox, "Please fix the highlighted fields and try again."); alertBox.scrollIntoView({ behavior: "smooth", block: "center" }); return; }
  S.placing = true;
  const btn = $("#place-order"); btn.disabled = true; btn.innerHTML = `<span class="spin"></span> Placing your order…`;
  const reset = () => { S.placing = false; btn.disabled = false; btn.innerHTML = `<span id="place-label">Place order</span>`; renderSummary(); };
  let order;
  try {
    order = await api("/orders", { method: "POST", body: JSON.stringify({
      items: S.cart.map(x => ({ productId: String(x.p.id), quantity: x.qty })),
      customerName: d.customerName.trim(), email: d.email.trim(), phone, address: d.address.trim(), deliveryArea: d.deliveryArea, paymentMethod: d.paymentMethod }) });
  } catch (e) {
    reset();
    if (e.status === 401) { closeCheckout(); openAuth("login", "checkout"); return; }
    formAlert(alertBox, e.message); alertBox.scrollIntoView({ behavior: "smooth", block: "center" });
    if (e.status === 409 || e.status === 400) refreshCatalogue();
    return;
  }
  store.set("pn_ship", { name: d.customerName.trim(), phone, address: d.address.trim(), area: d.deliveryArea });
  const items = S.cart.map(x => ({ ...x }));
  S.cart = []; saveCart(); S.placing = false;
  if (d.paymentMethod === "ONLINE") {
    try {
      const pay = await api(`/payments/${encodeURIComponent(order.id)}/sslcommerz/initiate`, { method: "POST", body: "{}" });
      if (!pay.paymentUrl) throw new Error("The payment gateway did not return a payment link.");
      location.href = pay.paymentUrl; return;
    } catch (e) { renderSuccess(order, items, d, "Your order was saved, but we couldn't start the online payment: " + e.message + " You can retry from My orders."); return; }
  }
  renderSuccess(order, items, d);
}
function renderSuccess(order, items, d, warn = "") {
  $("#checkout-host").scrollTop = 0;
  $("#checkout-host").innerHTML = `<div class="co-bar"><div class="container co-bar-in"><a class="brand" href="/"><span class="brand-mark">${esc($("#brand-mark").textContent)}</span><span class="brand-text"><b>${esc(storeName())}</b></span></a><div class="co-steps"><span>Bag</span>${icon("right")}<span>Checkout</span>${icon("right")}<span class="on">Done</span></div><span></span></div></div>
    <div class="container"><div class="co-success"><div class="tick">${icon("check")}</div><h1>Thank you, ${esc((d.customerName || "").split(" ")[0] || "friend")}!</h1>
      <p>Your order has been placed. We'll email a confirmation to <b>${esc(d.email)}</b>.</p>
      ${warn ? `<div class="alert info" style="margin-top:18px;text-align:left">${icon("alert")}<span>${esc(warn)}</span></div>` : ""}
      <div class="co-receipt"><div><span>Order number</span><b>${esc(order.orderNumber || order.id)}</b></div><div><span>Items</span><b>${items.reduce((n, x) => n + x.qty, 0)}</b></div>
        <div><span>Delivery</span><b>${order.deliveryCharge ? money(order.deliveryCharge) : "FREE"}</b></div><div><span>Payment</span><b>${order.paymentMethod === "ONLINE" ? "Online payment (pending)" : "Cash on delivery"}</b></div>
        <div><span>Total</span><b>${money(order.total)}</b></div><div><span>Deliver to</span><b style="text-align:right;max-width:60%">${esc(d.address)}</b></div></div>
      <div class="co-actions"><a class="btn btn-primary btn-lg" href="/customer.html#my-orders">View my orders</a><button class="btn btn-ghost btn-lg" data-co-done>Continue shopping</button></div></div></div>`;
}
async function refreshCatalogue() {
  try { const p = await api("/products"); if (Array.isArray(p)) { S.products = p; syncCartWithCatalogue(); renderBadges(); refreshShop(); } } catch {}
}

/* ---------- Suggestions ---------- */
function showSuggest(q) {
  const box = $("#suggest"); q = q.trim().toLowerCase();
  if (q.length < 2) { box.hidden = true; return; }
  const hits = S.products.filter(p => `${p.name} ${p.category}`.toLowerCase().includes(q)).slice(0, 6);
  if (!hits.length) { box.innerHTML = `<div style="padding:16px;color:var(--muted);font-size:14px">No matches for “${esc(q)}”</div>`; box.hidden = false; return; }
  box.innerHTML = hits.map(p => `<button type="button" data-quick="${esc(p.id)}" data-hide-suggest>${thumb(p)}<span><b>${esc(p.name)}</b><small>${esc(p.category || "")}</small></span><em>${money(priceOf(p))}</em></button>`).join("") +
    `<button type="button" class="sg-all" data-search-all>See all results for “${esc(q)}”</button>`;
  box.hidden = false;
}

/* ---------- Routing helpers ---------- */
function routeHash(h) {
  if (h === "#cart") { openDrawer("cart"); return true; }
  if (h === "#wishlist") { if (wishOn()) openDrawer("wish"); return true; }
  if (h === "#account" || h === "#login") { S.user ? toggleAcctMenu() : openAuth("login"); return true; }
  if (h === "#ai") { openAi(); return true; }
  if (h === "#deals") {
    const d = $("#deals-section");
    if (d && !d.hidden) d.scrollIntoView({ behavior: "smooth", block: "start" });
    else { S.f.deals = true; S.f.cat = ""; S.shown = 12; refreshShop(); $("#shop").scrollIntoView({ behavior: "smooth" }); }
    return true;
  }
  if (h === "#shop") { $("#shop").scrollIntoView({ behavior: "smooth", block: "start" }); return true; }
  if (h === "#categories") { ($("#categories-section").innerHTML ? $("#categories-section") : $("#shop")).scrollIntoView({ behavior: "smooth" }); return true; }
  if (h.startsWith("#cat:")) { setCategory(decodeURIComponent(h.slice(5))); return true; }
  return false;
}

/* ---------- Events ---------- */
document.addEventListener("error", e => {
  const t = e.target; if (!t || t.tagName !== "IMG") return;
  if (t.hasAttribute("data-hide")) { t.style.display = "none"; return; }
  if (t.dataset.fb) { const d = document.createElement("div"); d.className = "noimg"; d.textContent = t.dataset.fb; t.replaceWith(d); }
}, true);

document.addEventListener("click", e => {
  const t = e.target, q = s => t.closest(s); let el;
  if (!q(".search")) $("#suggest").hidden = true;
  if (!q(".acct-wrap")) closeAcctMenu();
  if (e.target === $("#modal-host")) { closeModal(); return; }
  if ((el = q("a[href^='#']"))) { if (routeHash(el.getAttribute("href"))) { e.preventDefault(); $("#cat-nav").classList.remove("open"); return; } }
  if (q("[data-wish]")) { e.preventDefault(); e.stopPropagation(); toggleWish(q("[data-wish]").dataset.wish); return; }
  if ((el = q("[data-tryon]"))) { e.preventDefault(); e.stopPropagation(); openTryOn(el.dataset.tryon); return; }
  if ((el = q("[data-add]"))) { addToCart(el.dataset.add); return; }
  if ((el = q("[data-hide-suggest]"))) { $("#suggest").hidden = true; }
  if ((el = q("[data-quick]"))) { openQuickView(el.dataset.quick); return; }
  if ((el = q("[data-search-all]"))) { $("#suggest").hidden = true; $("#search").requestSubmit(); return; }
  if ((el = q("[data-rm]"))) { setQty(el.dataset.rm, 0); return; }
  if ((el = q("[data-dec]"))) { const l = S.cart.find(x => String(x.p.id) === el.dataset.dec); if (l) setQty(l.p.id, l.qty - 1); return; }
  if ((el = q("[data-inc]"))) { const l = S.cart.find(x => String(x.p.id) === el.dataset.inc); if (l) setQty(l.p.id, l.qty + 1); return; }
  if (q("[data-checkout]")) { openCheckout(); return; }
  if (q("[data-co-back]")) { closeCheckout(); openDrawer("cart"); return; }
  if (q("[data-co-done]")) { closeCheckout(); window.scrollTo({ top: 0, behavior: "smooth" }); return; }
  if ((el = q("[data-open]"))) { const k = el.dataset.open; k === "ai" ? openAi() : openDrawer(k === "wish" ? "wish" : "cart"); return; }
  if (q("[data-logout]")) { clearSession(); closeAcctMenu(); toast("You've been logged out."); return; }
  if (q("[data-ad-close]")) { sessionStorage.setItem("pn_ad_x", "1"); renderAdStrip(); renderAdBand(); return; }
  if (q("[data-close]")) { closeDrawer(); closeModal(); return; }
  if (q("#scrim")) { closeDrawer(); closeModal(); return; }
  if (q("#menu-btn")) { $("#cat-nav").classList.toggle("open"); return; }
  if (q("#account")) { S.user ? toggleAcctMenu() : openAuth("login"); return; }
  if (q("#wishlist")) { openDrawer("wish"); return; }
  if (q("#cart")) { openDrawer("cart"); return; }
  if ((el = q("[data-cat]"))) { $("#cat-nav").classList.remove("open"); setCategory(el.dataset.cat); return; }
  if ((el = q("[data-slide]"))) { goSlide(Number(el.dataset.slide)); return; }
  if ((el = q("[data-car]"))) { goSlide(S.slide + Number(el.dataset.car)); return; }
  if ((el = q("[data-scroll]"))) { const s = $("#deals-scroller"); s?.scrollBy({ left: Number(el.dataset.scroll) * s.clientWidth * .8, behavior: "smooth" }); return; }
  if (q("[data-more]")) { S.shown += 12; renderGrid(); return; }
  if ((el = q("[data-chip]"))) {
    const k = el.dataset.chip, f = S.f;
    if (k === "cat") f.cat = ""; if (k === "q") { f.q = ""; $("#query").value = ""; } if (k === "price") { f.min = ""; f.max = ""; } if (k === "inStock") f.inStock = false; if (k === "deals") f.deals = false;
    S.shown = 12; refreshShop(); return;
  }
  if (q("[data-clear-filters]")) { S.f = { ...S.f, cat: "", q: "", min: "", max: "", inStock: false, deals: false }; $("#query").value = ""; S.shown = 12; refreshShop(); return; }
  if (q("[data-open-filters]")) { $("#filters").classList.add("open"); $("#scrim").hidden = false; syncLock(); return; }
  if (q("[data-close-filters]")) { closeFilters(); if (!S.drawer) $("#scrim").hidden = true; syncLock(); $("#shop").scrollIntoView({ behavior: "smooth" }); return; }
  if ((el = q("[data-eye]"))) { const inp = el.parentElement.querySelector("input"); const show = inp.type === "password"; inp.type = show ? "text" : "password"; el.innerHTML = icon(show ? "eye-off" : "eye"); el.setAttribute("aria-label", show ? "Hide password" : "Show password"); return; }
  if ((el = q("[data-auth-mode]"))) { openAuth(el.dataset.authMode, S.authCtx?.ctx); return; }
  if (q("[data-auth-back]")) { const c = S.authCtx; S.authCtx = { ...c, stage: "form" }; renderAuth(); return; }
  if (q("#resend")) { const A = S.authCtx; if (A?.data && A.cooldown <= 0) { sendOtp(q("#resend"), A.data); } return; }
  if ((el = q("[data-ai]"))) { askAi(el.dataset.ai); return; }
  if ((el = q("[data-qv]"))) { const box = $("#qv-qty"), n = $("#qv-n"), max = Number(box.dataset.max) || 1; n.textContent = Math.max(1, Math.min(max, Number(n.textContent) + Number(el.dataset.qv))); return; }
  if ((el = q("[data-qv-add]"))) { const n = Number($("#qv-n").textContent) || 1; if (addToCart(el.dataset.qvAdd, n, true)) { closeModal(); openDrawer("cart"); } return; }
});
document.addEventListener("submit", e => {
  const f = e.target; e.preventDefault();
  if (f.id === "search") {
    S.f.q = $("#query").value.trim(); S.f.cat = $("#search-cat").value || S.f.cat; S.f.deals = false; S.shown = 12; $("#suggest").hidden = true; refreshShop();
    $("#shop").scrollIntoView({ behavior: "smooth", block: "start" });
  } else if (f.id === "login-form") submitLogin(f);
  else if (f.id === "reg-form") submitRegister(f);
  else if (f.id === "otp-form") submitOtp(f);
  else if (f.id === "co-form") placeOrder(f);
  else if (f.id === "ai-form") { const m = String(new FormData(f).get("message") || "").trim(); if (m) { f.reset(); askAi(m); } }
});
document.addEventListener("input", e => {
  const t = e.target;
  if (t.id === "query") showSuggest(t.value);
  if (t.closest(".field.err")) { t.closest(".field").classList.remove("err"); const m = $(".msg", t.closest(".field")); if (m) m.textContent = ""; }
  if (t.name === "password" && t.form?.id === "reg-form") {
    const s = pwScore(t.value); $("#pw-meter").dataset.s = t.value ? s : 0;
    $("#pw-label").textContent = t.value ? ["Too weak", "Weak", "Okay", "Good", "Strong"][s] + (pwOk(t.value) ? "" : " — needs 8+ characters, a letter and a number") : "Use 8+ characters with letters and numbers.";
  }
  if (t.id === "f-min" || t.id === "f-max") applyPrice();
});
const applyPrice = debounce(() => { S.f.min = $("#f-min").value; S.f.max = $("#f-max").value; S.shown = 12; renderGrid(); }, 450);
document.addEventListener("change", e => {
  const t = e.target;
  if (t.id === "sort") { S.f.sort = t.value; renderGrid(); }
  if (t.id === "f-stock") { S.f.inStock = t.checked; S.shown = 12; renderGrid(); }
  if (t.id === "f-deals") { S.f.deals = t.checked; S.shown = 12; renderGrid(); }
  if (t.name === "deliveryArea" || t.name === "paymentMethod") { if ($("#co-sum")) renderSummary(); }
  if (t.id === "search-cat") { S.f.cat = t.value; }
});
document.addEventListener("submit", e => {
  if (e.target && e.target.id === "tryon-form") {
    e.preventDefault();
    submitTryOn(e.target);
  }
});
document.addEventListener("keydown", e => {
  if (e.key !== "Escape") return;
  if (!$("#modal-host").hidden) closeModal();
  else if (!$("#drawer").hidden) closeDrawer();
  else if (!$("#checkout-host").hidden && !S.placing) closeCheckout();
  else { $("#suggest").hidden = true; closeAcctMenu(); closeFilters(); syncLock(); }
});

/* ---------- Boot ---------- */
function renderAll() {
  renderBrand(); renderTopbar(); renderAdStrip(); renderCatNav(); renderHero(); renderBenefits(); renderCategories(); renderDeals(); renderPromoRow(); renderAdBand(); renderAiBanner(); renderFilters(); renderGrid(); renderFooter(); renderBadges(); renderAccountBtn();
  if (S.isDemo) $("#chips").insertAdjacentHTML("afterbegin", `<div class="alert info" style="width:100%">${icon("alert")}<span>Preview catalogue shown — the live store couldn't be reached. Start the backend to see real products.</span></div>`);
}
async function init() {
  renderShopShell(); renderBrand(); renderTopbar(); renderHero(); renderBenefits(); renderFooter(); renderBadges(); renderAccountBtn(); renderCatNav();
  const [settings, products, cats] = await Promise.all([api("/store/settings").catch(() => ({})), api("/products").catch(() => null), api("/categories").catch(() => null)]);
  S.settings = settings && typeof settings === "object" && !Array.isArray(settings) ? settings : {};
  store.set("pn_store_settings", S.settings);
  if (Array.isArray(products)) { S.products = products.filter(p => p && p.status !== "INACTIVE"); }
  else { S.isDemo = true; S.products = C.USE_DEMO_FALLBACK ? demo : []; }
  S.categories = Array.isArray(cats) && cats.length ? cats.map(c => c.name) : [...new Set(S.products.map(p => p.category).filter(Boolean))];
  S.loaded = true;
  syncCartWithCatalogue();
  renderAll();
  const pay = new URLSearchParams(location.search).get("payment");
  if (pay) {
    history.replaceState({}, "", location.pathname + location.hash);
    if (pay === "success") { S.cart = []; saveCart(); openModal(`<div class="modal-pad" style="text-align:center"><div class="co-success" style="box-shadow:none;border:0;margin:0;padding:10px"><div class="tick">${icon("check")}</div><h1>Payment received</h1><p>Thank you! Your payment was verified and your order is being processed.</p><div class="co-actions" style="margin-top:22px"><a class="btn btn-primary" href="/customer.html#my-orders">View my orders</a><button class="btn btn-ghost" data-close>Continue shopping</button></div></div></div>`); }
    else toast("Payment was not completed. You can retry from My orders.", "err");
  }
  if (location.hash && location.hash.length > 1) setTimeout(() => routeHash(decodeURIComponent(location.hash)), 300);
}
/* Hook for js/ai-features.js (visual search, smart search, recommendations). Read-only access to existing helpers. */
window.PaboNakiStore = { S, card, esc, money, icon, toast, openModal, closeModal };
init();
})();
