# PaboNaki Advanced Features (additive patch)

This patch adds `AdvancedFeaturesController.java` and `advanced.html` without replacing existing controllers, storefront scripts, Google sign-in, checkout, payment, courier, admin pages, or existing UI.

## Open
Run the existing backend as before, then visit `http://localhost:8080/advanced.html`.
Sign in first. Admin-only cards require an ADMIN account.

## Added APIs
- `POST /api/v1/visual-search` (multipart form field `image`, JPG/PNG/WEBP, max 5 MB): Gemini image description + local catalogue keyword matching.
- `GET /api/v1/recommendations` (signed in): recommendation based on purchase-category affinity, excludes previously purchased products.
- `GET /api/v1/admin/safety/overview` (admin): review flags for recent order inconsistencies. It is an alert dashboard, not a payment gateway validator.
- `GET /api/v1/admin/analytics/predictions` (admin): simple revenue trend estimate and low-stock alerts.
- `GET /api/v1/loyalty/me` (signed in): 1 point per Tk 100 in paid/delivered orders, plus 200 points for each successful referral.
- `POST /api/v1/loyalty/referral/apply` (signed in; JSON `{"code":"PN..."}`): apply a referral code once per account.

## Configuration
Visual search uses the existing `app.integrations.gemini-key` property. For production, configure it with an environment variable such as `${GEMINI_API_KEY:}` rather than committing an API key. Do not share or commit secrets.

## Important limits
- Visual matching is catalogue keyword matching after Gemini describes the image; it is not vector-embedding image similarity.
- Recommendation and sales predictions are baseline implementations based on existing order data, not trained ML models.
- Safety dashboard only flags suspicious inconsistencies for manual review. Existing payment callbacks must continue to validate transactions with the payment provider before an order is marked paid.
- The loyalty points are currently computed from qualifying order history; referral relationships are stored in the new `pabonaki_referrals` MongoDB collection. No redemption/cash payout is implemented.
- Test payment, courier, mail, Google login, visual search, and admin authorization in a sandbox before deploying to production.
