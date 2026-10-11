# Change summary (AI features upgrade)

## Modified existing files (small, additive)
- `backend/src/main/java/com/pabonaki/config/SecurityConfig.java`: 3 new route rules (public search/recommendation GETs, public POST for photo search and activity; `/recommendations/for-you` requires login). Existing rules untouched.
- `backend/src/main/resources/application.properties`: hardcoded secrets replaced by environment variables (no secret defaults); `.env` auto-import; `app.ai.*` settings; multipart limit raised to 6 MB.
- `backend/src/main/resources/static/index.html`, `frontend/index.html`: one CSS link, one script tag.
- `backend/src/main/resources/static/js/store.js`, `frontend/js/store.js`: one line exposing existing helpers (`window.PaboNakiStore`).
- `.env.example`, `README.md`: new variables / pointer to docs.

## New files
- `backend/src/main/java/com/pabonaki/ai/`: `AiConfig, AiSettings, EmbeddingProvider, EmbeddingException, GeminiEmbeddingClient, ImageFetcher, ImageSupport, ProductEmbedding, ProductIndexer, ProductIndexListener, QueryParser, RateLimiter, SearchController, SemanticSearchService, VectorMath, VectorSearcher, VisualSearchService, AiAdminController`
- `backend/src/main/java/com/pabonaki/ai/reco/`: `ActivityController, PopularityService, RecoMath, RecommendationController, RecommendationService, UserActivity`
- `backend/src/test/java/com/pabonaki/ai/`: `QueryParserTest, ImageSupportTest, RateLimiterTest, VectorMathTest, GeminiEmbeddingClientTest, SemanticSearchServiceTest` and `reco/`: `RecoMathTest, ActivityValidationTest, RecommendationServiceTest`
- `static/js/ai-features.js`, `static/css/ai-features.css` (also copied to `frontend/`)
- `docs/AI_FEATURES.md`, `docs/atlas-vector-indexes.json`, `docs/CHANGES.md`

No Maven dependency was added or removed. `backend/target` (stale build output) is not included; Maven rebuilds it.
