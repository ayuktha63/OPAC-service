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
        upsertTemplate("tenant_approved", "Tenant Approved",
            "Welcome to Orque OPAC - {{tenantName}}",
            EmailTemplateBuilder.wrap("Your tenant is now active",
                "<p style=\"margin:0 0 16px;\">Dear {{username}},</p>"
                + "<p style=\"margin:0 0 16px;\">We're pleased to let you know that your tenant "
                + "<strong>{{tenantName}}</strong> has been approved and is now active on the Orque Platform.</p>"
                + "<p style=\"margin:0 0 8px;font-weight:600;\">Your login credentials</p>"
                + EmailTemplateBuilder.credentialsBox(
                      EmailTemplateBuilder.credentialRow("Tenant", "{{tenantName}}")
                    + EmailTemplateBuilder.credentialRow("Username", "{{username}}")
                    + EmailTemplateBuilder.credentialRow("Temporary Password", "{{tempPassword}}"))
                + EmailTemplateBuilder.button("Log in to OPAC", "{{opacUrl}}")
                + "<p style=\"margin:16px 0 0;color:#6b7280;font-size:13px;\">For security, please change your "
                + "password immediately after your first login.</p>"));

        upsertTemplate("license_approved", "License Approved",
            "License Approved - {{companyName}}",
            EmailTemplateBuilder.wrap("Your license has been approved",
                "<p style=\"margin:0 0 16px;\">Dear Team,</p>"
                + "<p style=\"margin:0 0 16px;\">Your license request for <strong>{{companyName}}</strong> has been "
                + "reviewed and approved. Your license is now active.</p>"
                + "<p style=\"margin:16px 0 0;color:#6b7280;font-size:13px;\">Log in to Tenant Configuration to view "
                + "or apply your license key.</p>"));
    }

    /**
     * Inserts the template if missing, or refreshes it if the stored copy predates the
     * HTML redesign (plain-text bodies never contain an opening tag). Templates that an
     * admin has since edited via the management UI will already start with "<" and are
     * left untouched.
     */
    private void upsertTemplate(String key, String name, String subject, String htmlBody) {
        EmailTemplate template = emailTemplateRepository.findByTemplateKey(key).orElseGet(EmailTemplate::new);
        boolean isNew = template.getUuid() == null;
        boolean isLegacyPlainText = !isNew && template.getBody() != null && !template.getBody().stripLeading().startsWith("<");
        if (!isNew && !isLegacyPlainText) {
            return;
        }
        template.setTemplateKey(key);
        template.setName(name);
        template.setSubject(subject);
        template.setBody(htmlBody);
        template.setActive(true);
        template.setUpdatedTimestamp(LocalDateTime.now());
        if (isNew) {
            template.setCreatedTimestamp(LocalDateTime.now());
        }
        emailTemplateRepository.save(template);
    }
}
