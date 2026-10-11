# TryOnCloud virtual try-on integration

This feature is additive: existing storefront, API routes, and application properties are retained.

## What was added

- Clothing product cards show a **Try it on** button.
- The customer selects a JPG/PNG photo (maximum 5 MB), and the backend sends it to TryOnCloud.
- The API returns a PNG; the storefront previews it and provides a **Download image** link.
- The TryOnCloud API key stays on the server and is never placed in browser JavaScript.

## Configure the API key

1. Create a Developer API key in your TryOnCloud dashboard.
2. Set the environment variable `TRYONCLOUD_API_KEY` on the machine/hosting platform that runs Spring Boot.
3. Restart the backend. Do not paste the key into frontend files or commit it.
4. Product images used for try-on must be publicly accessible HTTPS image URLs. If your product image URL is relative, set `PUBLIC_BASE_URL` to your public HTTPS storefront origin (for example `https://your-domain.example`) so the backend can build an absolute URL.

The existing `application.properties` was preserved; these additive settings are used:

```properties
app.integrations.tryoncloud-key=${TRYONCLOUD_API_KEY:}
```

If the key is missing, the rest of the store continues to work and the try-on endpoint returns a clear configuration message.

## Endpoint

`POST /api/v1/tryon/generate` (`multipart/form-data`)

- `person_image`: uploaded JPG/PNG photo
- `productId`: selected product ID (optional for demo catalogue)
- `garmentUrl`: selected product image URL (used for demo products; live product records use their stored image URL)

Successful response: `image/png`.

## Notes

- The existing Spring multipart limits were not changed.
- Try-on uses paid/free-trial credits in the TryOnCloud account. Review the provider's current pricing and limits before production use.
- The feature is intended for clothing products; other catalogue categories do not show the button.
