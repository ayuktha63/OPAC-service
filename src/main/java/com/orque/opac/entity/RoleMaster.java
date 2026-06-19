package com.orque.opac.entity;

import jakarta.persistence.*;
import java.time.LocalDateTime;
import java.time.LocalDate;
import java.util.UUID;

@Entity
@Table(name = "role_master")
public class RoleMaster {
    @Id
    @GeneratedValue(strategy = GenerationType.AUTO)
    private UUID uuid;

    @Column(name = "tenant_uuid", nullable = false)
    private UUID tenantUuid;

    @Column(name = "role_name", nullable = false)
    private String roleName;

    @Column(name = "is_system_default")
    private Boolean isSystemDefault = false;

    @Column(name = "created_timestamp")
    private LocalDateTime createdTimestamp = LocalDateTime.now();

    public UUID getUuid() { return uuid; }
    public void setUuid(UUID uuid) { this.uuid = uuid; }

    public UUID getTenantUuid() { return tenantUuid; }
    public void setTenantUuid(UUID tenantUuid) { this.tenantUuid = tenantUuid; }

    public String getRoleName() { return roleName; }
    public void setRoleName(String roleName) { this.roleName = roleName; }

    public Boolean getIsSystemDefault() { return isSystemDefault; }
    public void setIsSystemDefault(Boolean isSystemDefault) { this.isSystemDefault = isSystemDefault; }

    public LocalDateTime getCreatedTimestamp() { return createdTimestamp; }
    public void setCreatedTimestamp(LocalDateTime createdTimestamp) { this.createdTimestamp = createdTimestamp; }
}
