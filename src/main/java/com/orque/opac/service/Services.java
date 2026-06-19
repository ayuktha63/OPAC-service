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
import java.time.LocalDateTime;
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

        public AuditService(AuditLogRepository auditLogRepository, AuditHistoryRepository auditHistoryRepository) {
            this.auditLogRepository = auditLogRepository;
            this.auditHistoryRepository = auditHistoryRepository;
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
}
