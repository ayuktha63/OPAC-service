package com.orque.opac.repository;

import com.orque.opac.entity.*;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

@Repository
public interface TenantRequestRepository extends JpaRepository<TenantRequest, UUID> {
    List<TenantRequest> findAllByStatusOrderByCreatedTimestampDesc(String status);
    List<TenantRequest> findAllByOrderByCreatedTimestampDesc();
    Optional<TenantRequest> findByTenantName(String tenantName);
}
