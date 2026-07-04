package com.orque.opac.service;

import com.orque.opac.config.RedisCacheConfig;
import com.orque.opac.entity.TenantMaster;
import com.orque.opac.repository.TenantMasterRepository;
import org.springframework.cache.annotation.CacheEvict;
import org.springframework.cache.annotation.Cacheable;
import org.springframework.stereotype.Service;

import java.util.UUID;

/**
 * Caches the tenant-by-UUID lookup used by resolveScope(), which runs once per
 * request across ~24 endpoints. Tenant master data changes only on tenant
 * create/update (a rare, human-initiated action), so a 60-minute TTL is safe.
 *
 * Lives in its own bean (not a private method on AdminController) because Spring's
 * proxy-based @Cacheable cannot intercept self-invoked private/internal calls.
 */
@Service
public class TenantLookupService {

    private final TenantMasterRepository tenantMasterRepository;

    public TenantLookupService(TenantMasterRepository tenantMasterRepository) {
        this.tenantMasterRepository = tenantMasterRepository;
    }

    @Cacheable(value = RedisCacheConfig.CACHE_TENANT_SCOPE, key = "#tenantUuid", unless = "#result == null")
    public TenantMaster findTenantById(UUID tenantUuid) {
        return tenantMasterRepository.findById(tenantUuid).orElse(null);
    }

    /** Call after any create/update of a tenant_master row so stale data isn't served. */
    @CacheEvict(value = RedisCacheConfig.CACHE_TENANT_SCOPE, key = "#tenantUuid")
    public void evict(UUID tenantUuid) {
        // no-op body — the eviction happens via the annotation
    }
}
