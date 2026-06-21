package com.orque.opac.service;

import com.orque.opac.entity.EmailQueue;
import com.orque.opac.entity.EmailTemplate;
import com.orque.opac.repository.EmailQueueRepository;
import com.orque.opac.repository.EmailTemplateRepository;
import jakarta.mail.internet.MimeMessage;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.mail.javamail.JavaMailSender;
import org.springframework.mail.javamail.MimeMessageHelper;
import org.springframework.stereotype.Service;

import java.time.LocalDateTime;
import java.util.Map;
import java.util.Optional;

@Service
public class EmailService {

    private static final Logger log = LoggerFactory.getLogger(EmailService.class);

    private final JavaMailSender mailSender;
    private final EmailTemplateRepository emailTemplateRepository;
    private final EmailQueueRepository emailQueueRepository;

    @Value("${app.mail.from:noreply@orque.com}")
    private String fromAddress;

    @Value("${app.mail.from-name:Orque Platform}")
    private String fromName;

    public EmailService(JavaMailSender mailSender,
                        EmailTemplateRepository emailTemplateRepository,
                        EmailQueueRepository emailQueueRepository) {
        this.mailSender = mailSender;
        this.emailTemplateRepository = emailTemplateRepository;
        this.emailQueueRepository = emailQueueRepository;
    }

    /**
     * Send an email using a stored template. Placeholders in subject/body are replaced
     * with values from the provided map. Falls back to plain text if template not found.
     */
    public void sendFromTemplate(String templateKey, String toEmail, String ccEmails,
                                  Map<String, String> variables) {
        Optional<EmailTemplate> opt = emailTemplateRepository.findByTemplateKeyAndActiveTrue(templateKey);

        String subject;
        String body;

        if (opt.isPresent()) {
            subject = replacePlaceholders(opt.get().getSubject(), variables);
            body    = replacePlaceholders(opt.get().getBody(),    variables);
        } else {
            subject = variables.getOrDefault("subject", "Orque Platform Notification");
            body    = variables.getOrDefault("body",    "No template found for key: " + templateKey);
        }

        sendEmail(toEmail, ccEmails, subject, body);
    }

    /**
     * Send a raw email directly (used by the Share dialog endpoint).
     */
    public void sendEmail(String toEmail, String ccEmails, String subject, String body) {
        EmailQueue queue = new EmailQueue();
        queue.setToEmail(toEmail);
        queue.setCcEmails(ccEmails);
        queue.setSubject(subject);
        queue.setBody(body);

        try {
            MimeMessage message = mailSender.createMimeMessage();
            MimeMessageHelper helper = new MimeMessageHelper(message, false, "UTF-8");
            helper.setFrom(fromAddress, fromName);
            helper.setTo(toEmail);
            if (ccEmails != null && !ccEmails.isBlank()) {
                helper.setCc(ccEmails.split(","));
            }
            helper.setSubject(subject);
            helper.setText(body, false);
            mailSender.send(message);

            queue.setStatus("Sent");
            queue.setSentTimestamp(LocalDateTime.now());
            log.info("Email sent to {} — subject: {}", toEmail, subject);
        } catch (Exception e) {
            queue.setStatus("Failed");
            queue.setAttempts(1);
            log.error("Failed to send email to {}: {}", toEmail, e.getMessage());
        }

        emailQueueRepository.save(queue);
    }

    private String replacePlaceholders(String template, Map<String, String> variables) {
        if (template == null) return "";
        String result = template;
        for (Map.Entry<String, String> entry : variables.entrySet()) {
            result = result.replace("{{" + entry.getKey() + "}}", entry.getValue() != null ? entry.getValue() : "");
        }
        return result;
    }
}
