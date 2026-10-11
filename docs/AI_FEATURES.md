# PaboNaki AI features: visual search, smart search, recommendations

Everything here is **additive**. No existing endpoint, collection, page or dependency was removed. The old
`POST /api/v1/visual-search` and `GET /api/v1/recommendations` (used by `advanced.html`) still exist and behave as before.

## What was added

| Feature | Endpoint | Where it shows in the storefront |
|---|---|---|
| Visual search | `POST /api/v1/search/visual` (multipart field `image`) | Camera button inside the header search bar, preview dialog, results section above the shop grid |
| Smart (semantic) search | `GET /api/v1/search/semantic?q=...&category&minPrice&maxPrice&inStock&sort&page&size` | "Smart matches" row above the normal keyword grid after you press Search. The keyword grid, filters, sorting and "load more" are unchanged |
| Capabilities | `GET /api/v1/search/capabilities` | Lets the page show the camera button / smart row only when AI is configured |
| Recommended for You | `GET /api/v1/recommendations/for-you` (**login required**) | Home page, above the shop grid |
| Guest / session recommendations | `GET /api/v1/recommendations/discover?sessionId=` | Same slot for guests (falls back to Trending) |
| Trending Products | `GET /api/v1/recommendations/trending` | Home page |
| Because You Viewed | `GET /api/v1/recommendations/because-you-viewed?sessionId=` | Home page |
| Similar Products | `GET /api/v1/recommendations/similar/{productId}` | Inside the product quick-view |
| Frequently Bought Together | `GET /api/v1/recommendations/frequently-bought-together/{productId}` | Inside the product quick-view (only when real co-purchases exist) |
| Activity signals | `POST /api/v1/activity` | Sent automatically on view / cart add / wishlist add / search |
| Admin | `GET /api/v1/admin/ai/status`, `POST /api/v1/admin/ai/reindex?force=&limit=` | Admin token required (existing `/admin/**` rule) |

New MongoDB collections (your existing collections are never modified): `product_embeddings` (derived, safe to delete, rebuilt automatically) and `user_activity` (auto-expires after 180 days).

## How it works

**Embeddings.** `gemini-embedding-2` (Google Gemini API) maps text and images into one vector space. Product text (name, category, description) and product images are embedded once, stored in `product_embeddings`, and only re-embedded when a SHA-256 content hash changes (name, description, category, image URL or model). Searches embed only the query/photo.

**Indexing.** A Mongo save listener queues every created/edited product; a scheduled sweep (every 5 min) backfills existing products in batches of 20 (`AI_INDEX_BATCH`), skips unchanged ones, retries failed images up to 3 times (1 h apart), and stops cleanly on rate limits or when the daily budget is reached. Products without an image, or whose image is not JPEG/PNG, are recorded as `NO_IMAGE` / `UNSUPPORTED` (they still take part in text search). Admin can also trigger `POST /api/v1/admin/ai/reindex?limit=50` repeatedly.

**Vector search.** First tries Atlas `$vectorSearch`; if the index is missing/building or you run a local MongoDB, it answers with an exact in-memory cosine scan (fine up to a few thousand products, `AI_VECTOR_MODE=local` forces this). Results are always re-checked against the live `products` records.

**Smart search.** Rule-based query parser (budget like "under 3000 BDT", "between 1k and 5k", "affordable") + keyword score + semantic score fused with Reciprocal Rank Fusion, then hard filters: ACTIVE only, in stock (default), category and price. If AI is not configured or the provider fails, the same endpoint returns keyword matches and says so in `mode`/`notice` (it never fabricates results).

**Recommendations.** No paid AI needed for personalization. Score = content similarity (text embeddings, if available) + category affinity + price fit + aggregate popularity, with recency decay (half-life 21 days). Signals: completed orders (server data), plus views, searches, cart adds and wishlist adds reported by the browser (cart and wishlist live in the browser in PaboNaki). Only ACTIVE, in-stock products are recommended. Popularity is aggregated anonymously and cached for 10 minutes. The engine is in `RecommendationService`; swap it for an ML model later without touching controllers.

**Privacy.** User identity comes only from the verified JWT. Guest data is keyed by a random browser id and only reachable with that id. API responses contain product data only, never anyone's history; personalized responses send `Cache-Control: private, no-store`.

## Setup

1. **Rotate your credentials.** Secrets were hardcoded in the original `application.properties` (MongoDB password, Gemini, Resend, Cloudinary, Pathao, SSLCOMMERZ, Gmail app password). They have been replaced by environment variables. Treat the old values as compromised.
2. **Provide configuration.** Copy `.env.example` to `.env` in `backend/` (or the project root) and fill it in. Spring Boot does not read `.env` on its own; `application.properties` now has `spring.config.import=optional:file:./.env[.properties],optional:file:../.env[.properties]` so it does. Real OS environment variables override the file. `.env` is git-ignored.
3. **Gemini key.** Create a key in Google AI Studio and put it in `GEMINI_API_KEY`. The free tier has rate limits; paid usage is billed per token/image. Check current prices at https://ai.google.dev/gemini-api/docs/pricing. `AI_DAILY_EMBEDDING_BUDGET` caps calls per UTC day (default 2000). Rough guide: indexing costs 2 calls per product (text + image); each *new* search query is 1 call (repeat queries are cached for an hour); each photo search is 1 call. Embeddings send product text/images and customer query text/photos to Google; see Google's terms for the Gemini API.
4. **Atlas vector indexes (recommended).** Atlas UI > your cluster > Search > Create Search Index > JSON editor, collection `<db>.product_embeddings`, type *Vector Search*, using the two definitions in `docs/atlas-vector-indexes.json` (names `product_text_vector_index` and `product_image_vector_index`). `numDimensions` must equal `AI_EMBEDDING_DIMENSIONS` (768). Free M0 clusters support a limited number of search indexes; two is within that limit but confirm on your plan. Without these indexes the app still works via the in-memory fallback.
5. **Start the app and wait for indexing**, or backfill immediately as admin: `POST /api/v1/admin/ai/reindex?limit=50` (repeat until `processed` is 0). `GET /api/v1/admin/ai/status` shows counts.

## Windows commands

```powershell
cd path\to\PaboNaki_Complete\backend
copy ..\.env.example .env        # then edit .env with your real values
mvn clean verify
mvn spring-boot:run
# open http://localhost:8080
```
If PowerShell cannot find `mvn`, install Maven and Java 21 and reopen the terminal (`java -version` should say 21).

## Deployment

Use the same process you used before (the backend serves the storefront). Set the same variables from `.env.example` in your host's environment settings instead of a `.env` file, create the Atlas vector indexes once, deploy, then call the admin reindex endpoint. The app limits per-client request rates in memory (single instance). Behind a reverse proxy all users can look like one IP address; enable forwarded-header support for your host (for example `server.forward-headers-strategy=native`) so per-IP limits work.

## What is verified and what is not

Verified in the packaging sandbox (no Maven, no network, no MongoDB, no Gemini key): the pure logic (query/budget parser, image validation and SSRF address checks, vector math, rate limiter, scoring functions) compiled with the JDK and passed its tests; frontend JavaScript passed `node --check`.
**Not verified (you must run these):** the full Maven build, all Spring/Mongo-dependent tests (`GeminiEmbeddingClientTest`, `SemanticSearchServiceTest`, `RecommendationServiceTest`), live Gemini calls, Atlas `$vectorSearch`, the browser UI, and regression of existing features. Run `mvn clean verify` first and fix anything it reports.

Known limits: the visual-similarity threshold (`AI_VISUAL_MIN_SCORE`, default 0.35) is a starting guess; tune it with your photos. Gemini embeddings accept JPEG/PNG only (WEBP product images are indexed via Cloudinary JPEG renditions when hosted on Cloudinary, otherwise marked UNSUPPORTED). Cart and wishlist events come from the browser, so they are signals, not facts; purchases come from orders. Popularity can be nudged by a determined bot despite rate limits and view de-duplication.

## Manual checks before production

- Rotate all previously exposed secrets; set a strong `JWT_SECRET`; remove `PABONAKI_SETUP_TOKEN` after admin setup.
- Create Atlas vector indexes; run the reindex until `status` shows all products indexed.
- Try: photo search with a JPG, PNG and an invalid file; a natural-language query; log in, view/add items, reload the home page; open a product and check Similar / Frequently Bought Together.
- Confirm login, registration, checkout, orders, admin pages and the old `advanced.html` still work.
