# Added: Admin sidebar scrolling and password reset by email OTP

## Admin sidebar
- Sidebar now supports vertical scrolling on desktop and mobile.
- On small screens it becomes a vertically scrollable panel; the storefront and logout links remain at the bottom of the menu and can be reached by scrolling.

## Forgot password
1. Open the storefront login dialog and select **Forgot password?**.
2. Enter the registered email address.
3. Enter the 6-digit email OTP (expires in 10 minutes).
4. Verify the OTP and choose a new password.

The flow uses the project's existing `IntegrationService` email delivery configuration (Gmail SMTP first, with the existing Resend fallback). Configure `SMTP_USERNAME`, `SMTP_PASSWORD` (a Gmail App Password), and `SMTP_FROM` through the existing environment/property setup for Gmail delivery. OTP requests have a 60-second resend cooldown and a five-attempt limit. The reset API endpoints are public only for the intended OTP flow; the rest of the existing API security rules are unchanged.

## New API endpoints
- `POST /api/v1/auth/forgot-password/request-otp` — JSON `{ "email": "you@example.com" }`
- `POST /api/v1/auth/forgot-password/verify-otp` — JSON `{ "email": "you@example.com", "otp": "123456" }`
- `POST /api/v1/auth/forgot-password/reset` — JSON `{ "email": "you@example.com", "otp": "123456", "newPassword": "NewPass123", "confirmPassword": "NewPass123" }`

Build/run the project from the `backend` directory using the project's normal Maven workflow so the new Java classes are compiled.
