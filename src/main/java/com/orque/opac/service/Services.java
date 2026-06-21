package com.orque.opac.service;

import com.orque.opac.entity.*;
import com.orque.opac.repository.*;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import javax.crypto.Cipher;
import javax.crypto.Mac;
import javax.crypto.spec.IvParameterSpec;
import javax.crypto.spec.SecretKeySpec;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.SecureRandom;
import java.time.LocalDateTime;
import java.util.Base64;
import java.util.UUID;

@Service
public class Services {

    // =========================================================================
    // 1. SEQUENCE SERVICE
    // =========================================================================
    @Service
    public static class SequenceService {
        private final RequestSequenceMasterRepository sequenceRepository;

        public SequenceService(RequestSequenceMasterRepository sequenceRepository) {
            this.sequenceRepository = sequenceRepository;
        }

        @Transactional
        public synchronized String generateNextId(String prefix) {
            RequestSequenceMaster seq = sequenceRepository.findByPrefix(prefix)
                    .orElseGet(() -> {
                        RequestSequenceMaster newSeq = new RequestSequenceMaster();
                        newSeq.setPrefix(prefix);
                        newSeq.setCurrentValue(0);
                        return sequenceRepository.save(newSeq);
                    });
            seq.setCurrentValue(seq.getCurrentValue() + 1);
            sequenceRepository.save(seq);
            return String.format("%s-%06d", prefix, seq.getCurrentValue());
        }
    }

    // =========================================================================
    // 2. AUDIT SERVICE
    // =========================================================================
    @Service
    public static class AuditService {
        private final AuditLogRepository auditLogRepository;
        private final AuditHistoryRepository auditHistoryRepository;
        private final UserMasterRepository userMasterRepository;

        public AuditService(AuditLogRepository auditLogRepository, AuditHistoryRepository auditHistoryRepository,
                            UserMasterRepository userMasterRepository) {
            this.auditLogRepository = auditLogRepository;
            this.auditHistoryRepository = auditHistoryRepository;
            this.userMasterRepository = userMasterRepository;
        }

        /** Resolve the tenant of the acting user so audit rows can be isolated per tenant. */
        private UUID resolveActorTenant(String username) {
            if (username == null) return null;
            return userMasterRepository.findByUsername(username)
                    .map(UserMaster::getTenantUuid)
                    .orElse(null);
        }

        @Transactional
        public void logAuditEvent(String action, String module, String username, String entityName, UUID entityUuid, String ipAddress) {
            AuditLog log = new AuditLog();
            log.setAction(action);
            log.setModule(module);
            log.setUsername(username);
            log.setEntityName(entityName);
            log.setEntityUuid(entityUuid);
            log.setIpAddress(ipAddress);
            log.setTenantUuid(resolveActorTenant(username));
            auditLogRepository.save(log);
        }

        @Transactional
        public void logAuditEventWithChanges(String action, String module, String username, String entityName, UUID entityUuid, String ipAddress, String field, String oldVal, String newVal) {
            AuditLog log = new AuditLog();
            log.setAction(action);
            log.setModule(module);
            log.setUsername(username);
            log.setEntityName(entityName);
            log.setEntityUuid(entityUuid);
            log.setIpAddress(ipAddress);
            log.setTenantUuid(resolveActorTenant(username));
            AuditLog savedLog = auditLogRepository.save(log);

            AuditHistory history = new AuditHistory();
            history.setAuditLogUuid(savedLog.getUuid());
            history.setFieldName(field);
            history.setOldValue(oldVal);
            history.setNewValue(newVal);
            auditHistoryRepository.save(history);
        }
    }

    // =========================================================================
    // 3. LICENSE CRYPTOGRAPHIC ENGINE
    // =========================================================================
    @Service
    public static class LicenseCryptService {
        @Value("${licensing.secret}")
        private String secretKey;

        @Value("${licensing.ivHex}")
        private String ivHex;

        // Symmetric AES-256 Encryption
        public String encrypt(String payload) throws Exception {
            byte[] keyBytes = secretKey.getBytes(StandardCharsets.UTF_8);
            byte[] ivBytes = hexStringToByteArray(ivHex);

            SecretKeySpec keySpec = new SecretKeySpec(keyBytes, "AES");
            IvParameterSpec ivSpec = new IvParameterSpec(ivBytes);

            Cipher cipher = Cipher.getInstance("AES/CBC/PKCS5Padding");
            cipher.init(Cipher.ENCRYPT_MODE, keySpec, ivSpec);

            byte[] encryptedBytes = cipher.doFinal(payload.getBytes(StandardCharsets.UTF_8));
            return byteArrayToHexString(encryptedBytes);
        }

        // Symmetric AES-256 Decryption
        public String decrypt(String encryptedHex) throws Exception {
            byte[] keyBytes = secretKey.getBytes(StandardCharsets.UTF_8);
            byte[] ivBytes = hexStringToByteArray(ivHex);
            byte[] encryptedBytes = hexStringToByteArray(encryptedHex);

            SecretKeySpec keySpec = new SecretKeySpec(keyBytes, "AES");
            IvParameterSpec ivSpec = new IvParameterSpec(ivBytes);

            Cipher cipher = Cipher.getInstance("AES/CBC/PKCS5Padding");
            cipher.init(Cipher.DECRYPT_MODE, keySpec, ivSpec);

            byte[] decryptedBytes = cipher.doFinal(encryptedBytes);
            return new String(decryptedBytes, StandardCharsets.UTF_8);
        }

        // HmacSHA256 Digital Signature
        public String generateHmac(String data) throws Exception {
            byte[] keyBytes = secretKey.getBytes(StandardCharsets.UTF_8);
            SecretKeySpec signingKey = new SecretKeySpec(keyBytes, "HmacSHA256");
            Mac mac = Mac.getInstance("HmacSHA256");
            mac.init(signingKey);
            byte[] rawHmac = mac.doFinal(data.getBytes(StandardCharsets.UTF_8));
            return byteArrayToHexString(rawHmac);
        }

        // Conversion Helpers
        private byte[] hexStringToByteArray(String s) {
            int len = s.length();
            byte[] data = new byte[len / 2];
            for (int i = 0; i < len; i += 2) {
                data[i / 2] = (byte) ((Character.digit(s.charAt(i), 16) << 4)
                        + Character.digit(s.charAt(i+1), 16));
            }
            return data;
        }

        private String byteArrayToHexString(byte[] bytes) {
            StringBuilder sb = new StringBuilder();
            for (byte b : bytes) {
                sb.append(String.format("%02x", b));
            }
            return sb.toString();
        }
    }

    // =========================================================================
    // 4. PASSWORD SERVICE
    // =========================================================================
    @Service
    public static class PasswordService {
        public String hashPassword(String password) throws Exception {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            byte[] salt = new byte[16];
            new SecureRandom().nextBytes(salt);

            byte[] passwordBytes = password.getBytes(StandardCharsets.UTF_8);
            byte[] input = new byte[salt.length + passwordBytes.length];
            System.arraycopy(salt, 0, input, 0, salt.length);
            System.arraycopy(passwordBytes, 0, input, salt.length, passwordBytes.length);

            byte[] hash = digest.digest(input);
            byte[] combined = new byte[salt.length + hash.length];
            System.arraycopy(salt, 0, combined, 0, salt.length);
            System.arraycopy(hash, 0, combined, salt.length, hash.length);

            return Base64.getEncoder().encodeToString(combined);
        }

        public boolean verifyPassword(String password, String hashedPassword) throws Exception {
            byte[] combined = Base64.getDecoder().decode(hashedPassword);
            byte[] salt = new byte[16];
            System.arraycopy(combined, 0, salt, 0, 16);

            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            byte[] passwordBytes = password.getBytes(StandardCharsets.UTF_8);
            byte[] input = new byte[salt.length + passwordBytes.length];
            System.arraycopy(salt, 0, input, 0, salt.length);
            System.arraycopy(passwordBytes, 0, input, salt.length, passwordBytes.length);

            byte[] computedHash = digest.digest(input);
            byte[] storedHash = new byte[combined.length - 16];
            System.arraycopy(combined, 16, storedHash, 0, storedHash.length);

            return MessageDigest.isEqual(computedHash, storedHash);
        }

        public String generateRandomPassword() {
            SecureRandom random = new SecureRandom();
            String chars = "ABCDEFGHIJKLMNOPQRSTUVWXYZabcdefghijklmnopqrstuvwxyz0123456789!@#$%";
            StringBuilder sb = new StringBuilder();
            for (int i = 0; i < 12; i++) {
                sb.append(chars.charAt(random.nextInt(chars.length())));
            }
            return sb.toString();
        }
    }
}
