package com.pabonaki.model;

import org.springframework.data.annotation.Id;
import org.springframework.data.mongodb.core.index.Indexed;
import org.springframework.data.mongodb.core.mapping.Document;
import java.time.Instant;

@Document("registrationOtps")
public class RegistrationOtp {
    @Id private String id;
    @Indexed(unique = true) private String email;
    private String name;
    private String firstName, lastName, phone, gender, dateOfBirth;
    private String passwordHash;
    private String otpHash;
    private int attempts = 0;
    private Instant createdAt = Instant.now();
    @Indexed(expireAfter = "0s") private Instant expiresAt;
    public String getId(){return id;} public void setId(String v){id=v;}
    public String getEmail(){return email;} public void setEmail(String v){email=v;}
    public String getName(){return name;} public void setName(String v){name=v;}
    public String getPasswordHash(){return passwordHash;} public void setPasswordHash(String v){passwordHash=v;}
    public String getOtpHash(){return otpHash;} public void setOtpHash(String v){otpHash=v;}
    public int getAttempts(){return attempts;} public void setAttempts(int v){attempts=v;}
    public Instant getCreatedAt(){return createdAt;} public void setCreatedAt(Instant v){createdAt=v;}
    public Instant getExpiresAt(){return expiresAt;} public void setExpiresAt(Instant v){expiresAt=v;}
    public String getFirstName(){return firstName;} public void setFirstName(String v){firstName=v;}
    public String getLastName(){return lastName;} public void setLastName(String v){lastName=v;}
    public String getPhone(){return phone;} public void setPhone(String v){phone=v;}
    public String getGender(){return gender;} public void setGender(String v){gender=v;}
    public String getDateOfBirth(){return dateOfBirth;} public void setDateOfBirth(String v){dateOfBirth=v;}
}
