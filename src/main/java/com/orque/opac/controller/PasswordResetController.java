package com.orque.opac.controller;

import com.orque.opac.entity.PasswordResetToken;
import com.orque.opac.entity.UserMaster;
import com.orque.opac.repository.PasswordResetTokenRepository;
import com.orque.opac.repository.UserMasterRepository;
import com.orque.opac.service.EmailService;
import com.orque.opac.service.Services;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.time.LocalDateTime;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

/**
 * Self-service "forgot password" flow, separate from AdminController's
 * admin-driven password resets. Always returns a generic success message from
 * /forgot-password regardless of whether the account was found, so this can't
 * be used to enumerate registered emails/usernames.
 */
@RestController
@RequestMapping("/api/auth")
public class PasswordResetController {

    private static final long TOKEN_VALIDITY_MINUTES = 30;
    private static final String GENERIC_MESSAGE =
            "If an account with that username or email exists, a password reset link has been sent.";

    private final UserMasterRepository userMasterRepository;
    private final PasswordResetTokenRepository passwordResetTokenRepository;
    private final EmailService emailService;
    private final Services.PasswordService passwordService;

    @Value("${opac.frontend-url}")
    private String opacFrontendUrl;

    @Value("${crm.app-url}")
    private String crmAppUrl;

    public PasswordResetController(UserMasterRepository userMasterRepository,
                                    PasswordResetTokenRepository passwordResetTokenRepository,
                                    EmailService emailService,
                                    Services.PasswordService passwordService) {
        this.userMasterRepository = userMasterRepository;
        this.passwordResetTokenRepository = passwordResetTokenRepository;
        this.emailService = emailService;
        this.passwordService = passwordService;
    }

    @PostMapping("/forgot-password")
    public ResponseEntity<Map<String, Object>> forgotPassword(@RequestBody Map<String, String> body) {
        String usernameOrEmail = body.get("usernameOrEmail");
        if (usernameOrEmail == null || usernameOrEmail.isBlank()) {
            return ResponseEntity.badRequest().body(Map.of("success", false, "message", "Username or email is required."));
        }

        // "source" tells us which app's frontend to link back to — CRM proxies its own
        // forgot-password call through here (OPAC owns the real password/identity), but
        // the emailed link must land the user on CRM's reset screen, not OPAC's. Only
        // ever chooses between the two server-configured URLs below — never a
        // client-supplied one, so this can't be used as an open redirect.
        boolean fromCrm = "crm".equalsIgnoreCase(body.get("source"));
        String frontendUrl = fromCrm ? crmAppUrl : opacFrontendUrl;
        String productLabel = fromCrm ? "CRM" : "OPAC";

        Optional<UserMaster> userOpt = userMasterRepository.findByUsername(usernameOrEmail.trim())
                .or(() -> userMasterRepository.findByEmailIgnoreCase(usernameOrEmail.trim()));

        userOpt.ifPresent(user -> {
            String token = UUID.randomUUID().toString();
            PasswordResetToken resetToken = new PasswordResetToken();
            resetToken.setUserUuid(user.getUuid());
            resetToken.setToken(token);
            resetToken.setExpiryTimestamp(LocalDateTime.now().plusMinutes(TOKEN_VALIDITY_MINUTES));
            passwordResetTokenRepository.save(resetToken);

            String resetLink = frontendUrl + "/reset-password?token=" + token;
            String emailBody = "<p>Hello " + user.getUsername() + ",</p>"
                    + "<p>We received a request to reset your " + productLabel + " password. Click the link below to choose a new one — "
                    + "this link expires in " + TOKEN_VALIDITY_MINUTES + " minutes.</p>"
                    + "<p><a href=\"" + resetLink + "\">Reset your password</a></p>"
                    + "<p>If you didn't request this, you can safely ignore this email.</p>";
            emailService.sendEmail(user.getEmail(), null, "Reset your " + productLabel + " password", emailBody);
        });

        return ResponseEntity.ok(Map.of("success", true, "message", GENERIC_MESSAGE));
    }

    /**
     * Lets the reset-password screen show whose account is about to change, before submit.
     * reason distinguishes an expired/used link ("expired") from a garbage token ("invalid")
     * so the UI can word the failure accurately.
     */
    @GetMapping("/reset-password/validate")
    public ResponseEntity<Map<String, Object>> validateResetToken(@RequestParam("token") String token) {
        Optional<PasswordResetToken> tokenOpt = passwordResetTokenRepository.findByToken(token);
        if (tokenOpt.isEmpty()) {
            return ResponseEntity.ok(Map.of("valid", false, "reason", "invalid"));
        }
        if (tokenOpt.get().isUsed() || tokenOpt.get().getExpiryTimestamp().isBefore(LocalDateTime.now())) {
            return ResponseEntity.ok(Map.of("valid", false, "reason", "expired"));
        }

        Optional<UserMaster> userOpt = userMasterRepository.findById(tokenOpt.get().getUserUuid());
        if (userOpt.isEmpty()) {
            return ResponseEntity.ok(Map.of("valid", false, "reason", "invalid"));
        }

        UserMaster user = userOpt.get();
        Map<String, Object> res = new java.util.HashMap<>();
        res.put("valid", true);
        res.put("username", user.getUsername());
        res.put("tenantName", user.getTenantName() != null ? user.getTenantName() : "");
        res.put("maskedEmail", maskEmail(user.getEmail()));
        return ResponseEntity.ok(res);
    }

    private String maskEmail(String email) {
        if (email == null || !email.contains("@")) return "your account";
        String[] parts = email.split("@", 2);
        String local = parts[0];
        String visible = local.length() <= 2 ? local.substring(0, 1) : local.substring(0, 2);
        return visible + "***@" + parts[1];
    }

    @PostMapping("/reset-password")
    public ResponseEntity<Map<String, Object>> resetPassword(@RequestBody Map<String, String> body) {
        String token = body.get("token");
        String newPassword = body.get("newPassword");

        if (token == null || token.isBlank() || newPassword == null || newPassword.isBlank()) {
            return ResponseEntity.badRequest().body(Map.of("success", false, "message", "Token and new password are required."));
        }

        Optional<PasswordResetToken> tokenOpt = passwordResetTokenRepository.findByToken(token);
        if (tokenOpt.isEmpty() || tokenOpt.get().isUsed() || tokenOpt.get().getExpiryTimestamp().isBefore(LocalDateTime.now())) {
            return ResponseEntity.badRequest().body(Map.of("success", false, "message", "This reset link is invalid or has expired. Please request a new one."));
        }

        PasswordResetToken resetToken = tokenOpt.get();
        Optional<UserMaster> userOpt = userMasterRepository.findById(resetToken.getUserUuid());
        if (userOpt.isEmpty()) {
            return ResponseEntity.badRequest().body(Map.of("success", false, "message", "This reset link is invalid or has expired. Please request a new one."));
        }

        try {
            UserMaster user = userOpt.get();
            user.setPassword(passwordService.hashPassword(newPassword));
            userMasterRepository.save(user);

            resetToken.setUsed(true);
            passwordResetTokenRepository.save(resetToken);

            return ResponseEntity.ok(Map.of("success", true, "message", "Your password has been reset successfully. You can now log in."));
        } catch (Exception e) {
            return ResponseEntity.status(500).body(Map.of("success", false, "message", "Something went wrong. Please try again."));
        }
    }
}
