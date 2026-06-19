package com.orque.opac.entity;

import jakarta.persistence.*;
import java.time.LocalDateTime;
import java.time.LocalDate;
import java.util.UUID;

@Entity
@Table(name = "license_master")
public class LicenseMaster {
    @Id
    @GeneratedValue(strategy = GenerationType.AUTO)
    private UUID uuid;

    @Column(name = "tenant_uuid", nullable = false)
    private UUID tenantUuid;

    @Column(name = "license_key", nullable = false)
    private String licenseKey;

    @Column(nullable = false)
    private String status = "Active";

    @Column(name = "expiry_date", nullable = false)
    private LocalDate expiryDate;

    @Column(name = "license_version", nullable = false)
    private String licenseVersion = "1.0";

    @Column(name = "issue_date", nullable = false)
    private LocalDate issueDate = LocalDate.now();

    @Column(name = "license_type", nullable = false)
    private String licenseType = "Standard";

    @Column(name = "digital_signature", nullable = false)
    private String digitalSignature;

    @Column(name = "created_timestamp")
    private LocalDateTime createdTimestamp = LocalDateTime.now();

    @Column(name = "updated_timestamp")
    private LocalDateTime updatedTimestamp = LocalDateTime.now();

    // Getters and Setters
    public UUID getUuid() { return uuid; }
    public void setUuid(UUID uuid) { this.uuid = uuid; }

    public UUID getTenantUuid() { return tenantUuid; }
    public void setTenantUuid(UUID tenantUuid) { this.tenantUuid = tenantUuid; }

    public String getLicenseKey() { return licenseKey; }
    public void setLicenseKey(String licenseKey) { this.licenseKey = licenseKey; }

    public String getStatus() { return status; }
    public void setStatus(String status) { this.status = status; }

    public LocalDate getExpiryDate() { return expiryDate; }
    public void setExpiryDate(LocalDate expiryDate) { this.expiryDate = expiryDate; }

    public String getLicenseVersion() { return licenseVersion; }
    public void setLicenseVersion(String licenseVersion) { this.licenseVersion = licenseVersion; }

    public LocalDate getIssueDate() { return issueDate; }
    public void setIssueDate(LocalDate issueDate) { this.issueDate = issueDate; }

    public String getLicenseType() { return licenseType; }
    public void setLicenseType(String licenseType) { this.licenseType = licenseType; }

    public String getDigitalSignature() { return digitalSignature; }
    public void setDigitalSignature(String digitalSignature) { this.digitalSignature = digitalSignature; }

    public LocalDateTime getCreatedTimestamp() { return createdTimestamp; }
    public void setCreatedTimestamp(LocalDateTime createdTimestamp) { this.createdTimestamp = createdTimestamp; }

    public LocalDateTime getUpdatedTimestamp() { return updatedTimestamp; }
    public void setUpdatedTimestamp(LocalDateTime updatedTimestamp) { this.updatedTimestamp = updatedTimestamp; }
}
