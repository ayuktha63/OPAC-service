package com.orque.opac.entity;

import jakarta.persistence.*;
import java.time.LocalDateTime;
import java.time.LocalDate;
import java.util.UUID;

@Entity
@Table(name = "license_request")
public class LicenseRequest {
    @Id
    @GeneratedValue(strategy = GenerationType.AUTO)
    private UUID uuid;

    @Column(name = "request_id", unique = true, nullable = false)
    private String requestId;

    @Column(name = "tenant_uuid")
    private UUID tenantUuid;

    @Column(name = "company_name", nullable = false)
    private String companyName;

    @Column(nullable = false)
    private String email;

    @Column(name = "requested_by", nullable = false)
    private String requestedBy;

    @Column(nullable = false)
    private String status = "Draft";

    @Column(name = "request_type", nullable = false)
    private String requestType = "New";

    @Column(name = "created_timestamp")
    private LocalDateTime createdTimestamp = LocalDateTime.now();

    @Column(name = "updated_timestamp")
    private LocalDateTime updatedTimestamp = LocalDateTime.now();

    // Getters and Setters
    public UUID getUuid() { return uuid; }
    public void setUuid(UUID uuid) { this.uuid = uuid; }

    public String getRequestId() { return requestId; }
    public void setRequestId(String requestId) { this.requestId = requestId; }

    public UUID getTenantUuid() { return tenantUuid; }
    public void setTenantUuid(UUID tenantUuid) { this.tenantUuid = tenantUuid; }

    public String getCompanyName() { return companyName; }
    public void setCompanyName(String companyName) { this.companyName = companyName; }

    public String getEmail() { return email; }
    public void setEmail(String email) { this.email = email; }

    public String getRequestedBy() { return requestedBy; }
    public void setRequestedBy(String requestedBy) { this.requestedBy = requestedBy; }

    public String getStatus() { return status; }
    public void setStatus(String status) { this.status = status; }

    public String getRequestType() { return requestType; }
    public void setRequestType(String requestType) { this.requestType = requestType; }

    public LocalDateTime getCreatedTimestamp() { return createdTimestamp; }
    public void setCreatedTimestamp(LocalDateTime createdTimestamp) { this.createdTimestamp = createdTimestamp; }

    public LocalDateTime getUpdatedTimestamp() { return updatedTimestamp; }
    public void setUpdatedTimestamp(LocalDateTime updatedTimestamp) { this.updatedTimestamp = updatedTimestamp; }
}
