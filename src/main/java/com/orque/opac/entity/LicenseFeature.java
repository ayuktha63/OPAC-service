package com.orque.opac.entity;

import jakarta.persistence.*;
import java.time.LocalDateTime;
import java.time.LocalDate;
import java.util.UUID;

@Entity
@Table(name = "license_feature")
public class LicenseFeature {
    @Id
    @GeneratedValue(strategy = GenerationType.AUTO)
    private UUID uuid;

    @Column(name = "license_product_uuid", nullable = false)
    private UUID licenseProductUuid;

    @Column(name = "feature_name", nullable = false)
    private String featureName;

    @Column(name = "created_timestamp")
    private LocalDateTime createdTimestamp = LocalDateTime.now();

    // Getters and Setters
    public UUID getUuid() { return uuid; }
    public void setUuid(UUID uuid) { this.uuid = uuid; }

    public UUID getLicenseProductUuid() { return licenseProductUuid; }
    public void setLicenseProductUuid(UUID licenseProductUuid) { this.licenseProductUuid = licenseProductUuid; }

    public String getFeatureName() { return featureName; }
    public void setFeatureName(String featureName) { this.featureName = featureName; }

    public LocalDateTime getCreatedTimestamp() { return createdTimestamp; }
    public void setCreatedTimestamp(LocalDateTime createdTimestamp) { this.createdTimestamp = createdTimestamp; }
}
