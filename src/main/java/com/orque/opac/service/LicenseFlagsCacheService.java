package com.orque.opac.service;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.orque.opac.config.RedisCacheConfig;
import com.orque.opac.entity.TenantConfiguration;
import com.orque.opac.repository.TenantConfigurationRepository;
import org.springframework.cache.annotation.CacheEvict;
import org.springframework.cache.annotation.Cacheable;
import org.springframework.stereotype.Service;

import java.util.Map;
import java.util.UUID;

/**
 * Derives the tenant's license flags (master license active / CRM product licensed)
 * from its settings JSON. Cached in Redis since this is read on every login and every
 * /api/my-crm-license poll, but only changes when a license is activated or renewed
 * (i.e. rare, human-initiated writes) — a few minutes of staleness is harmless.
 *
 * This lives in its own Spring bean (not a private method on AdminController) because
 * Spring's proxy-based @Cacheable cannot intercept self-invoked private/internal calls.
 */
@Service
public class LicenseFlagsCacheService {

    private static final String KEY_LICENSED_PRODUCTS = "licensedProducts";

    private final TenantConfigurationRepository tenantConfigurationRepository;
    private final ObjectMapper objectMapper;

    public LicenseFlagsCacheService(TenantConfigurationRepository tenantConfigurationRepository,
                                     ObjectMapper objectMapper) {
        this.tenantConfigurationRepository = tenantConfigurationRepository;
        this.objectMapper = objectMapper;
    }

    public record LicenseFlags(boolean hasActiveLicense, boolean hasCrmLicense) {
    }

    @Cacheable(value = RedisCacheConfig.CACHE_LICENSE_FLAGS, key = "#tenantUuid")
    @SuppressWarnings("unchecked")
    public LicenseFlags resolveLicenseFlags(UUID tenantUuid) {
        try {
            TenantConfiguration cfg = tenantConfigurationRepository.findByTenantUuid(tenantUuid).orElse(null);
            if (cfg == null || cfg.getSettingsJson() == null || cfg.getSettingsJson().isBlank()) {
                return new LicenseFlags(false, false);
            }
            Map<String, Object> settings = objectMapper.readValue(cfg.getSettingsJson(), new TypeReference<>() {});
            Object lp = settings.get(KEY_LICENSED_PRODUCTS);
            boolean hasActive = lp instanceof Map && !((Map<?, ?>) lp).isEmpty();
            boolean hasCrm = false;
            if (lp instanceof Map) {
                Map<String, Object> products = (Map<String, Object>) lp;
                hasCrm = products.containsKey("crm") || products.containsKey("CRM");
            }
            return new LicenseFlags(hasActive, hasCrm);
        } catch (Exception ignored) {
            return new LicenseFlags(false, false);
        }
    }

    /** Call after any write to a tenant's settings JSON (license apply/renew/activate). */
    @CacheEvict(value = RedisCacheConfig.CACHE_LICENSE_FLAGS, key = "#tenantUuid")
    public void evict(UUID tenantUuid) {
        // no-op body — the eviction happens via the annotation
    }
}
