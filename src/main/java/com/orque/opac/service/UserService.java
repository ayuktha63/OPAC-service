package com.orque.opac.service;

import com.orque.opac.entity.LicenseMaster;
import com.orque.opac.entity.LicenseProduct;
import com.orque.opac.entity.UserMaster;
import com.orque.opac.entity.TenantMaster;
import com.orque.opac.repository.LicenseMasterRepository;
import com.orque.opac.repository.LicenseProductRepository;
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
    private final LicenseMasterRepository licenseMasterRepository;
    private final LicenseProductRepository licenseProductRepository;
    private final Services.AuditService auditService;

    public UserService(UserMasterRepository userMasterRepository,
                       TenantMasterRepository tenantMasterRepository,
                       LicenseMasterRepository licenseMasterRepository,
                       LicenseProductRepository licenseProductRepository,
                       Services.AuditService auditService) {
        this.userMasterRepository = userMasterRepository;
        this.tenantMasterRepository = tenantMasterRepository;
        this.licenseMasterRepository = licenseMasterRepository;
        this.licenseProductRepository = licenseProductRepository;
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
            enforceUserSeatLimit(tenantUuid);
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

    /**
     * Caps the number of business users a tenant can create at the seat count on its
     * master license (the highest per-product userLimit — a tenant's overall headcount
     * cap, not a sum across products). Tenants with no license/products on file are left
     * unrestricted rather than silently locking out existing installs without license data.
     */
    private void enforceUserSeatLimit(UUID tenantUuid) {
        LicenseMaster license = licenseMasterRepository.findFirstByTenantUuidOrderByCreatedTimestampDesc(tenantUuid)
                .orElse(null);
        if (license == null) return;

        List<LicenseProduct> products = licenseProductRepository.findAllByLicenseUuid(license.getUuid());
        int seatLimit = products.stream()
                .map(LicenseProduct::getUserLimit)
                .filter(java.util.Objects::nonNull)
                .max(Integer::compareTo)
                .orElse(0);
        if (seatLimit <= 0) return;

        long activeUsers = userMasterRepository.findByTenantUuid(tenantUuid).stream()
                .filter(u -> !"INACTIVE".equalsIgnoreCase(u.getStatus()))
                .count();
        if (activeUsers >= seatLimit) {
            throw new IllegalArgumentException(
                    "User limit reached: your license allows " + seatLimit + " user(s). "
                    + "Deactivate an existing user or upgrade your license to add more.");
        }
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
