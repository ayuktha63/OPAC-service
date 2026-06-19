package com.orque.opac.entity;

import jakarta.persistence.*;
import java.time.LocalDateTime;
import java.time.LocalDate;
import java.util.UUID;

@Entity
@Table(name = "session_master")
public class SessionMaster {
    @Id
    @GeneratedValue(strategy = GenerationType.AUTO)
    private UUID uuid;

    @Column(name = "tenant_uuid")
    private UUID tenantUuid;

    @Column(nullable = false)
    private String username;

    @Column(name = "login_timestamp", nullable = false)
    private LocalDateTime loginTimestamp = LocalDateTime.now();

    @Column(name = "logout_timestamp")
    private LocalDateTime logoutTimestamp;

    private String device;
    private String browser;

    @Column(name = "ip_address")
    private String ipAddress;

    @Column(name = "session_duration_seconds")
    private Integer sessionDurationSeconds;

    public UUID getUuid() { return uuid; }
    public void setUuid(UUID uuid) { this.uuid = uuid; }

    public UUID getTenantUuid() { return tenantUuid; }
    public void setTenantUuid(UUID tenantUuid) { this.tenantUuid = tenantUuid; }

    public String getUsername() { return username; }
    public void setUsername(String username) { this.username = username; }

    public LocalDateTime getLoginTimestamp() { return loginTimestamp; }
    public void setLoginTimestamp(LocalDateTime loginTimestamp) { this.loginTimestamp = loginTimestamp; }

    public LocalDateTime getLogoutTimestamp() { return logoutTimestamp; }
    public void setLogoutTimestamp(LocalDateTime logoutTimestamp) { this.logoutTimestamp = logoutTimestamp; }

    public String getDevice() { return device; }
    public void setDevice(String device) { this.device = device; }

    public String getBrowser() { return browser; }
    public void setBrowser(String browser) { this.browser = browser; }

    public String getIpAddress() { return ipAddress; }
    public void setIpAddress(String ipAddress) { this.ipAddress = ipAddress; }

    public Integer getSessionDurationSeconds() { return sessionDurationSeconds; }
    public void setSessionDurationSeconds(Integer sessionDurationSeconds) { this.sessionDurationSeconds = sessionDurationSeconds; }
}
