package com.orque.opac.repository;

import com.orque.opac.entity.*;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

@Repository
public interface ApprovalRequestRepository extends JpaRepository<ApprovalRequest, UUID> {
    /**
     * Returns the most recent approval request for a reference. Tolerant of duplicate
     * rows (e.g. from a record being submitted more than once) — always picks the latest.
     */
    Optional<ApprovalRequest> findFirstByReferenceUuidOrderByCreatedTimestampDesc(UUID referenceUuid);
}
