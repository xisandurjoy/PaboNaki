package com.pabonaki.controller;

import com.pabonaki.integration.IntegrationService;
import com.pabonaki.model.PasswordResetOtp;
import com.pabonaki.model.User;
import com.pabonaki.repo.UserRepository;
import jakarta.validation.Valid;
import jakarta.validation.constraints.Email;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Pattern;
import org.springframework.data.mongodb.core.MongoTemplate;
import org.springframework.data.mongodb.core.query.Criteria;
import org.springframework.data.mongodb.core.query.Query;
import org.springframework.http.HttpStatus;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.server.ResponseStatusException;

import java.time.Instant;
import java.util.Locale;
import java.util.Map;
import java.security.SecureRandom;

@RestController
@RequestMapping("/api/v1/auth/forgot-password")
public class PasswordResetController {
    private static final long OTP_LIFETIME_SECONDS = 600;
    private static final long RESEND_COOLDOWN_SECONDS = 60;
    private static final int MAX_ATTEMPTS = 5;
    private final UserRepository users;
    private final MongoTemplate mongo;
    private final PasswordEncoder encoder;
    private final IntegrationService integrations;
    private final SecureRandom random = new SecureRandom();

    public PasswordResetController(UserRepository users, MongoTemplate mongo, PasswordEncoder encoder, IntegrationService integrations) {
        this.users = users; this.mongo = mongo; this.encoder = encoder; this.integrations = integrations;
    }

    public record EmailRequest(@NotBlank @Email String email) {}
    public record VerifyRequest(@NotBlank @Email String email, @NotBlank @Pattern(regexp="[0-9]{6}") String otp) {}
    public record ResetRequest(@NotBlank @Email String email, @NotBlank @Pattern(regexp="[0-9]{6}") String otp,
                               @NotBlank @Pattern(regexp="^(?=.*[A-Za-z])(?=.*\\d).{8,72}$") String newPassword,
                               @NotBlank String confirmPassword) {}

    @PostMapping("/request-otp")
    public Map<String,String> requestOtp(@Valid @RequestBody EmailRequest request) {
        String email = request.email().trim().toLowerCase(Locale.ROOT);
        // Use a generic response so callers cannot discover which email addresses are registered.
        User user = users.findByEmailIgnoreCase(email).orElse(null);
        if (user == null) return Map.of("message", "If an account exists for that email, a reset code has been sent.");
        if (!integrations.emailConfigured()) throw new ResponseStatusException(HttpStatus.SERVICE_UNAVAILABLE, "Email delivery is not configured. Check RESEND_API_KEY and EMAIL_FROM.");
        PasswordResetOtp old = mongo.findById(email, PasswordResetOtp.class);
        if (old != null && old.getCreatedAt() != null && old.getCreatedAt().isAfter(Instant.now().minusSeconds(RESEND_COOLDOWN_SECONDS))) {
            throw new ResponseStatusException(HttpStatus.TOO_MANY_REQUESTS, "Please wait 60 seconds before requesting another code.");
        }
        String code = String.format(Locale.ROOT, "%06d", random.nextInt(1_000_000));
        PasswordResetOtp pending = new PasswordResetOtp();
        pending.setId(email); pending.setEmail(email); pending.setOtpHash(encoder.encode(code));
        pending.setAttempts(0); pending.setCreatedAt(Instant.now()); pending.setExpiresAt(Instant.now().plusSeconds(OTP_LIFETIME_SECONDS));
        pending.setVerifiedAt(null); mongo.save(pending);
        try {
            integrations.sendEmail(email, "Reset your PaboNaki password", "<div style='font-family:Arial,sans-serif;max-width:520px;margin:auto;padding:28px;border:1px solid #eee7fb;border-radius:18px;color:#211044'><h2>Reset your PaboNaki password</h2><p>Use this one-time code to verify your email address.</p><div style='font-size:32px;letter-spacing:8px;font-weight:bold;background:#f4efff;padding:18px;text-align:center;border-radius:12px;color:#5c35bd'>"+code+"</div><p>This code expires in 10 minutes. You can request another code after 60 seconds.</p><p>If you did not request a password reset, you can safely ignore this email.</p></div>");
        } catch (RuntimeException ex) {
            mongo.remove(new Query(Criteria.where("_id").is(email)), PasswordResetOtp.class);
            throw new ResponseStatusException(HttpStatus.SERVICE_UNAVAILABLE, "Could not send the reset email. Check RESEND_API_KEY and that EMAIL_FROM is an approved sender in Resend.");
        }
        return Map.of("message", "If an account exists for that email, a reset code has been sent.");
    }

    @PostMapping("/verify-otp")
    public Map<String,String> verifyOtp(@Valid @RequestBody VerifyRequest request) {
        String email = request.email().trim().toLowerCase(Locale.ROOT);
        PasswordResetOtp pending = mongo.findById(email, PasswordResetOtp.class);
        if (pending == null || pending.getExpiresAt() == null || pending.getExpiresAt().isBefore(Instant.now())) {
            if (pending != null) mongo.remove(pending);
            throw new ResponseStatusException(HttpStatus.GONE, "Code expired. Request a new password-reset code.");
        }
        if (pending.getAttempts() >= MAX_ATTEMPTS) {
            mongo.remove(pending);
            throw new ResponseStatusException(HttpStatus.TOO_MANY_REQUESTS, "Too many incorrect codes. Request a new code.");
        }
        if (!encoder.matches(request.otp(), pending.getOtpHash())) {
            pending.setAttempts(pending.getAttempts()+1); mongo.save(pending);
            throw new ResponseStatusException(HttpStatus.UNAUTHORIZED, "Incorrect verification code.");
        }
        pending.setVerifiedAt(Instant.now()); mongo.save(pending);
        return Map.of("message", "Email verified. You can now choose a new password.");
    }

    @PostMapping("/reset")
    public Map<String,String> reset(@Valid @RequestBody ResetRequest request) {
        if (!request.newPassword().equals(request.confirmPassword())) throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Passwords do not match.");
        String email = request.email().trim().toLowerCase(Locale.ROOT);
        PasswordResetOtp pending = mongo.findById(email, PasswordResetOtp.class);
        Instant now = Instant.now();
        if (pending == null || pending.getExpiresAt() == null || pending.getExpiresAt().isBefore(now)) {
            if (pending != null) mongo.remove(pending);
            throw new ResponseStatusException(HttpStatus.GONE, "Reset session expired. Request a new code.");
        }
        if (pending.getVerifiedAt() == null || pending.getVerifiedAt().isBefore(now.minusSeconds(600)) || !encoder.matches(request.otp(), pending.getOtpHash())) {
            throw new ResponseStatusException(HttpStatus.UNAUTHORIZED, "Verify your email code again before resetting the password.");
        }
        User user = users.findByEmailIgnoreCase(email).orElseThrow(() -> new ResponseStatusException(HttpStatus.BAD_REQUEST, "Unable to reset this account's password."));
        user.setPasswordHash(encoder.encode(request.newPassword())); users.save(user);
        mongo.remove(pending);
        return Map.of("message", "Password reset successfully. Please log in with your new password.");
    }
}
