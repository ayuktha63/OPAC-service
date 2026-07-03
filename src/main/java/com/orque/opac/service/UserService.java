package com.orque.opac.service;

import com.orque.opac.entity.UserMaster;
import com.orque.opac.entity.TenantMaster;
import com.orque.opac.repository.UserMasterRepository;
import com.orque.opac.repository.TenantMasterRepository;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

@Service
public class UserService {

    private final UserMasterRepository userMasterRepository;
    private final TenantMasterRepository tenantMasterRepository;
    private final Services.AuditService auditService;

    public UserService(UserMasterRepository userMasterRepository,
                       TenantMasterRepository tenantMasterRepository,
                       Services.AuditService auditService) {
        this.userMasterRepository = userMasterRepository;
        this.tenantMasterRepository = tenantMasterRepository;
        this.auditService = auditService;
    }

    public List<UserMaster> getAllUsers() {
        return userMasterRepository.findAllByOrderByCreatedTimestampDesc();
    }

    /** Strict tenant-scoped query — used by all non-Orque callers. */
    public List<UserMaster> getTenantUsers(UUID tenantUuid) {
        return userMasterRepository.findByTenantUuid(tenantUuid);
    }

    public Optional<UserMaster> getUserById(UUID uuid) {
        return userMasterRepository.findById(uuid);
    }

    public Optional<UserMaster> getUserByUsername(String username) {
        return userMasterRepository.findByUsername(username);
    }

    @Transactional
    public UserMaster saveUser(UserMaster user, String actor) {
        if (user.getUuid() != null) {
            // Update flow
            UserMaster existing = userMasterRepository.findById(user.getUuid())
                    .orElseThrow(() -> new IllegalArgumentException("User not found: " + user.getUuid()));
            existing.setUsername(user.getUsername());
            existing.setEmail(user.getEmail());
            if (user.getStatus() != null) {
                existing.setStatus(user.getStatus());
            }
            existing.setFirstName(user.getFirstName());
            existing.setLastName(user.getLastName());
            existing.setRole(user.getRole());
            existing.setTenantName(user.getTenantName());
            existing.setContactNumber(user.getContactNumber());
            if (user.getAssignedProducts() != null) {
                existing.setAssignedProducts(user.getAssignedProducts());
            }
            existing.setUpdatedTimestamp(LocalDateTime.now());
            
            UserMaster saved = userMasterRepository.save(existing);
            auditService.logAuditEvent("UPDATE", "User", actor, "user_master", saved.getUuid(), "localhost");
            return saved;
        } else {
            // Create flow
            if (userMasterRepository.findByUsername(user.getUsername()).isPresent()) {
                throw new IllegalArgumentException("Username already exists: " + user.getUsername());
            }

            // Resolve Tenant UUID
            UUID tenantUuid = resolveTenantUuid(user.getTenantName());
            user.setTenantUuid(tenantUuid);
            user.setCreatedTimestamp(LocalDateTime.now());
            user.setUpdatedTimestamp(LocalDateTime.now());
            if (user.getStatus() == null) {
                user.setStatus("Active");
            }

            UserMaster saved = userMasterRepository.save(user);
            auditService.logAuditEvent("INSERT", "User", actor, "user_master", saved.getUuid(), "localhost");
            return saved;
        }
    }

    @Transactional
    public void deactivateUser(UUID userId, String actor) {
        UserMaster user = userMasterRepository.findById(userId)
                .orElseThrow(() -> new IllegalArgumentException("User not found: " + userId));
        user.setStatus("INACTIVE");
        userMasterRepository.save(user);
        auditService.logAuditEvent("DEACTIVATE", "User", actor, "user_master", userId, "localhost");
    }

    @Transactional
    public void activateUser(UUID userId, String actor) {
        UserMaster user = userMasterRepository.findById(userId)
                .orElseThrow(() -> new IllegalArgumentException("User not found: " + userId));
        user.setStatus("ACTIVE");
        userMasterRepository.save(user);
        auditService.logAuditEvent("ACTIVATE", "User", actor, "user_master", userId, "localhost");
    }

    private UUID resolveTenantUuid(String tenantName) {
        if (tenantName == null || tenantName.isBlank()) {
            throw new IllegalArgumentException("Tenant name is required to create a user.");
        }
        Optional<TenantMaster> byName = tenantMasterRepository.findByTenantName(tenantName);
        if (byName.isPresent()) return byName.get().getUuid();
        // Also match on company name for legacy data
        return tenantMasterRepository.findByCompanyName(tenantName)
                .map(TenantMaster::getUuid)
                .orElseThrow(() -> new IllegalArgumentException(
                        "Tenant not found: '" + tenantName + "'. Ensure the tenant exists before creating users."));
    }
}
