# PaboNaki — E-commerce MVP

**Ja Khujcho, Peye Jabe.**

## Stack
- Java 21, Spring Boot 3.5.5, Maven
- MongoDB Atlas (or local MongoDB for development)
- HTML, CSS, Vanilla JavaScript served by Spring Boot
- JWT authentication, customer/admin roles

## Run locally
1. Install Java 21, Maven, and MongoDB (or create a MongoDB Atlas database).
2. Set the environment variables listed in `.env.example` in your terminal/IDE. Do not commit real keys.
3. From the `backend` folder, run `mvn clean test` and then `mvn spring-boot:run`.
4. Open `http://localhost:8080` (the frontend is served by Spring Boot; no separate frontend server is required).
5. Check `http://localhost:8080/api/v1/health` for the health endpoint.

On Windows PowerShell, set a variable for the current terminal with `$env:NAME="value"`. On macOS/Linux use `export NAME="value"`.

## One-time admin bootstrap
Set `PABONAKI_SETUP_TOKEN` to a long random temporary secret. Then POST to `/api/v1/setup/initialize`:

```json
{"setupToken":"your-temporary-secret","name":"Store Admin","email":"admin@example.com","password":"Use-A-Strong-Password-Here"}
```

After the admin is created, remove `PABONAKI_SETUP_TOKEN` from the environment and restart the application. Never expose the admin token in frontend code.

## API integrations
All credentials are read from environment variables; no real keys are embedded in the source.

| Integration | Implemented endpoint/use | Required configuration |
|---|---|---|
| MongoDB Atlas | Users, products, categories, orders | `MONGODB_URI` |
| Gemini | Product-catalog-aware shopping assistant | `GEMINI_API_KEY`, optional `GEMINI_MODEL` |
| Google Identity Services | Google ID-token verification and login | `GOOGLE_CLIENT_ID`; configure the matching authorized JavaScript origin in Google Cloud Console |
| Brevo (preferred) | Registration OTP, password reset, order confirmation/status and support emails | `BREVO_API_KEY`, `BREVO_SENDER_EMAIL`, `BREVO_SENDER_NAME` |\n| Resend (fallback) | Optional email API fallback | `RESEND_API_KEY`, `EMAIL_FROM` (verify your sending domain for production) |
| Cloudinary | Signed product image upload endpoint | `CLOUDINARY_CLOUD_NAME`, `CLOUDINARY_API_KEY`, `CLOUDINARY_API_SECRET` |
| Pathao | Admin shipment creation | Merchant credentials, store ID, and valid city/zone/area IDs from Pathao |
| SSLCOMMERZ | Online checkout, server-side transaction validation, success/fail/cancel/IPN callbacks | Sandbox/merchant store ID and password; set public callback URL for deployment |
| COD | Standard order placement | No payment key required |

### Main API routes
- `POST /api/v1/auth/register`, `POST /api/v1/auth/login`, `POST /api/v1/auth/google`
- `GET /api/v1/products`, `GET /api/v1/products/{id}`, `GET /api/v1/categories`
- `POST /api/v1/orders`, `GET /api/v1/orders/my`, `POST /api/v1/orders/{id}/cancel`
- `POST /api/v1/payments/{orderId}/sslcommerz/initiate`
- `POST /api/v1/ai/ask`
- Admin: `POST /api/v1/admin/media/upload`, `POST /api/v1/admin/email/send`, `POST /api/v1/admin/orders/{id}/courier`
- Admin: `POST /api/v1/admin/products`, `GET /api/v1/admin/orders`, `POST /api/v1/admin/orders/{id}/confirm`, `PATCH /api/v1/admin/orders/{id}/status?value=SHIPPED`

## Important integration setup notes
- **SSLCOMMERZ:** start with the sandbox URL in `.env.example`. Set `PUBLIC_BASE_URL` to a publicly reachable HTTPS backend for real gateway callbacks. The success callback validates the transaction with SSLCOMMERZ before marking it paid. Do not mark orders paid from frontend input.
- **Pathao:** the code requires a valid merchant account and actual recipient city/zone/area IDs. The configured IDs are used for shipments; configure them for the delivery area supported by the account. Test with Pathao sandbox/merchant guidance before production.
- **Google:** add the same `GOOGLE_CLIENT_ID` to Google Identity Services and authorize the website origin. Google sign-in is hidden until a client ID is configured.
- **Cloudinary:** upload endpoint is admin-only and limits uploads to 8 MB. Only upload appropriate product images.
- **Brevo:** verify the sender in Brevo before production sending. Set `BREVO_API_KEY` and `BREVO_SENDER_EMAIL`; this uses HTTPS and works around Render Free SMTP port restrictions.\n- **Resend:** test mode/sender restrictions may apply until the sender domain is verified.
- **Gemini:** responses use current active products as catalog context, but still validate any purchase details against the backend.

## Test scope / current limitations
Run `mvn clean test` on a machine with Maven and network access to resolve dependencies. The project includes unit tests for order/payment model defaults. External services cannot be live-tested without your own merchant/API credentials and valid public callback URLs. Before a real launch, add rate limiting, password reset/email verification, payment refund handling, shipping-zone selection, admin dashboard CRUD, monitoring, and full end-to-end tests. Never commit API keys, JWT secrets, database passwords, or `.env` files.

## Local demo data and demo accounts

The development seeder is enabled by default for this local starter (`app.seed-demo=${PABONAKI_SEED_DEMO:false}`). On first startup it creates the five categories, up to 20 sample products (missing SKUs only), and the two roles currently implemented by the backend:

| Role | Demo email | Demo password |
|---|---|---|
| ADMIN | `admin@pabonaki.demo` | `PaboAdmin123!` |
| CUSTOMER | `customer@pabonaki.demo` | `PaboCustomer123!` |

Passwords are stored as BCrypt hashes. Seeding is idempotent: existing demo accounts and product SKUs are not overwritten. These accounts are **local-demo credentials only**. Before deployment, set `PABONAKI_SEED_DEMO=false`, change any demo passwords, and configure a strong `JWT_SECRET`. The codebase currently defines `ADMIN` and `CUSTOMER`; no seller/staff role is implemented yet.

### Connect your MongoDB Atlas database

Do not commit your database URI or paste credentials into source files. Set the URI as an environment variable called `MONGODB_URI` on the computer where you run the app. If your database password was exposed, rotate it in MongoDB Atlas and use the new password. Ensure Atlas Network Access allows your current IP and the database user has the required database permissions.

Windows PowerShell (current terminal only):

```powershell
$env:MONGODB_URI = "<your-new-MongoDB-Atlas-URI>"
$env:PABONAKI_SEED_DEMO = "true"
cd backend
mvn clean test
mvn spring-boot:run
```

Then open `http://localhost:8080`. Seeding runs only when the app can connect to MongoDB. The current project has not been configured with a production seller/staff account or production credentials.

## Shopping assistant

The assistant modal includes suggested prompts, chat bubbles, a loading indicator, and catalogue suggestions when Gemini is not configured. For live Gemini responses, set `GEMINI_API_KEY`. The catalogue-only fallback is not a live AI response.


## Demo access and admin dashboard

For local development, demo seeding is enabled by default (`app.seed-demo=true`). On first startup, the app creates these accounts if they do not already exist:

- Admin: `admin@pabonaki.demo` / `PaboAdmin123!`
- Customer: `customer@pabonaki.demo` / `PaboCustomer123!`

Sign in using **Account** on the storefront. Admin login redirects to `/admin.html`. The dashboard has overview statistics, order confirmation/status updates, and product creation. Seed accounts are development-only. Set `PABONAKI_SEED_DEMO=false` in any shared/staging/production environment and change the JWT secret before deployment.

## Landing animation

The hero includes a locally hosted looping MP4 under `backend/src/main/resources/static/media/`; no third-party video host is required. The reduced-motion preference is respected by the CSS animations.

## Verification note

The project ZIP has been checked for archive integrity, JavaScript syntax, and `pom.xml` XML validity. Maven is not installed in the build environment, so a full Maven compile/test and real MongoDB/payment API test have not been claimed. Run `mvn clean test` and `mvn spring-boot:run` locally with Java 21 and a reachable MongoDB instance.

## Expanded admin and customer dashboards (blueprint progress)

The admin console now includes these management sections:

- **Overview:** product, order, pending-order, low-stock and sales metrics; recent orders.
- **Products:** create, edit, deactivate, pricing, SKU, category, stock and image URL management. Deactivation is used instead of hard deletion so historical order snapshots remain intact.
- **Categories:** create, edit/rename, activate/deactivate. Renaming a category updates products referencing its old name.
- **Orders:** review customer/address/item/payment data, confirm eligible orders, update fulfilment status, trigger Pathao booking when configured, and export order records to CSV.
- **Customers & accounts:** list accounts without exposing password hashes; switch ADMIN/CUSTOMER roles. The last admin cannot be demoted.
- **Reports:** order values by status, verified paid-online totals, and non-cancelled COD order value. COD order value is not proof that cash was collected.
- **API & integrations:** configured/missing environment indicators and documented endpoint routes. Configured values do not mean a live integration has been tested.
- **Customer dashboard:** dedicated `customer.html` page with order history, item/price snapshots, payment/courier status, pending-order cancellation and unpaid online-payment retry.

### Added admin endpoints
- `GET /api/v1/admin/overview`
- `GET /api/v1/admin/products`, `PUT /api/v1/admin/products/{id}`, `DELETE /api/v1/admin/products/{id}`
- `GET /api/v1/admin/categories`, `POST /api/v1/admin/categories`, `PUT /api/v1/admin/categories/{id}`, `DELETE /api/v1/admin/categories/{id}`
- `GET /api/v1/admin/users`, `PATCH /api/v1/admin/users/{id}/role`
- `GET /api/v1/admin/reports/sales`

### Important limitations before production
- The environment used to prepare this ZIP has Java 21 but does not have Maven installed, so a complete `mvn clean test` build was not run. JavaScript syntax checks and POM XML parsing are performed; compile/runtime success must still be verified locally.
- MongoDB, Gemini, Pathao, email, Cloudinary and SSLCOMMERZ cannot be fully tested without your own valid credentials and reachable callback configuration.
- Cart persistence is currently browser-local rather than a MongoDB `carts` collection; user profile/address book, reviews/ratings, wishlist sync, return/refund operations, and password reset/email verification remain future work.
- For production, disable demo seeding, replace local JWT defaults, rotate any previously exposed database credential, and never commit `.env` or real credentials.

## Checkout and integrations (updated)

- Checkout now shows a responsive order summary, delivery fee and total, and a clear COD vs SSLCOMMERZ choice.
- `POST /api/v1/payments/{orderId}/sslcommerz/initiate` starts a gateway session for the signed-in customer's ONLINE order.
- SSLCOMMERZ success/fail/cancel callbacks redirect to the storefront with a payment result. Success is only accepted after server-side validation against SSLCOMMERZ's validation API, transaction ID, amount and BDT currency.
- `PUBLIC_BASE_URL` must be a publicly reachable HTTPS backend URL so SSLCOMMERZ can call the callback endpoints. Keep `SSLCOMMERZ_BASE_URL=https://sandbox.sslcommerz.com` until sandbox checkout is tested; switch to the live gateway URL only with approved live merchant credentials.
- Admin product management has an image upload control. It posts the file to `POST /api/v1/admin/media/upload`; the backend signs the upload and sends it to Cloudinary, then fills the product image URL.
- Brevo API (when configured) is used for registration OTP, password reset, order confirmation/status notifications, customer support messages, and the protected admin email endpoint `POST /api/v1/admin/email/send`; Resend remains an optional fallback.
- Configure `SUPPORT_EMAIL` in the backend environment to receive customer support form messages. `EMAIL_FROM` must be an address/domain verified by Resend for production.
- Admin delivery fees saved in website settings are now used by the backend order total calculation as well as the checkout summary.

Never put provider secrets in frontend JavaScript or commit real keys to source control. Use environment variables and rotate any credentials that were exposed in a shared file or log.

### Cloudinary image upload setup (local IntelliJ)

The backend loads environment variables from the process environment and also optionally reads `.env` from the project root or `backend/` folder. Copy `.env.example` to `.env` in the project root, then fill these values using Cloudinary Dashboard → API Keys:

```properties
CLOUDINARY_CLOUD_NAME=your_actual_cloud_name
CLOUDINARY_API_KEY=your_api_key
CLOUDINARY_API_SECRET=your_rotated_api_secret
```

Do not use `Root`, your account username, or a URL as the cloud name. Keep `.env` private and never commit it. Restart the Spring Boot application after editing it. Upload errors now return a useful JSON `message` rather than only a generic `Bad Request`.

## Storefront & admin (latest update)
- Homepage is now an international-style storefront (`static/index.html`, `css/store.css`, `js/store.js`).
- **Admin → Homepage & footer**: hero banners (model/advertisement photos), side tiles, promo row, trust badges, footer text/columns/copyright/social links, free-delivery threshold.
- **Admin → Advertisements**: slim top strip or banner band under the hero.
- Sign-up collects first/last name, email, mobile, optional gender/DOB, password + confirm password, then email OTP.
- API errors now always return `{"message": ...}`; `/media/**` static files are public.

## AI features (visual search, smart search, recommendations)
Added without removing anything existing. See [`docs/AI_FEATURES.md`](docs/AI_FEATURES.md) for setup (Gemini key, Atlas vector indexes, indexing, endpoints, Windows commands, deployment, and what is/is not verified).
Note: `application.properties` no longer contains secrets; configure them via environment variables or a git-ignored `.env` (now auto-loaded if present in `backend/` or the project root).


## Render deployment
See [DEPLOY_RENDER.md](DEPLOY_RENDER.md) and the complete variable inventory in `.env.example`. Demo seeding is disabled by default for deployments.
