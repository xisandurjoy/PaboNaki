package com.pabonaki.config;

import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.http.converter.HttpMessageNotReadableException;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import org.springframework.web.server.ResponseStatusException;

import java.util.LinkedHashMap;
import java.util.Map;

/** Always answers with {"message": "..."} so the storefront can show a human-readable reason. */
@RestControllerAdvice
public class ApiExceptionHandler {

    private static final Map<String, String> LABELS = Map.ofEntries(
            Map.entry("firstName", "First name"), Map.entry("lastName", "Last name"),
            Map.entry("name", "Name"), Map.entry("email", "Email address"),
            Map.entry("password", "Password"), Map.entry("confirmPassword", "Confirm password"),
            Map.entry("phone", "Phone number"), Map.entry("customerName", "Full name"),
            Map.entry("address", "Delivery address"), Map.entry("deliveryArea", "Delivery area"),
            Map.entry("paymentMethod", "Payment method"), Map.entry("items", "Your bag"),
            Map.entry("otp", "Verification code"), Map.entry("subject", "Subject"),
            Map.entry("message", "Message"));

    @ExceptionHandler(ResponseStatusException.class)
    public ResponseEntity<Map<String, Object>> status(ResponseStatusException ex) {
        String reason = ex.getReason();
        if (reason == null || reason.isBlank()) reason = HttpStatus.valueOf(ex.getStatusCode().value()).getReasonPhrase();
        return ResponseEntity.status(ex.getStatusCode()).body(body(reason));
    }

    @ExceptionHandler(MethodArgumentNotValidException.class)
    public ResponseEntity<Map<String, Object>> invalid(MethodArgumentNotValidException ex) {
        String msg = ex.getBindingResult().getFieldErrors().stream().findFirst().map(fe -> {
            String field = fe.getField();
            String label = LABELS.getOrDefault(field.replaceAll("\\[.*", ""), field);
            String detail = fe.getDefaultMessage() == null ? "is invalid" : fe.getDefaultMessage();
            if (detail.equals("must not be blank") || detail.equals("must not be empty")) detail = "is required";
            return label + " " + detail + ".";
        }).orElse("Please check the form and try again.");
        return ResponseEntity.badRequest().body(body(msg));
    }

    @ExceptionHandler(HttpMessageNotReadableException.class)
    public ResponseEntity<Map<String, Object>> unreadable(HttpMessageNotReadableException ex) {
        return ResponseEntity.badRequest().body(body("The request could not be read. Please refresh the page and try again."));
    }

    private Map<String, Object> body(String message) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("message", message);
        return m;
    }
}
