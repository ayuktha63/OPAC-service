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

    /**
     * Bumped whenever a seeded template body changes below, so upsertTemplate knows to
     * overwrite a stored HTML copy that predates the fix — plain "starts with '<'" alone
     * can't tell an old HTML revision from the current one.
     */
    private static final String TEMPLATE_VERSION_MARKER = "<!--v2-->";

    private void initializeEmailTemplates() {
        upsertTemplate("tenant_approved", "Tenant Approved",
            "Welcome to Orque OPAC - {{tenantName}}",
            TEMPLATE_VERSION_MARKER + EmailTemplateBuilder.wrap("Your tenant is now active",
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
            TEMPLATE_VERSION_MARKER + EmailTemplateBuilder.wrap("Your license has been approved",
                "<p style=\"margin:0 0 16px;\">Dear Team,</p>"
                + "<p style=\"margin:0 0 16px;\">Your license request for <strong>{{companyName}}</strong> has been "
                + "reviewed and approved. Your license is now active.</p>"
                + "<p style=\"margin:0 0 8px;font-weight:600;\">OPAC License Key</p>"
                + "<div style=\"background:#f9fafb;border:1px solid #e5e7eb;border-radius:6px;padding:14px 16px;"
                + "font-family:'Courier New',monospace;font-size:13px;color:#111827;word-break:break-all;margin:0 0 16px;\">"
                + "{{licenseKey}}</div>"
                + "<p style=\"margin:16px 0 0;color:#6b7280;font-size:13px;\">Log in to the OPAC application and "
                + "navigate to Tenant Configuration &rarr; Add License, then paste the license key above.</p>"));
    }

    /**
     * Inserts the template if missing, or refreshes it if the stored copy predates the
     * HTML redesign (plain-text, no opening tag) or an older HTML revision (missing the
     * current TEMPLATE_VERSION_MARKER). Note: this means a genuine admin customization made
     * through the management UI would also get overwritten on the next deploy, since it too
     * lacks the marker — acceptable for now since there's no template-editing UI yet.
     */
    private void upsertTemplate(String key, String name, String subject, String htmlBody) {
        EmailTemplate template = emailTemplateRepository.findByTemplateKey(key).orElseGet(EmailTemplate::new);
        boolean isNew = template.getUuid() == null;
        boolean isLegacyPlainText = !isNew && template.getBody() != null && !template.getBody().stripLeading().startsWith("<");
        boolean isStaleVersion = !isNew && template.getBody() != null && !template.getBody().contains(TEMPLATE_VERSION_MARKER);
        if (!isNew && !isLegacyPlainText && !isStaleVersion) {
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
