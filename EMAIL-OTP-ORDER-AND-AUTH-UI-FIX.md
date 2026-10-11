# PaboNaki: Email, Category and Authentication UI fixes

## Email OTP and order updates
The backend uses the Brevo transactional email API when BREVO_API_KEY and BREVO_SENDER_EMAIL are configured; it retains the Resend API as an optional fallback. Configure these environment variables in IntelliJ Run > Edit Configurations > Environment variables:

- `BREVO_API_KEY=your-Brevo-API-key`\n- `BREVO_SENDER_EMAIL=your-verified-sender@example.com`\n- `BREVO_SENDER_NAME=PaboNaki`\n\nLegacy SMTP settings (below) are retained for compatibility but are not required when Brevo API is configured.\n\n- `SMTP_HOST=smtp.gmail.com`
- `SMTP_PORT=587`
- `SMTP_USERNAME=your-sender@gmail.com`
- `SMTP_PASSWORD=your-Google-App-Password` (use an App Password; never your normal Google password)
- `SMTP_FROM=your-sender@gmail.com`

Alternatively configure `RESEND_API_KEY` and a sender approved by Resend using `EMAIL_FROM` if you do not configure Brevo. Email providers may reject unverified senders or invalid credentials; check the backend console for delivery errors. Do not paste secrets into chat or commit them.

Registration OTP expires after 10 minutes and has an attempt limit. Order-created and order-status emails are sent best-effort so an email outage does not undo a saved order. The console logs a failure without printing credentials.

## Order status email
Customer notification is attempted when an order is confirmed or its fulfilment status changes. A status notification is also attempted when a payment becomes PAID through the existing payment integration.

## Category management
The category table now provides an Activate action for inactive categories. Saving a category reloads the category and product lists. Category rename updates matching product category names case-insensitively. If save still fails, check the admin toast and backend log for a duplicate-name or authorization error.

## Login/register UI
The sign-in/register modal has been redesigned into a responsive split-screen layout with PaboNaki branding and a compact mobile layout. Existing OTP registration and login API flow are retained.

## Run
1. Open this extracted project folder in IntelliJ.
2. Reload Maven (the project adds `spring-boot-starter-mail`).
3. Set MongoDB and email environment variables in Run Configuration.
4. Restart the backend and test OTP using a real inbox, then test order confirmation/status updates.

Build and actual inbox delivery must be verified on your machine with valid SMTP/Resend credentials.
