package com.orque.opac.repository;

import com.orque.opac.entity.EmailTemplate;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;
import java.util.Optional;
import java.util.UUID;

@Repository
public interface EmailTemplateRepository extends JpaRepository<EmailTemplate, UUID> {
    Optional<EmailTemplate> findByTemplateKey(String templateKey);
    Optional<EmailTemplate> findByTemplateKeyAndActiveTrue(String templateKey);
}
