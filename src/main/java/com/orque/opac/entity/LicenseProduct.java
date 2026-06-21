package com.orque.opac.entity;

import jakarta.persistence.*;
import java.time.LocalDateTime;
import java.time.LocalDate;
import java.util.UUID;

@Entity
@Table(name = "license_product")
public class LicenseProduct {
    @Id
    @GeneratedValue(strategy = GenerationType.AUTO)
    private UUID uuid;

    @Column(name = "license_uuid")
    private UUID licenseUuid;

    @Column(name = "license_request_uuid")
    private UUID licenseRequestUuid;

    @Column(name = "product_name", nullable = false)
    private String productName;

    @Column(name = "start_date", nullable = false)
    private LocalDate startDate;

    @Column(name = "end_date", nullable = false)
    private LocalDate endDate;

    @Column(name = "user_limit", nullable = false)
    private Integer userLimit = 1;

    @Column(name = "concurrent_limit", nullable = false)
    private Integer concurrentLimit = 1;

    @Column(name = "grace_period")
    private Integer gracePeriod;

    @Column(name = "created_timestamp")
    private LocalDateTime createdTimestamp = LocalDateTime.now();

    // Getters and Setters
    public UUID getUuid() { return uuid; }
    public void setUuid(UUID uuid) { this.uuid = uuid; }

    public UUID getLicenseUuid() { return licenseUuid; }
    public void setLicenseUuid(UUID licenseUuid) { this.licenseUuid = licenseUuid; }

    public UUID getLicenseRequestUuid() { return licenseRequestUuid; }
    public void setLicenseRequestUuid(UUID licenseRequestUuid) { this.licenseRequestUuid = licenseRequestUuid; }

    public String getProductName() { return productName; }
    public void setProductName(String productName) { this.productName = productName; }

    public LocalDate getStartDate() { return startDate; }
    public void setStartDate(LocalDate startDate) { this.startDate = startDate; }

    public LocalDate getEndDate() { return endDate; }
    public void setEndDate(LocalDate endDate) { this.endDate = endDate; }

    public Integer getUserLimit() { return userLimit; }
    public void setUserLimit(Integer userLimit) { this.userLimit = userLimit; }

    public Integer getConcurrentLimit() { return concurrentLimit; }
    public void setConcurrentLimit(Integer concurrentLimit) { this.concurrentLimit = concurrentLimit; }

    public Integer getGracePeriod() { return gracePeriod; }
    public void setGracePeriod(Integer gracePeriod) { this.gracePeriod = gracePeriod; }

    public LocalDateTime getCreatedTimestamp() { return createdTimestamp; }
    public void setCreatedTimestamp(LocalDateTime createdTimestamp) { this.createdTimestamp = createdTimestamp; }
}
