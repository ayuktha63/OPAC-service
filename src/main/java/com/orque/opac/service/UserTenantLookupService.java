package com.orque.opac.service;

import com.orque.opac.config.RedisCacheConfig;
import com.orque.opac.entity.UserMaster;
import com.orque.opac.repository.UserMasterRepository;
import org.springframework.cache.annotation.Cacheable;
import org.springframework.stereotype.Service;

import java.util.UUID;

/**
 * Caches username → tenant UUID, resolved on every audit log call (20+ call sites
 * across the app). This mapping is stable unless a user is reassigned to a
 * different tenant, so a 60-minute TTL is safe.
 *
 * Lives in its own bean (not a private method on AuditService) because Spring's
 * proxy-based @Cacheable cannot intercept self-invoked private/internal calls.
 */
@Service
public class UserTenantLookupService {

    private final UserMasterRepository userMasterRepository;

    public UserTenantLookupService(UserMasterRepository userMasterRepository) {
        this.userMasterRepository = userMasterRepository;
    }

    @Cacheable(value = RedisCacheConfig.CACHE_USER_TENANT, key = "#username", unless = "#result == null")
    public UUID findTenantUuidByUsername(String username) {
        return userMasterRepository.findByUsername(username)
                .map(UserMaster::getTenantUuid)
                .orElse(null);
    }
}
