package com.orque.opac.service;

import com.orque.opac.entity.RoleMaster;
import com.orque.opac.entity.TenantMaster;
import com.orque.opac.repository.RoleMasterRepository;
import com.orque.opac.repository.TenantMasterRepository;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

@Service
public class RoleService {

    private final RoleMasterRepository roleMasterRepository;
    private final TenantMasterRepository tenantMasterRepository;
    private final Services.AuditService auditService;

    public RoleService(RoleMasterRepository roleMasterRepository,
                       TenantMasterRepository tenantMasterRepository,
                       Services.AuditService auditService) {
        this.roleMasterRepository = roleMasterRepository;
        this.tenantMasterRepository = tenantMasterRepository;
        this.auditService = auditService;
    }

    public List<RoleMaster> getAllRoles() {
        return roleMasterRepository.findAllByOrderByCreatedTimestampDesc();
    }

    public Optional<RoleMaster> getRoleById(UUID uuid) {
        return roleMasterRepository.findById(uuid);
    }

    @Transactional
    public RoleMaster saveRole(RoleMaster role, String actor) {
        if (role.getUuid() != null) {
            // Update flow
            RoleMaster existing = roleMasterRepository.findById(role.getUuid())
                    .orElseThrow(() -> new IllegalArgumentException("Role not found: " + role.getUuid()));
            existing.setRoleName(role.getRoleName());
            existing.setRoleCode(role.getRoleCode());
            existing.setRoleType(role.getRoleType());
            existing.setDescription(role.getDescription());
            if (role.getStatus() != null) {
                existing.setStatus(role.getStatus());
            }
            existing.setTenantName(role.getTenantName());

            RoleMaster saved = roleMasterRepository.save(existing);
            auditService.logAuditEvent("UPDATE", "Role", actor, "role_master", saved.getUuid(), "localhost");
            return saved;
        } else {
            // Create flow
            UUID tenantUuid = resolveTenantUuid(role.getTenantName());
            if (roleMasterRepository.findByRoleNameAndTenantUuid(role.getRoleName(), tenantUuid).isPresent()) {
                throw new IllegalArgumentException("Role name already exists for this tenant: " + role.getRoleName());
            }

            role.setTenantUuid(tenantUuid);
            role.setCreatedTimestamp(LocalDateTime.now());
            if (role.getStatus() == null) {
                role.setStatus("ACTIVE");
            }
            role.setIsSystemDefault(false);

            RoleMaster saved = roleMasterRepository.save(role);
            auditService.logAuditEvent("INSERT", "Role", actor, "role_master", saved.getUuid(), "localhost");
            return saved;
        }
    }

    @Transactional
    public void deleteRole(UUID roleId, String actor) {
        roleMasterRepository.deleteById(roleId);
        auditService.logAuditEvent("DELETE", "Role", actor, "role_master", roleId, "localhost");
    }

    private UUID resolveTenantUuid(String tenantName) {
        if (tenantName != null && !tenantName.isEmpty()) {
            Optional<TenantMaster> tenantOpt = tenantMasterRepository.findByTenantName(tenantName);
            if (tenantOpt.isPresent()) {
                return tenantOpt.get().getUuid();
            } else {
                List<TenantMaster> allTenants = tenantMasterRepository.findAll();
                for (TenantMaster tm : allTenants) {
                    if (tenantName.equalsIgnoreCase(tm.getCompanyName())) {
                        return tm.getUuid();
                    }
                }
            }
        }

        List<TenantMaster> fallbackList = tenantMasterRepository.findAll();
        if (!fallbackList.isEmpty()) {
            return fallbackList.get(0).getUuid();
        }
        throw new IllegalStateException("No active tenant found in system.");
    }
}
