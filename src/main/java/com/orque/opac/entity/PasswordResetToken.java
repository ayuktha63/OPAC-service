package com.orque.opac.entity;

import jakarta.persistence.*;
import java.time.LocalDateTime;
import java.util.UUID;

@Entity
@Table(name = "password_reset_token", indexes = {
    @Index(name = "idx_password_reset_token_user_uuid", columnList = "user_uuid")
})
public class PasswordResetToken {
    @Id
    @GeneratedValue(strategy = GenerationType.AUTO)
    private UUID uuid;

    @Column(name = "user_uuid", nullable = false)
    private UUID userUuid;

    @Column(nullable = false, unique = true)
    private String token;

    @Column(name = "expiry_timestamp", nullable = false)
    private LocalDateTime expiryTimestamp;

    @Column(nullable = false)
    private boolean used = false;

    @Column(name = "created_timestamp")
    private LocalDateTime createdTimestamp = LocalDateTime.now();

    public UUID getUuid() { return uuid; }
    public void setUuid(UUID uuid) { this.uuid = uuid; }

    public UUID getUserUuid() { return userUuid; }
    public void setUserUuid(UUID userUuid) { this.userUuid = userUuid; }

    public String getToken() { return token; }
    public void setToken(String token) { this.token = token; }

    public LocalDateTime getExpiryTimestamp() { return expiryTimestamp; }
    public void setExpiryTimestamp(LocalDateTime expiryTimestamp) { this.expiryTimestamp = expiryTimestamp; }

    public boolean isUsed() { return used; }
    public void setUsed(boolean used) { this.used = used; }

    public LocalDateTime getCreatedTimestamp() { return createdTimestamp; }
    public void setCreatedTimestamp(LocalDateTime createdTimestamp) { this.createdTimestamp = createdTimestamp; }
}
