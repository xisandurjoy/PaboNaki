# Render deployment checklist — PaboNaki

## Deploy
1. Push this project to a private/public Git repository.
2. In Render, create a **Web Service** from the repository and use the included `render.yaml` Blueprint, or configure a Docker web service with **Root Directory** `backend` and **Dockerfile** `./Dockerfile`.
3. Add the environment values below in Render. Do not upload a real `.env` file.
4. Deploy, then check `https://YOUR-SERVICE.onrender.com/api/v1/health`.

## Required Render environment variables
- `MONGODB_URI`: MongoDB Atlas connection URI. Encode reserved URI characters in the database password. In Atlas, allow Render access through Network Access; use least-privilege database credentials.
- `JWT_SECRET`: long random secret (use Render's generated value if available).
- `BREVO_API_KEY`: Brevo API key.
- `BREVO_SENDER_EMAIL`: exact sender address verified in Brevo.
- `BREVO_SENDER_NAME`: `PaboNaki`.
- `PUBLIC_BASE_URL`: deployed HTTPS URL, e.g. `https://your-service.onrender.com`.
- `FRONTEND_ORIGIN`: the public origin used by your frontend; if the frontend is served from this same service, set it to the same HTTPS URL.

## Optional integration environment variables
See `.env.example` for the complete inventory of optional Gemini, Google, Cloudinary, Pathao, SSLCOMMERZ, TryOnCloud, Resend, and legacy SMTP variables. Leave unused integrations blank. SSLCOMMERZ and Pathao are set to sandbox defaults unless configured otherwise.

## Important
- Demo data seeding is disabled by default (`PABONAKI_SEED_DEMO=false`). No demo products/accounts are inserted by deployment configuration.
- This archive intentionally excludes IDE settings, compiled `target/` output, and local `.env` files.
- Never put real API keys/passwords in `application.properties`, source code, or Git. This project previously contained embedded credentials. Rotate exposed MongoDB, Gemini, Cloudinary, Pathao, SSLCOMMERZ, Brevo/Resend, and other credentials before production, then enter the new values in Render.
- Render Free may spin down while idle. First request after idle can be slow.
- A valid environment configuration is required for MongoDB and JWT startup. Optional integrations only work when their respective provider credentials and account/domain setup are valid.
- Run `mvn clean test` from `backend/` with Java 21 and Maven before production. This environment did not have Maven installed, so a full Maven build has not been verified here.
