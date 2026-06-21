package com.orque.opac.repository;

import com.orque.opac.entity.*;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

@Repository
public interface RolePermissionRepository extends JpaRepository<RolePermission, UUID> {
    List<RolePermission> findAllByRoleUuid(UUID roleUuid);
    void deleteAllByRoleUuid(UUID roleUuid);
    Optional<RolePermission> findByRoleUuidAndAccessPolicyKey(UUID roleUuid, String accessPolicyKey);
}
