package com.orque.opac.service;

import com.orque.opac.entity.EmailTemplate;
import com.orque.opac.repository.EmailTemplateRepository;
import org.springframework.boot.ApplicationRunner;
import org.springframework.stereotype.Service;
import java.time.LocalDateTime;

@Service
public class DataInitializationService implements ApplicationRunner {

    private final EmailTemplateRepository emailTemplateRepository;

    public DataInitializationService(EmailTemplateRepository emailTemplateRepository) {
        this.emailTemplateRepository = emailTemplateRepository;
    }

    @Override
    public void run(org.springframework.boot.ApplicationArguments args) throws Exception {
        initializeEmailTemplates();
    }

    private void initializeEmailTemplates() {
        // Tenant Approved Template
        if (emailTemplateRepository.findByTemplateKey("tenant_approved").isEmpty()) {
            EmailTemplate tenantApproved = new EmailTemplate();
            tenantApproved.setTemplateKey("tenant_approved");
            tenantApproved.setName("Tenant Approved");
            tenantApproved.setSubject("Welcome to Orque OPAC - {{tenantName}}");
            tenantApproved.setBody("Dear {{username}},\n\n" +
                "Your tenant \"{{tenantName}}\" has been approved and is now active.\n\n" +
                "Temporary Password: {{tempPassword}}\n" +
                "URL: {{opacUrl}}\n\n" +
                "Best regards,\n" +
                "Orque Team");
            tenantApproved.setActive(true);
            tenantApproved.setCreatedTimestamp(LocalDateTime.now());
            tenantApproved.setUpdatedTimestamp(LocalDateTime.now());
            emailTemplateRepository.save(tenantApproved);
        }

        // License Approved Template
        if (emailTemplateRepository.findByTemplateKey("license_approved").isEmpty()) {
            EmailTemplate licenseApproved = new EmailTemplate();
            licenseApproved.setTemplateKey("license_approved");
            licenseApproved.setName("License Approved");
            licenseApproved.setSubject("License Approved - {{companyName}}");
            licenseApproved.setBody("Dear {{email}},\n\n" +
                "Your license request has been approved.\n\n" +
                "Best regards,\n" +
                "Orque Team");
            licenseApproved.setActive(true);
            licenseApproved.setCreatedTimestamp(LocalDateTime.now());
            licenseApproved.setUpdatedTimestamp(LocalDateTime.now());
            emailTemplateRepository.save(licenseApproved);
        }
    }
}
