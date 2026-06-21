package com.orque.opac.controller;

import com.orque.opac.entity.UserMaster;
import com.orque.opac.entity.TenantMaster;
import com.orque.opac.repository.UserMasterRepository;
import com.orque.opac.repository.TenantMasterRepository;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;
import java.util.*;

@RestController
@CrossOrigin(origins = "*")
@RequestMapping("/api/auth")
public class LoginController {

    private final UserMasterRepository userMasterRepository;
    private final TenantMasterRepository tenantMasterRepository;

    public LoginController(UserMasterRepository userMasterRepository, TenantMasterRepository tenantMasterRepository) {
        this.userMasterRepository = userMasterRepository;
        this.tenantMasterRepository = tenantMasterRepository;
    }

    @PostMapping("/login")
    public ResponseEntity<?> login(@RequestBody Map<String, String> credentials) {
        try {
            String tenantName = credentials.get("tenantName");
            String username = credentials.get("username");
            String password = credentials.get("password");

            if (tenantName == null || username == null || password == null) {
                return ResponseEntity.status(400).body(Map.of(
                    "success", false,
                    "message", "Tenant name, username, and password are required"
                ));
            }

            // Find tenant by name
            Optional<TenantMaster> tenantOpt = tenantMasterRepository.findByTenantName(tenantName);
            if (tenantOpt.isEmpty()) {
                return ResponseEntity.status(401).body(Map.of(
                    "success", false,
                    "message", "Invalid tenant or credentials"
                ));
            }

            TenantMaster tenant = tenantOpt.get();

            // Find user by username and tenant
            Optional<UserMaster> userOpt = userMasterRepository.findByUsernameAndTenantUuid(username, tenant.getUuid());
            if (userOpt.isEmpty()) {
                return ResponseEntity.status(401).body(Map.of(
                    "success", false,
                    "message", "Invalid tenant or credentials"
                ));
            }

            UserMaster user = userOpt.get();

            // Check if user is active
            if (!"Active".equalsIgnoreCase(user.getStatus())) {
                return ResponseEntity.status(401).body(Map.of(
                    "success", false,
                    "message", "User account is not active"
                ));
            }

            // Check if tenant is active
            if (!"Active".equalsIgnoreCase(tenant.getStatus())) {
                return ResponseEntity.status(401).body(Map.of(
                    "success", false,
                    "message", "Tenant is not active"
                ));
            }

            // Simple password check (in production, use bcrypt or similar)
            if (!password.equals(user.getPassword())) {
                return ResponseEntity.status(401).body(Map.of(
                    "success", false,
                    "message", "Invalid tenant or credentials"
                ));
            }

            // Login successful - return user details with tenant context
            return ResponseEntity.ok(Map.of(
                "success", true,
                "message", "Login successful",
                "data", Map.of(
                    "userId", user.getUuid(),
                    "username", user.getUsername(),
                    "email", user.getEmail(),
                    "tenantUuid", tenant.getUuid(),
                    "tenantName", tenant.getTenantName(),
                    "tenantId", tenant.getUuid(),
                    "role", "SYSTEM_ADMIN"
                )
            ));

        } catch (Exception e) {
            return ResponseEntity.status(500).body(Map.of(
                "success", false,
                "message", "Login failed: " + e.getMessage()
            ));
        }
    }

    @GetMapping("/validate")
    public ResponseEntity<?> validateToken(@RequestHeader(value = "x-user-id", required = false) String userId,
                                          @RequestHeader(value = "x-tenant-id", required = false) String tenantId) {
        try {
            if (userId == null || tenantId == null) {
                return ResponseEntity.status(401).body(Map.of(
                    "success", false,
                    "message", "Invalid session"
                ));
            }

            return ResponseEntity.ok(Map.of(
                "success", true,
                "message", "Session valid"
            ));

        } catch (Exception e) {
            return ResponseEntity.status(500).body(Map.of(
                "success", false,
                "message", "Validation failed: " + e.getMessage()
            ));
        }
    }
}
