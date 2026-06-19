package com.orque.opac.entity;

import jakarta.persistence.*;
import java.time.LocalDateTime;
import java.time.LocalDate;
import java.util.UUID;

@Entity
@Table(name = "role_permission")
public class RolePermission {
    @Id
    @GeneratedValue(strategy = GenerationType.AUTO)
    private UUID uuid;

    @Column(name = "role_uuid", nullable = false)
    private UUID roleUuid;

    @Column(name = "access_policy_key", nullable = false)
    private String accessPolicyKey;

    @Column(name = "created_timestamp")
    private LocalDateTime createdTimestamp = LocalDateTime.now();

    public UUID getUuid() { return uuid; }
    public void setUuid(UUID uuid) { this.uuid = uuid; }

    public UUID getRoleUuid() { return roleUuid; }
    public void setRoleUuid(UUID roleUuid) { this.roleUuid = roleUuid; }

    public String getAccessPolicyKey() { return accessPolicyKey; }
    public void setAccessPolicyKey(String accessPolicyKey) { this.accessPolicyKey = accessPolicyKey; }

    public LocalDateTime getCreatedTimestamp() { return createdTimestamp; }
    public void setCreatedTimestamp(LocalDateTime createdTimestamp) { this.createdTimestamp = createdTimestamp; }
}
