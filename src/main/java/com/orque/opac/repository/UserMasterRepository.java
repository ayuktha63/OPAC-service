package com.orque.opac.repository;

import com.orque.opac.entity.*;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

@Repository
public interface UserMasterRepository extends JpaRepository<UserMaster, UUID> {
    Optional<UserMaster> findByUsername(String username);
    List<UserMaster> findAllByOrderByCreatedTimestampDesc();
    List<UserMaster> findByTenantUuidAndStatus(UUID tenantUuid, String status);
    Optional<UserMaster> findByUsernameAndTenantUuid(String username, UUID tenantUuid);
}
