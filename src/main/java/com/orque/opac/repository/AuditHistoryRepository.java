package com.orque.opac.repository;

import com.orque.opac.entity.*;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

@Repository
public interface AuditHistoryRepository extends JpaRepository<AuditHistory, UUID> {
    List<AuditHistory> findAllByAuditLogUuid(UUID auditLogUuid);
}
