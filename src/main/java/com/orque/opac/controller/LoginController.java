package com.orque.opac.controller;

import com.orque.opac.entity.TenantMaster;
import com.orque.opac.entity.UserMaster;
import com.orque.opac.repository.TenantMasterRepository;
import com.orque.opac.repository.UserMasterRepository;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.Map;
import java.util.Optional;

@RestController
@RequestMapping("/api")
@CrossOrigin(origins = "http://localhost:4200")
public class LoginController {

    @Autowired
    private TenantMasterRepository tenantMasterRepository;

    @Autowired
    private UserMasterRepository userMasterRepository;

    @PostMapping("/auth/login")
    public ResponseEntity<?> login(@RequestBody Map<String, String> credentials) {
        String tenantName = credentials.get("tenantName");
        String username = credentials.get("username");
        String password = credentials.get("password");

        if (tenantName == null || tenantName.isBlank() ||
            username == null || username.isBlank() ||
            password == null || password.isBlank()) {
            return ResponseEntity.badRequest().body(Map.of(
                "success", false,
                "error", "Tenant name, username, and password are required"
            ));
        }

        Optional<TenantMaster> tenantOpt = tenantMasterRepository.findByTenantName(tenantName);
        if (tenantOpt.isEmpty()) {
            return ResponseEntity.status(401).body(Map.of(
                "success", false,
                "error", "Invalid tenant name"
            ));
        }

        TenantMaster tenant = tenantOpt.get();
        if (!tenant.getStatus().equals("Active")) {
            return ResponseEntity.status(401).body(Map.of(
                "success", false,
                "error", "Tenant is not active"
            ));
        }

        Optional<UserMaster> userOpt = userMasterRepository.findByUsernameAndTenantUuid(username, tenant.getUuid());
        if (userOpt.isEmpty()) {
            return ResponseEntity.status(401).body(Map.of(
                "success", false,
                "error", "Invalid username or password"
            ));
        }

        UserMaster user = userOpt.get();
        if (!user.getStatus().equals("Active")) {
            return ResponseEntity.status(401).body(Map.of(
                "success", false,
                "error", "User is not active"
            ));
        }

        if (!password.equals(user.getPassword())) {
            return ResponseEntity.status(401).body(Map.of(
                "success", false,
                "error", "Invalid username or password"
            ));
        }

        return ResponseEntity.ok(Map.of(
            "success", true,
            "data", Map.of(
                "userId", user.getUuid().toString(),
                "username", user.getUsername(),
                "tenantUuid", tenant.getUuid().toString(),
                "tenantName", tenant.getTenantName(),
                "role", "SYSTEM_ADMIN"
            )
        ));
    }
}
