package com.pabonaki.model;

import org.springframework.data.annotation.Id;
import org.springframework.data.mongodb.core.index.Indexed;
import org.springframework.data.mongodb.core.mapping.Document;
import java.time.Instant;

/** Temporary state for the email OTP password-reset flow. */
@Document("passwordResetOtps")
public class PasswordResetOtp {
    @Id private String id;
    @Indexed(unique = true) private String email;
    private String otpHash;
    private int attempts = 0;
    private Instant createdAt = Instant.now();
    @Indexed(expireAfter = "0s") private Instant expiresAt;
    private Instant verifiedAt;

    public String getId(){return id;} public void setId(String v){id=v;}
    public String getEmail(){return email;} public void setEmail(String v){email=v;}
    public String getOtpHash(){return otpHash;} public void setOtpHash(String v){otpHash=v;}
    public int getAttempts(){return attempts;} public void setAttempts(int v){attempts=v;}
    public Instant getCreatedAt(){return createdAt;} public void setCreatedAt(Instant v){createdAt=v;}
    public Instant getExpiresAt(){return expiresAt;} public void setExpiresAt(Instant v){expiresAt=v;}
    public Instant getVerifiedAt(){return verifiedAt;} public void setVerifiedAt(Instant v){verifiedAt=v;}
}
