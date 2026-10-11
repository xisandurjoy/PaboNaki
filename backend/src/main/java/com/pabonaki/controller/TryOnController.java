package com.pabonaki.controller;

import com.pabonaki.model.Product;
import com.pabonaki.repo.ProductRepository;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.core.io.ByteArrayResource;
import org.springframework.http.*;
import org.springframework.util.LinkedMultiValueMap;
import org.springframework.util.MultiValueMap;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.client.RestClientResponseException;
import org.springframework.web.client.RestTemplate;
import org.springframework.web.multipart.MultipartFile;
import org.springframework.web.server.ResponseStatusException;

import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.util.LinkedHashMap;
import java.util.Locale;
import java.util.Map;

@RestController
@RequestMapping("/api/v1/tryon")
public class TryOnController {
    private static final String TRYON_URL = "https://www.tryoncloud.com/api/v1/generate";

    private final ProductRepository products;
    private final RestTemplate restTemplate = new RestTemplate();
    private final String apiKey;
    private final String publicBaseUrl;

    public TryOnController(
            ProductRepository products,
            @Value("${app.integrations.tryoncloud-key:}") String apiKey,
            @Value("${app.public-base-url:http://localhost:8080}") String publicBaseUrl) {
        this.products = products;
        this.apiKey = apiKey == null ? "" : apiKey.trim();
        this.publicBaseUrl = publicBaseUrl == null ? "" : publicBaseUrl.trim();
    }

    @PostMapping(value = "/generate", consumes = MediaType.MULTIPART_FORM_DATA_VALUE)
    public ResponseEntity<?> generate(
            @RequestPart("person_image") MultipartFile personImage,
            @RequestParam(required = false) String productId,
            @RequestParam(required = false) String garmentUrl) {

        if (apiKey.isBlank()) {
            return error(HttpStatus.SERVICE_UNAVAILABLE,
                    "Try-on is not configured yet. Add TRYONCLOUD_API_KEY to your environment and restart the server.",
                    "TRYON_NOT_CONFIGURED");
        }
        if (personImage == null || personImage.isEmpty()) {
            return error(HttpStatus.BAD_REQUEST, "Please upload your photo.", "NO_PERSON");
        }
        if (personImage.getSize() > 5L * 1024 * 1024) {
            return error(HttpStatus.PAYLOAD_TOO_LARGE, "Please use a photo smaller than 5 MB.", "IMAGE_TOO_LARGE");
        }
        String contentType = personImage.getContentType() == null ? "" :
                personImage.getContentType().toLowerCase(Locale.ROOT);
        if (!contentType.equals("image/jpeg") && !contentType.equals("image/png")) {
            return error(HttpStatus.BAD_REQUEST, "Please upload a JPG or PNG image.", "BAD_IMAGE_TYPE");
        }

        String selectedGarmentUrl = garmentUrl;
        if (productId != null && !productId.isBlank() && !productId.startsWith("demo")) {
            Product product = products.findById(productId)
                    .filter(p -> "ACTIVE".equals(p.getStatus()))
                    .orElse(null);
            if (product == null) {
                return error(HttpStatus.NOT_FOUND, "This product is no longer available.", "PRODUCT_NOT_FOUND");
            }
            selectedGarmentUrl = product.getImageUrl();
        }

        selectedGarmentUrl = makeAbsoluteUrl(selectedGarmentUrl);
        if (!isPublicHttpsUrl(selectedGarmentUrl)) {
            return error(HttpStatus.BAD_REQUEST,
                    "This product image needs a publicly accessible HTTPS URL. Set the product image to a public HTTPS image URL and try again.",
                    "GARMENT_URL_NOT_PUBLIC");
        }

        try {
            ByteArrayResource imageResource = new ByteArrayResource(personImage.getBytes()) {
                @Override
                public String getFilename() {
                    String original = personImage.getOriginalFilename();
                    return original == null || original.isBlank() ? "person-image.jpg" : original;
                }
            };
            HttpHeaders imageHeaders = new HttpHeaders();
            imageHeaders.setContentType(MediaType.parseMediaType(contentType));
            HttpEntity<ByteArrayResource> imagePart = new HttpEntity<>(imageResource, imageHeaders);

            MultiValueMap<String, Object> form = new LinkedMultiValueMap<>();
            form.add("garment_url", selectedGarmentUrl);
            form.add("person_image", imagePart);

            HttpHeaders headers = new HttpHeaders();
            headers.set("X-API-KEY", apiKey);
            headers.setContentType(MediaType.MULTIPART_FORM_DATA);
            headers.setAccept(java.util.List.of(MediaType.IMAGE_PNG, MediaType.APPLICATION_JSON));

            ResponseEntity<byte[]> response = restTemplate.exchange(
                    TRYON_URL, HttpMethod.POST, new HttpEntity<>(form, headers), byte[].class);

            byte[] image = response.getBody();
            if (image == null || image.length == 0) {
                return error(HttpStatus.BAD_GATEWAY, "The try-on service returned an empty image. Please try again.", "EMPTY_RESULT");
            }

            HttpHeaders resultHeaders = new HttpHeaders();
            resultHeaders.setContentType(MediaType.IMAGE_PNG);
            resultHeaders.setContentLength(image.length);
            resultHeaders.setContentDisposition(ContentDisposition.inline().filename("pabonaki-try-on.png").build());
            resultHeaders.setCacheControl(CacheControl.noStore());
            return new ResponseEntity<>(image, resultHeaders, HttpStatus.OK);
        } catch (RestClientResponseException ex) {
            HttpStatus status = HttpStatus.BAD_GATEWAY;
            String code = "TRYON_FAILED";
            String message = "The virtual try-on service could not generate the image. Please try again.";
            try {
                String body = ex.getResponseBodyAsString(StandardCharsets.UTF_8);
                com.fasterxml.jackson.databind.JsonNode json = new com.fasterxml.jackson.databind.ObjectMapper().readTree(body);
                if (json.hasNonNull("error")) message = json.get("error").asText();
                if (json.hasNonNull("code")) code = json.get("code").asText();
            } catch (Exception ignored) {
                // Keep the friendly fallback when the provider response is not JSON.
            }
            if (ex.getStatusCode().value() == 401) {
                status = HttpStatus.BAD_GATEWAY;
                message = "The TryOnCloud API key is invalid. Please check the server configuration.";
                code = "INVALID_TRYON_KEY";
            } else if (ex.getStatusCode().value() == 402) {
                status = HttpStatus.PAYMENT_REQUIRED;
                message = "Try-on credits are unavailable. Please contact the store administrator.";
                code = "NO_TRYON_CREDITS";
            } else if (ex.getStatusCode().value() == 413) {
                status = HttpStatus.PAYLOAD_TOO_LARGE;
                message = "The image is too large. Please use a smaller photo.";
                code = "IMAGE_TOO_LARGE";
            } else if (ex.getStatusCode().value() == 422) {
                status = HttpStatus.UNPROCESSABLE_ENTITY;
                message = "The uploaded photo or product image could not be processed. Try a clear, front-facing photo.";
                code = "IMAGE_NOT_PROCESSABLE";
            } else if (ex.getStatusCode().value() == 429) {
                status = HttpStatus.TOO_MANY_REQUESTS;
                message = "Too many try-on requests. Please wait a moment and try again.";
                code = "RATE_LIMITED";
            }
            return error(status, message, code);
        } catch (Exception ex) {
            return error(HttpStatus.BAD_GATEWAY,
                    "Could not generate the try-on image right now. Please check your connection and try again.",
                    "TRYON_CONNECTION_ERROR");
        }
    }

    private String makeAbsoluteUrl(String rawUrl) {
        if (rawUrl == null || rawUrl.isBlank()) return "";
        String value = rawUrl.trim();
        if (value.startsWith("https://")) return value;
        if (value.startsWith("http://")) return value;
        if (value.startsWith("/") && !publicBaseUrl.isBlank()) {
            return publicBaseUrl.replaceAll("/+$", "") + value;
        }
        return value;
    }

    private boolean isPublicHttpsUrl(String value) {
        try {
            URI uri = URI.create(value);
            String host = uri.getHost();
            if (!"https".equalsIgnoreCase(uri.getScheme()) || host == null) return false;
            String h = host.toLowerCase(Locale.ROOT);
            return !h.equals("localhost") && !h.equals("127.0.0.1") &&
                    !h.equals("0.0.0.0") && !h.equals("::1");
        } catch (Exception ex) {
            return false;
        }
    }

    private ResponseEntity<Map<String, String>> error(HttpStatus status, String message, String code) {
        Map<String, String> body = new LinkedHashMap<>();
        body.put("message", message);
        body.put("error", message);
        body.put("code", code);
        return ResponseEntity.status(status).contentType(MediaType.APPLICATION_JSON).body(body);
    }
}
