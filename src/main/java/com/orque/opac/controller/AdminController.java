package com.orque.opac.controller;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.orque.opac.entity.*;
import com.orque.opac.repository.*;
import com.orque.opac.service.*;
import org.springframework.core.io.ClassPathResource;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.io.File;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Paths;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.*;
import java.util.stream.Collectors;

@RestController
@CrossOrigin(origins = "*")
@RequestMapping("/api")
public class AdminController {

    private static final String STATUS_DRAFT    = "Draft";
    private static final String STATUS_IN_PROG  = "In Progress";
    private static final String STATUS_ACTIVE   = "Active";
    private static final String STATUS_INACTIVE = "Inactive";
    private static final String STATUS_RETURNED = "Returned";
    private static final String STATUS_REJECTED = "Rejected";
    private static final String STATUS_PENDING  = "Pending";
    private static final String STATUS_APPROVED = "Approved";
    private static final String FIELD_COMPANY   = "companyName";

    private final TenantRequestRepository tenantRequestRepository;
    private final TenantMasterRepository tenantMasterRepository;
    private final TenantConfigurationRepository tenantConfigurationRepository;
    private final LicenseRequestRepository licenseRequestRepository;
    private final LicenseProductRepository licenseProductRepository;
    private final LicenseFeatureRepository licenseFeatureRepository;
    private final LicenseMasterRepository licenseMasterRepository;
    private final SessionMasterRepository sessionMasterRepository;
    private final NotificationMasterRepository notificationMasterRepository;
    private final EmailQueueRepository emailQueueRepository;
    private final AuditLogRepository auditLogRepository;
    private final AuditHistoryRepository auditHistoryRepository;
    private final ApprovalRequestRepository approvalRequestRepository;
    private final ApprovalHistoryRepository approvalHistoryRepository;
    private final RoleMasterRepository roleMasterRepository;
    private final RolePermissionRepository rolePermissionRepository;
    private final UserMasterRepository userMasterRepository;

    private final UserService userService;
    private final RoleService roleService;

    private final Services.SequenceService sequenceService;
    private final Services.AuditService auditService;
    private final Services.LicenseCryptService licenseCryptService;
    private final Services.PasswordService passwordService;
    private final EmailService emailService;
    private final ObjectMapper objectMapper;

    @org.springframework.beans.factory.annotation.Value("${approval-workflow.service-url}")
    private String workflowServiceUrl;

    public AdminController(TenantRequestRepository tenantRequestRepository,
                           TenantMasterRepository tenantMasterRepository,
                           TenantConfigurationRepository tenantConfigurationRepository,
                           LicenseRequestRepository licenseRequestRepository,
                           LicenseProductRepository licenseProductRepository,
                           LicenseFeatureRepository licenseFeatureRepository,
                           LicenseMasterRepository licenseMasterRepository,
                           SessionMasterRepository sessionMasterRepository,
                           NotificationMasterRepository notificationMasterRepository,
                           EmailQueueRepository emailQueueRepository,
                           AuditLogRepository auditLogRepository,
                           AuditHistoryRepository auditHistoryRepository,
                           ApprovalRequestRepository approvalRequestRepository,
                           ApprovalHistoryRepository approvalHistoryRepository,
                           RoleMasterRepository roleMasterRepository,
                           RolePermissionRepository rolePermissionRepository,
                           UserMasterRepository userMasterRepository,
                           UserService userService,
                           RoleService roleService,
                           Services.SequenceService sequenceService,
                           Services.AuditService auditService,
                           Services.LicenseCryptService licenseCryptService,
                           Services.PasswordService passwordService,
                           EmailService emailService,
                           ObjectMapper objectMapper) {
        this.tenantRequestRepository = tenantRequestRepository;
        this.tenantMasterRepository = tenantMasterRepository;
        this.tenantConfigurationRepository = tenantConfigurationRepository;
        this.licenseRequestRepository = licenseRequestRepository;
        this.licenseProductRepository = licenseProductRepository;
        this.licenseFeatureRepository = licenseFeatureRepository;
        this.licenseMasterRepository = licenseMasterRepository;
        this.sessionMasterRepository = sessionMasterRepository;
        this.notificationMasterRepository = notificationMasterRepository;
        this.emailQueueRepository = emailQueueRepository;
        this.auditLogRepository = auditLogRepository;
        this.auditHistoryRepository = auditHistoryRepository;
        this.approvalRequestRepository = approvalRequestRepository;
        this.approvalHistoryRepository = approvalHistoryRepository;
        this.roleMasterRepository = roleMasterRepository;
        this.rolePermissionRepository = rolePermissionRepository;
        this.userMasterRepository = userMasterRepository;
        this.userService = userService;
        this.roleService = roleService;
        this.sequenceService = sequenceService;
        this.auditService = auditService;
        this.licenseCryptService = licenseCryptService;
        this.passwordService = passwordService;
        this.emailService = emailService;
        this.objectMapper = objectMapper;
    }

    // =========================================================================
    // DYNAMIC FORMS SCHEMAS
    // =========================================================================
    @GetMapping("/schemas/{name}")
    public ResponseEntity<String> getSchema(@PathVariable String name) {
        try {
            String userDir = System.getProperty("user.dir");
            java.nio.file.Path path = Paths.get(userDir, "schemas", name + ".json");
            if (!Files.exists(path)) {
                path = Paths.get(userDir, "orque-platform-admin-center-service", "schemas", name + ".json");
            }
            String content = new String(Files.readAllBytes(path));
            return ResponseEntity.ok(content);
        } catch (IOException e) {
            return ResponseEntity.status(HttpStatus.NOT_FOUND).body("{\"error\": \"Schema not found\"}");
        }
    }

    // =========================================================================
    // AUTHENTICATION CONTROLLER
    // =========================================================================
    @PostMapping("/auth/login")
    public ResponseEntity<?> login(@RequestBody Map<String, Object> body) {
        try {
            String username = (String) body.get("username");
            String password = (String) body.get("password");
            String loginMode = (String) body.get("loginMode");
            String tenantName = (String) body.get("tenantName");

            if (username == null || username.isEmpty() || password == null || password.isEmpty()) {
                return ResponseEntity.status(401).body(Map.of(
                    "success", false,
                    "message", "Username and password are required"
                ));
            }

            // Find user by username
            Optional<UserMaster> userOpt = userMasterRepository.findByUsername(username);
            if (userOpt.isEmpty()) {
                return ResponseEntity.status(401).body(Map.of(
                    "success", false,
                    "message", "Invalid username or password"
                ));
            }

            UserMaster user = userOpt.get();

            // Verify password
            try {
                if (!passwordService.verifyPassword(password, user.getPassword())) {
                    return ResponseEntity.status(401).body(Map.of(
                        "success", false,
                        "message", "Invalid username or password"
                    ));
                }
            } catch (Exception e) {
                System.err.println("Password verification failed: " + e.getMessage());
                return ResponseEntity.status(401).body(Map.of(
                    "success", false,
                    "message", "Authentication failed"
                ));
            }

            // Check login mode
            if ("business".equals(loginMode)) {
                // Business user mode - verify tenant
                if (tenantName == null || tenantName.isEmpty()) {
                    return ResponseEntity.status(400).body(Map.of(
                        "success", false,
                        "message", "Tenant name is required for business user login"
                    ));
                }

                // Verify tenant exists and user belongs to it
                Optional<TenantMaster> tenantOpt = tenantMasterRepository.findByTenantName(tenantName);
                if (tenantOpt.isEmpty()) {
                    tenantOpt = tenantMasterRepository.findByCompanyName(tenantName);
                }

                if (tenantOpt.isEmpty()) {
                    return ResponseEntity.status(401).body(Map.of(
                        "success", false,
                        "message", "Tenant not found"
                    ));
                }

                TenantMaster tenant = tenantOpt.get();

                // Verify user belongs to this tenant
                if (!user.getTenantUuid().equals(tenant.getUuid())) {
                    return ResponseEntity.status(401).body(Map.of(
                        "success", false,
                        "message", "User does not belong to this tenant"
                    ));
                }

                // Business user login successful
                Map<String, Object> response = new HashMap<>();
                response.put("success", true);
                response.put("message", "Login successful");
                Map<String, Object> data = new HashMap<>();
                data.put("uuid", user.getUuid().toString());
                data.put("username", user.getUsername());
                data.put("email", user.getEmail());
                data.put("role", "REQUESTER");
                data.put("tenantUuid", tenant.getUuid().toString());
                data.put("tenantName", tenant.getTenantName());
                response.put("data", data);

                auditService.logAuditEvent("LOGIN", "Authentication", username, "user", user.getUuid(), "localhost");
                return ResponseEntity.ok(response);
            } else {
                // System admin mode
                if (!user.getRole().equals("SYSTEM_ADMIN")) {
                    return ResponseEntity.status(403).body(Map.of(
                        "success", false,
                        "message", "User is not a system admin"
                    ));
                }

                // A tenant name is now required so the admin is scoped to their tenant.
                // The Orque platform tenant grants full cross-tenant access; every other
                // tenant's admin (e.g. AKRO) is isolated to their own tenant's data.
                if (tenantName == null || tenantName.isEmpty()) {
                    return ResponseEntity.status(400).body(Map.of(
                        "success", false,
                        "message", "Tenant name is required for system admin login"
                    ));
                }

                Optional<TenantMaster> tenantOpt = tenantMasterRepository.findByTenantName(tenantName);
                if (tenantOpt.isEmpty()) {
                    tenantOpt = tenantMasterRepository.findByCompanyName(tenantName);
                }
                if (tenantOpt.isEmpty()) {
                    return ResponseEntity.status(401).body(Map.of(
                        "success", false,
                        "message", "Tenant not found"
                    ));
                }

                TenantMaster tenant = tenantOpt.get();

                // The admin must belong to this tenant (legacy admins with no tenant are
                // treated as platform admins and may sign in to any tenant).
                if (user.getTenantUuid() != null && !user.getTenantUuid().equals(tenant.getUuid())) {
                    return ResponseEntity.status(401).body(Map.of(
                        "success", false,
                        "message", "Admin does not belong to this tenant"
                    ));
                }

                // System admin login successful
                Map<String, Object> response = new HashMap<>();
                response.put("success", true);
                response.put("message", "Login successful");
                Map<String, Object> data = new HashMap<>();
                data.put("uuid", user.getUuid().toString());
                data.put("username", user.getUsername());
                data.put("email", user.getEmail());
                data.put("role", "SYSTEM_ADMIN");
                data.put("tenantUuid", tenant.getUuid().toString());
                data.put("tenantName", tenant.getTenantName());
                data.put("isPlatformOwner", isPlatformOwner(tenant));
                response.put("data", data);

                auditService.logAuditEvent("LOGIN", "Authentication", username, "user", user.getUuid(), "localhost");
                return ResponseEntity.ok(response);
            }
        } catch (Exception e) {
            System.err.println("Login error: " + e.getMessage());
            e.printStackTrace();
            return ResponseEntity.status(500).body(Map.of(
                "success", false,
                "message", "An error occurred during login"
            ));
        }
    }

    private void callWorkflowService(String triggerEvent, String referenceId) {
        try {
            java.net.http.HttpClient client = java.net.http.HttpClient.newHttpClient();
            String url = workflowServiceUrl + "/api/workflow-process/start-workflow-process" 
                       + "?triggerEvent=" + java.net.URLEncoder.encode(triggerEvent, java.nio.charset.StandardCharsets.UTF_8)
                       + "&referenceId=" + java.net.URLEncoder.encode(referenceId, java.nio.charset.StandardCharsets.UTF_8);
            java.net.http.HttpRequest req = java.net.http.HttpRequest.newBuilder()
                    .uri(java.net.URI.create(url))
                    .GET()
                    .build();
            java.net.http.HttpResponse<String> resp = client.send(req, java.net.http.HttpResponse.BodyHandlers.ofString());
            System.out.println("Workflow Service Response: " + resp.statusCode() + " - " + resp.body());
        } catch (Exception e) {
            System.err.println("Failed to notify workflow service: " + e.getMessage());
        }
    }

    // =========================================================================
    // MULTI-TENANT ISOLATION HELPERS
    // =========================================================================

    /** The Orque platform tenant has full cross-tenant visibility. */
    private boolean isPlatformOwner(TenantMaster tenant) {
        if (tenant == null) return false;
        return "orque".equalsIgnoreCase(tenant.getTenantName())
            || "orque".equalsIgnoreCase(tenant.getCompanyName());
    }

    /**
     * Resolves the tenant a request is scoped to from the {@code x-tenant-uuid} header.
     * Returns {@code null} when the caller is the Orque platform owner (or no/invalid
     * header is supplied), meaning "no filter — see everything".
     */
    private TenantMaster resolveScope(String headerTenantUuid) {
        if (headerTenantUuid == null || headerTenantUuid.isBlank()) return null;
        UUID tid;
        try {
            tid = UUID.fromString(headerTenantUuid);
        } catch (IllegalArgumentException e) {
            return null;
        }
        TenantMaster tenant = tenantMasterRepository.findById(tid).orElse(null);
        if (tenant == null || isPlatformOwner(tenant)) return null;
        return tenant;
    }

    // =========================================================================
    // TENANT MODULE CONTROLLER
    // =========================================================================
    @GetMapping("/tenants")
    public List<TenantRequest> getTenants(@RequestParam(required = false) String status,
            @RequestHeader(value = "x-tenant-uuid", required = false) String tenantHeader) {
        // Tenant onboarding is a platform-owner (Orque) function. A tenant-scoped
        // caller (e.g. AKRO) must not see any tenant requests — not even its own.
        TenantMaster scope = resolveScope(tenantHeader);
        if (scope != null) {
            return List.of();
        }

        return (status != null && !status.isEmpty())
            ? tenantRequestRepository.findAllByStatusOrderByCreatedTimestampDesc(status)
            : tenantRequestRepository.findAllByOrderByCreatedTimestampDesc();
    }

    @PostMapping("/tenants")
    public ResponseEntity<?> saveTenant(@RequestBody Map<String, Object> body, @RequestHeader(value = "x-user", defaultValue = "system-admin") String user) {
        try {
            String uuidStr = (String) body.get("uuid");
            String companyName = (String) body.get(FIELD_COMPANY);
            String tenantName = (String) body.get("tenantName");
            String adminUsername = (String) body.get("adminUsername");
            String adminEmail = (String) body.get("adminEmail");
            String contactNumber = (String) body.get("contactNumber");
            String country = (String) body.get("country");
            String timezone = (String) body.get("timezone");
            String adminFirstName = (String) body.get("adminFirstName");
            String adminLastName = (String) body.get("adminLastName");
            String baseCurrency = (String) body.get("baseCurrency");
            String companyRegistrationNumber = (String) body.get("companyRegistrationNumber");

            // Duplicate checks
            if (uuidStr == null && tenantMasterRepository.findByTenantName(tenantName).isPresent()) {
                return ResponseEntity.badRequest().body(Map.of("error", "Tenant Alias already exists and is active."));
            }

            TenantRequest request;
            if (uuidStr != null && !uuidStr.isEmpty()) {
                request = tenantRequestRepository.findById(UUID.fromString(uuidStr)).orElseThrow();
                if (!STATUS_DRAFT.equals(request.getStatus()) && !STATUS_RETURNED.equals(request.getStatus())) {
                    return ResponseEntity.badRequest().body(Map.of("error", "Only Draft or Returned requests are editable."));
                }
                request.setCompanyName(companyName);
                request.setTenantName(tenantName);
                request.setAdminUsername(adminUsername);
                request.setAdminEmail(adminEmail);
                request.setContactNumber(contactNumber);
                request.setCountry(country);
                request.setTimezone(timezone);
                request.setAdminFirstName(adminFirstName);
                request.setAdminLastName(adminLastName);
                request.setBaseCurrency(baseCurrency);
                request.setCompanyRegistrationNumber(companyRegistrationNumber);
                request.setUpdatedBy(user);
                request.setUpdatedTimestamp(LocalDateTime.now());
                tenantRequestRepository.save(request);
                auditService.logAuditEvent("UPDATE", "Tenant", user, "tenant_request", request.getUuid(), "localhost");
            } else {
                request = new TenantRequest();
                String nextId = sequenceService.generateNextId("TEN");
                request.setRequestId(nextId);
                request.setCompanyName(companyName);
                request.setTenantName(tenantName);
                request.setAdminUsername(adminUsername);
                request.setAdminEmail(adminEmail);
                request.setContactNumber(contactNumber);
                request.setCountry(country);
                request.setTimezone(timezone);
                request.setAdminFirstName(adminFirstName);
                request.setAdminLastName(adminLastName);
                request.setBaseCurrency(baseCurrency);
                request.setCompanyRegistrationNumber(companyRegistrationNumber);
                request.setStatus(STATUS_DRAFT);
                request.setCreatedBy(user);
                request.setUpdatedBy(user);
                TenantRequest saved = tenantRequestRepository.save(request);
                auditService.logAuditEvent("INSERT", "Tenant", user, "tenant_request", saved.getUuid(), "localhost");
            }
            return ResponseEntity.ok(request);
        } catch (Exception e) {
            return ResponseEntity.status(500).body(Map.of("error", e.getMessage()));
        }
    }

    @PostMapping("/tenants/submit/{uuid}")
    public ResponseEntity<?> submitTenant(@PathVariable UUID uuid, @RequestHeader(value = "x-user", defaultValue = "system-admin") String user) {
        try {
            TenantRequest request = tenantRequestRepository.findById(uuid).orElseThrow();
            request.setStatus(STATUS_IN_PROG);
            tenantRequestRepository.save(request);

            ApprovalRequest approval = new ApprovalRequest();
            approval.setReferenceUuid(uuid);
            approval.setTriggerEvent("tenantRegistration");
            approval.setTenantId(request.getTenantName());
            approval.setStatus(STATUS_PENDING);
            ApprovalRequest savedApp = approvalRequestRepository.save(approval);

            ApprovalHistory history = new ApprovalHistory();
            history.setApprovalRequestUuid(savedApp.getUuid());
            history.setAction("Submit");
            history.setActorUsername(user);
            history.setNotes("Tenant request sent for approval.");
            approvalHistoryRepository.save(history);

            callWorkflowService("tenantRegistration", request.getRequestId());

            auditService.logAuditEvent("SUBMIT", "Tenant", user, "tenant_request", uuid, "localhost");
            return ResponseEntity.ok(Map.of("success", true));
        } catch (Exception e) {
            return ResponseEntity.status(500).body(Map.of("error", e.getMessage()));
        }
    }

    @PostMapping("/tenants/{action}/{uuid}")
    public ResponseEntity<?> handleTenantApproval(@PathVariable String action, @PathVariable UUID uuid, @RequestBody Map<String, String> body, @RequestHeader(value = "x-user", defaultValue = "system-admin") String user) {
        try {
            TenantRequest request = tenantRequestRepository.findById(uuid).orElseThrow();
            ApprovalRequest approval = approvalRequestRepository.findFirstByReferenceUuidOrderByCreatedTimestampDesc(uuid).orElseThrow();
            String notes = body.get("notes");

            if ("approve".equals(action)) {
                request.setStatus(STATUS_ACTIVE);
                tenantRequestRepository.save(request);

                approval.setStatus(STATUS_APPROVED);
                approval.setUpdatedTimestamp(LocalDateTime.now());
                approvalRequestRepository.save(approval);

                ApprovalHistory history = new ApprovalHistory();
                history.setApprovalRequestUuid(approval.getUuid());
                history.setAction("Approve");
                history.setActorUsername(user);
                history.setNotes(notes);
                approvalHistoryRepository.save(history);

                // Create or Update Master
                TenantMaster master = tenantMasterRepository
                    .findByTenantName(request.getTenantName())
                    .orElse(new TenantMaster());
                master.setTenantName(request.getTenantName());
                master.setCompanyName(request.getCompanyName());
                master.setStatus(STATUS_ACTIVE);
                master.setCreatedBy(user);
                master.setUpdatedBy(user);
                TenantMaster savedMaster = tenantMasterRepository.save(master);

                // Create Configuration
                TenantConfiguration config = new TenantConfiguration();
                config.setTenantUuid(savedMaster.getUuid());
                config.setBrandingLogoUrl("");
                config.setThemePrimaryColor("#4F46E5");
                config.setSettingsJson("{}");
                tenantConfigurationRepository.save(config);

                // Default roles (check if they already exist)
                RoleMaster savedReqRole = roleMasterRepository
                    .findByRoleNameAndTenantUuid("Requester", savedMaster.getUuid())
                    .orElseGet(() -> {
                        RoleMaster reqRole = new RoleMaster();
                        reqRole.setTenantUuid(savedMaster.getUuid());
                        reqRole.setRoleName("Requester");
                        reqRole.setIsSystemDefault(true);
                        return roleMasterRepository.save(reqRole);
                    });

                RoleMaster savedAppRole = roleMasterRepository
                    .findByRoleNameAndTenantUuid("Approver", savedMaster.getUuid())
                    .orElseGet(() -> {
                        RoleMaster appRole = new RoleMaster();
                        appRole.setTenantUuid(savedMaster.getUuid());
                        appRole.setRoleName("Approver");
                        appRole.setIsSystemDefault(true);
                        return roleMasterRepository.save(appRole);
                    });

                // Add permissions if they don't exist
                if (rolePermissionRepository.findByRoleUuidAndAccessPolicyKey(savedReqRole.getUuid(), "tenant:read").isEmpty()) {
                    RolePermission p1 = new RolePermission();
                    p1.setRoleUuid(savedReqRole.getUuid());
                    p1.setAccessPolicyKey("tenant:read");
                    rolePermissionRepository.save(p1);
                }

                if (rolePermissionRepository.findByRoleUuidAndAccessPolicyKey(savedAppRole.getUuid(), "tenant:approve").isEmpty()) {
                    RolePermission p2 = new RolePermission();
                    p2.setRoleUuid(savedAppRole.getUuid());
                    p2.setAccessPolicyKey("tenant:approve");
                    rolePermissionRepository.save(p2);
                }

                // Default admin user with a known temporary password (emailed to them).
                String tempPassword = "Welcome@123!";
                String hashedPassword = passwordService.hashPassword(tempPassword);
                if (userMasterRepository.findByUsernameAndTenantUuid(request.getAdminUsername(), savedMaster.getUuid()).isEmpty()) {
                    UserMaster admin = new UserMaster();
                    admin.setTenantUuid(savedMaster.getUuid());
                    admin.setUsername(request.getAdminUsername());
                    admin.setEmail(request.getAdminEmail());
                    admin.setPassword(hashedPassword);
                    admin.setFirstName(request.getAdminFirstName());
                    admin.setLastName(request.getAdminLastName());
                    admin.setRole("SYSTEM_ADMIN");
                    admin.setTenantName(request.getTenantName());
                    admin.setContactNumber(request.getContactNumber());
                    admin.setStatus(STATUS_ACTIVE);
                    userMasterRepository.save(admin);

                    // Log credentials for development
                    System.out.println("\n" +
                        "════════════════════════════════════════════════════════════════\n" +
                        "  ✅ TENANT ACTIVATED - NEW SYSTEM ADMIN CREDENTIALS\n" +
                        "════════════════════════════════════════════════════════════════\n" +
                        "  Tenant Name:     " + request.getTenantName() + "\n" +
                        "  Company Name:    " + request.getCompanyName() + "\n" +
                        "  Admin Name:      " + request.getAdminFirstName() + " " + request.getAdminLastName() + "\n" +
                        "  Admin Email:     " + request.getAdminEmail() + "\n" +
                        "  Admin Contact:   " + request.getContactNumber() + "\n" +
                        "  ────────────────────────────────────────────────────────────────\n" +
                        "  🔐 LOGIN CREDENTIALS\n" +
                        "  ────────────────────────────────────────────────────────────────\n" +
                        "  Username:        " + request.getAdminUsername() + "\n" +
                        "  Temporary Pwd:   " + tempPassword + "\n" +
                        "  Login Mode:      System Admin\n" +
                        "  URL:             http://localhost:8083\n" +
                        "════════════════════════════════════════════════════════════════\n");
                } else {
                    System.out.println("⚠️  Tenant " + request.getTenantName() + " activated but admin user already exists");
                }

                // Send welcome email via template with credentials
                emailService.sendFromTemplate("tenant_approved", request.getAdminEmail(), null,
                    Map.of(
                        "tenantName",   request.getCompanyName(),   // display name, e.g. "SM MART"
                        "tenantCode",   request.getTenantName(),    // login tenant code, e.g. "SMMART"
                        "username",     request.getAdminUsername(),
                        "tempPassword", tempPassword,
                        "opacUrl",      "http://localhost:8083"
                    ));

                // Initial License Draft Setup
                LicenseRequest licReq = new LicenseRequest();
                licReq.setRequestId(sequenceService.generateNextId("LIC"));
                licReq.setTenantUuid(savedMaster.getUuid());
                licReq.setCompanyName(request.getCompanyName());
                licReq.setEmail(request.getAdminEmail());
                licReq.setRequestedBy("System Onboarding");
                licReq.setStatus(STATUS_DRAFT);
                licReq.setRequestType("New");
                licenseRequestRepository.save(licReq);

                // Notification
                NotificationMaster notif = new NotificationMaster();
                notif.setTenantUuid(savedMaster.getUuid());
                notif.setType("TenantApproved");
                notif.setMessage("Tenant " + request.getCompanyName() + " approved and provisioned successfully.");
                notificationMasterRepository.save(notif);

                auditService.logAuditEvent("APPROVE", "Tenant", user, "tenant_request", uuid, "localhost");
            } else if ("reject".equals(action)) {
                request.setStatus(STATUS_INACTIVE);
                tenantRequestRepository.save(request);

                approval.setStatus(STATUS_REJECTED);
                approvalRequestRepository.save(approval);

                ApprovalHistory history = new ApprovalHistory();
                history.setApprovalRequestUuid(approval.getUuid());
                history.setAction("Reject");
                history.setActorUsername(user);
                history.setNotes(notes);
                approvalHistoryRepository.save(history);

                auditService.logAuditEvent("REJECT", "Tenant", user, "tenant_request", uuid, "localhost");
            } else if ("return".equals(action)) {
                // Return for Revision: send record back to Draft so requester can re-edit & re-submit
                request.setStatus(STATUS_DRAFT);
                tenantRequestRepository.save(request);

                approval.setStatus(STATUS_RETURNED);
                approvalRequestRepository.save(approval);

                ApprovalHistory history = new ApprovalHistory();
                history.setApprovalRequestUuid(approval.getUuid());
                history.setAction("Return");
                history.setActorUsername(user);
                history.setNotes(notes);
                approvalHistoryRepository.save(history);

                auditService.logAuditEvent("RETURN", "Tenant", user, "tenant_request", uuid, "localhost");
            }
            return ResponseEntity.ok(Map.of("success", true));
        } catch (Exception e) {
            return ResponseEntity.status(500).body(Map.of("error", e.getMessage()));
        }
    }

    @GetMapping("/tenants/history/{uuid}")
    public List<ApprovalHistory> getTenantHistory(@PathVariable UUID uuid) {
        Optional<ApprovalRequest> req = approvalRequestRepository.findFirstByReferenceUuidOrderByCreatedTimestampDesc(uuid);
        if (req.isPresent()) {
            return approvalHistoryRepository.findAllByApprovalRequestUuidOrderByCreatedTimestampAsc(req.get().getUuid());
        }
        return Collections.emptyList();
    }

    // =========================================================================
    // LICENSING MODULE CONTROLLER
    // =========================================================================
    @GetMapping("/licenses")
    public List<Map<String, Object>> getLicenses(@RequestParam(required = false) String status,
            @RequestHeader(value = "x-tenant-uuid", required = false) String tenantHeader) {
        List<LicenseRequest> requests;
        if (status != null && !status.isEmpty()) {
            requests = licenseRequestRepository.findAllByStatusOrderByCreatedTimestampDesc(status);
        } else {
            requests = licenseRequestRepository.findAllByOrderByCreatedTimestampDesc();
        }

        // Tenant isolation: non-platform tenants only see their own license requests.
        TenantMaster scope = resolveScope(tenantHeader);
        if (scope != null) {
            requests = requests.stream()
                .filter(r -> scope.getUuid().equals(r.getTenantUuid()))
                .collect(Collectors.toList());
        }

        List<Map<String, Object>> result = new ArrayList<>();
        for (LicenseRequest req : requests) {
            Map<String, Object> map = new HashMap<>();
            map.put("uuid", req.getUuid());
            map.put("requestId", req.getRequestId());
            map.put("tenantUuid", req.getTenantUuid());
            map.put(FIELD_COMPANY, req.getCompanyName());
            map.put("email", req.getEmail());
            map.put("requestedBy", req.getRequestedBy());
            map.put("requestType", req.getRequestType());
            map.put("createdTimestamp", req.getCreatedTimestamp());
            map.put("totalUsers", req.getTotalUsers());
            map.put("maxConcurrent", req.getMaxConcurrent());
            map.put("startDate", req.getStartDate());
            map.put("endDate", req.getEndDate());
            map.put("gracePeriod", req.getGracePeriod());

            List<LicenseProduct> products = licenseProductRepository.findAllByLicenseRequestUuid(req.getUuid());

            // Expiry evaluation
            String resolvedStatus = req.getStatus();
            if (STATUS_ACTIVE.equals(resolvedStatus)) {
                if (req.getEndDate() != null && req.getEndDate().isBefore(LocalDate.now())) {
                    resolvedStatus = "Expired";
                }
            }
            map.put("status", resolvedStatus);

            List<Map<String, Object>> productsMapped = new ArrayList<>();
            for (LicenseProduct p : products) {
                Map<String, Object> pMap = new HashMap<>();
                pMap.put("productName", p.getProductName());
                pMap.put("startDate", p.getStartDate());
                pMap.put("endDate", p.getEndDate());
                pMap.put("userLimit", p.getUserLimit());
                pMap.put("concurrentLimit", p.getConcurrentLimit());
                pMap.put("gracePeriod", p.getGracePeriod());

                List<LicenseFeature> features = licenseFeatureRepository.findAllByLicenseProductUuid(p.getUuid());
                pMap.put("features", features.stream().map(LicenseFeature::getFeatureName).collect(Collectors.toList()));
                productsMapped.add(pMap);
            }
            map.put("licenseDetails", productsMapped);
            result.add(map);
        }
        return result;
    }

    @GetMapping("/licenses/{uuid}")
    public ResponseEntity<?> getLicenseById(@PathVariable UUID uuid,
            @RequestHeader(value = "x-tenant-uuid", required = false) String tenantHeader) {
        try {
            LicenseRequest req = licenseRequestRepository.findById(uuid).orElseThrow();
            // Tenant isolation: a scoped caller may only read its own license.
            TenantMaster scope = resolveScope(tenantHeader);
            if (scope != null && !scope.getUuid().equals(req.getTenantUuid())) {
                return ResponseEntity.status(404).body(Map.of("error", "License not found"));
            }
            Map<String, Object> map = new HashMap<>();
            map.put("uuid", req.getUuid());
            map.put("requestId", req.getRequestId());
            map.put("tenantUuid", req.getTenantUuid());
            map.put(FIELD_COMPANY, req.getCompanyName());
            map.put("email", req.getEmail());
            map.put("requestedBy", req.getRequestedBy());
            map.put("requestType", req.getRequestType());
            map.put("createdTimestamp", req.getCreatedTimestamp());
            map.put("totalUsers", req.getTotalUsers());
            map.put("maxConcurrent", req.getMaxConcurrent());
            map.put("startDate", req.getStartDate());
            map.put("endDate", req.getEndDate());
            map.put("gracePeriod", req.getGracePeriod());
            map.put("status", req.getStatus());

            List<LicenseProduct> products = licenseProductRepository.findAllByLicenseRequestUuid(req.getUuid());
            List<Map<String, Object>> productsMapped = new ArrayList<>();
            for (LicenseProduct p : products) {
                Map<String, Object> pMap = new HashMap<>();
                pMap.put("productName", p.getProductName());
                pMap.put("startDate", p.getStartDate());
                pMap.put("endDate", p.getEndDate());
                pMap.put("userLimit", p.getUserLimit());
                pMap.put("concurrentLimit", p.getConcurrentLimit());
                pMap.put("gracePeriod", p.getGracePeriod());
                List<LicenseFeature> features = licenseFeatureRepository.findAllByLicenseProductUuid(p.getUuid());
                pMap.put("features", features.stream().map(LicenseFeature::getFeatureName).collect(Collectors.toList()));
                productsMapped.add(pMap);
            }
            map.put("licenseDetails", productsMapped);
            return ResponseEntity.ok(map);
        } catch (Exception e) {
            return ResponseEntity.status(404).body(Map.of("error", e.getMessage()));
        }
    }

    @PostMapping("/licenses")
    public ResponseEntity<?> saveLicense(@RequestBody Map<String, Object> body, @RequestHeader(value = "x-user", defaultValue = "system-admin") String user) {
        try {
            String uuidStr = (String) body.get("uuid");
            String tenantUuidStr = (String) body.get("tenantUuid");
            String companyName = (String) body.get(FIELD_COMPANY);
            String email = (String) body.get("email");
            String requestedBy = (String) body.get("requestedBy");
            List<Map<String, Object>> details = (List<Map<String, Object>>) body.get("licenseDetails");

            Integer totalUsers = body.get("totalUsers") != null ? ((Number) body.get("totalUsers")).intValue() : null;
            Integer maxConcurrent = body.get("maxConcurrent") != null ? ((Number) body.get("maxConcurrent")).intValue() : null;
            String startDateStr = (String) body.get("startDate");
            String endDateStr = (String) body.get("endDate");
            Integer gracePeriod = body.get("gracePeriod") != null ? ((Number) body.get("gracePeriod")).intValue() : null;

            LicenseRequest req;
            UUID requestUuid;
            if (uuidStr != null && !uuidStr.isEmpty()) {
                requestUuid = UUID.fromString(uuidStr);
                req = licenseRequestRepository.findById(requestUuid).orElseThrow();
                req.setCompanyName(companyName);
                req.setEmail(email);
                req.setRequestedBy(requestedBy);
                req.setTotalUsers(totalUsers);
                req.setMaxConcurrent(maxConcurrent);
                req.setStartDate(startDateStr != null ? LocalDate.parse(startDateStr) : null);
                req.setEndDate(endDateStr != null ? LocalDate.parse(endDateStr) : null);
                req.setGracePeriod(gracePeriod);
                if (tenantUuidStr != null) {
                    req.setTenantUuid(UUID.fromString(tenantUuidStr));
                }
                licenseRequestRepository.save(req);

                // Remove existing products
                List<LicenseProduct> existing = licenseProductRepository.findAllByLicenseRequestUuid(requestUuid);
                licenseProductRepository.deleteAll(existing);
            } else {
                req = new LicenseRequest();
                req.setRequestId(sequenceService.generateNextId("LIC"));
                req.setTenantUuid(tenantUuidStr != null ? UUID.fromString(tenantUuidStr) : null);
                req.setCompanyName(companyName);
                req.setEmail(email);
                req.setRequestedBy(requestedBy);
                req.setTotalUsers(totalUsers);
                req.setMaxConcurrent(maxConcurrent);
                req.setStartDate(startDateStr != null ? LocalDate.parse(startDateStr) : null);
                req.setEndDate(endDateStr != null ? LocalDate.parse(endDateStr) : null);
                req.setGracePeriod(gracePeriod);
                req.setStatus(STATUS_DRAFT);
                req.setRequestType("New");
                LicenseRequest saved = licenseRequestRepository.save(req);
                requestUuid = saved.getUuid();
            }

            // Insert products & features
            if (details != null) {
                for (Map<String, Object> pMap : details) {
                    LicenseProduct product = new LicenseProduct();
                    product.setLicenseRequestUuid(requestUuid);
                    product.setProductName((String) pMap.get("productName"));
                    String sdStr = (String) pMap.get("startDate");
                    String edStr = (String) pMap.get("endDate");
                    product.setStartDate(sdStr != null && !sdStr.isEmpty() ? LocalDate.parse(sdStr) : null);
                    product.setEndDate(edStr != null && !edStr.isEmpty() ? LocalDate.parse(edStr) : null);
                    product.setUserLimit(pMap.get("userLimit") != null ? ((Number) pMap.get("userLimit")).intValue() : null);
                    product.setConcurrentLimit(pMap.get("concurrentLimit") != null ? ((Number) pMap.get("concurrentLimit")).intValue() : null);
                    product.setGracePeriod(pMap.get("gracePeriod") != null ? ((Number) pMap.get("gracePeriod")).intValue() : null);
                    LicenseProduct savedProd = licenseProductRepository.save(product);

                    List<String> features = (List<String>) pMap.get("features");
                    if (features != null) {
                        for (String fName : features) {
                            LicenseFeature feat = new LicenseFeature();
                            feat.setLicenseProductUuid(savedProd.getUuid());
                            feat.setFeatureName(fName);
                            licenseFeatureRepository.save(feat);
                        }
                    }
                }
            }

            auditService.logAuditEvent(uuidStr != null ? "UPDATE" : "INSERT", "License", user, "license_request", requestUuid, "localhost");
            return ResponseEntity.ok(Map.of("uuid", requestUuid, "success", true));
        } catch (Exception e) {
            return ResponseEntity.status(500).body(Map.of("error", e.getMessage()));
        }
    }

    @PostMapping("/licenses/submit/{uuid}")
    public ResponseEntity<?> submitLicense(@PathVariable UUID uuid, @RequestHeader(value = "x-user", defaultValue = "system-admin") String user) {
        try {
            LicenseRequest req = licenseRequestRepository.findById(uuid).orElseThrow();
            req.setStatus(STATUS_IN_PROG);
            licenseRequestRepository.save(req);

            ApprovalRequest approval = new ApprovalRequest();
            approval.setReferenceUuid(uuid);
            approval.setTriggerEvent("licenseApproval");
            approval.setTenantId(req.getCompanyName());
            approval.setStatus(STATUS_PENDING);
            ApprovalRequest savedApp = approvalRequestRepository.save(approval);

            ApprovalHistory history = new ApprovalHistory();
            history.setApprovalRequestUuid(savedApp.getUuid());
            history.setAction("Submit");
            history.setActorUsername(user);
            history.setNotes("License request submitted.");
            approvalHistoryRepository.save(history);

            callWorkflowService("licenseApproval", req.getRequestId());

            auditService.logAuditEvent("SUBMIT", "License", user, "license_request", uuid, "localhost");
            return ResponseEntity.ok(Map.of("success", true));
        } catch (Exception e) {
            return ResponseEntity.status(500).body(Map.of("error", e.getMessage()));
        }
    }

    @PostMapping("/licenses/renew/{uuid}")
    @org.springframework.transaction.annotation.Transactional
    public ResponseEntity<?> renewLicense(@PathVariable UUID uuid, @RequestBody Map<String, Object> body, @RequestHeader(value = "x-user", defaultValue = "system-admin") String user) {
        try {
            LicenseRequest req = licenseRequestRepository.findById(uuid).orElseThrow();
            req.setStatus(STATUS_IN_PROG);
            req.setRequestType("Renew");
            
            Integer totalUsers = body.get("totalUsers") != null ? ((Number) body.get("totalUsers")).intValue() : null;
            String startDateStr = (String) body.get("startDate");
            String endDateStr = (String) body.get("endDate");
            Integer gracePeriod = body.get("gracePeriod") != null ? ((Number) body.get("gracePeriod")).intValue() : null;
            Integer maxConcurrent = body.get("maxConcurrent") != null ? ((Number) body.get("maxConcurrent")).intValue() : null;

            req.setTotalUsers(totalUsers);
            req.setMaxConcurrent(maxConcurrent);
            req.setStartDate(startDateStr != null ? LocalDate.parse(startDateStr) : null);
            req.setEndDate(endDateStr != null ? LocalDate.parse(endDateStr) : null);
            req.setGracePeriod(gracePeriod);
            licenseRequestRepository.save(req);

            // Remove existing products
            List<LicenseProduct> existing = licenseProductRepository.findAllByLicenseRequestUuid(uuid);
            licenseProductRepository.deleteAll(existing);

            // Insert new products and features
            List<Map<String, Object>> details = (List<Map<String, Object>>) body.get("licenseDetails");
            if (details != null) {
                for (Map<String, Object> pMap : details) {
                    LicenseProduct product = new LicenseProduct();
                    product.setLicenseRequestUuid(uuid);
                    product.setProductName((String) pMap.get("productName"));
                    String pSd = (String) pMap.get("startDate");
                    String pEd = (String) pMap.get("endDate");
                    product.setStartDate(pSd != null && !pSd.isEmpty() ? LocalDate.parse(pSd) : null);
                    product.setEndDate(pEd != null && !pEd.isEmpty() ? LocalDate.parse(pEd) : null);
                    product.setUserLimit(pMap.get("userLimit") != null ? ((Number) pMap.get("userLimit")).intValue() : null);
                    product.setConcurrentLimit(pMap.get("concurrentLimit") != null ? ((Number) pMap.get("concurrentLimit")).intValue() : null);
                    product.setGracePeriod(pMap.get("gracePeriod") != null ? ((Number) pMap.get("gracePeriod")).intValue() : null);
                    LicenseProduct savedProd = licenseProductRepository.save(product);

                    List<String> features = (List<String>) pMap.get("features");
                    if (features != null) {
                        for (String fName : features) {
                            LicenseFeature feat = new LicenseFeature();
                            feat.setLicenseProductUuid(savedProd.getUuid());
                            feat.setFeatureName(fName);
                            licenseFeatureRepository.save(feat);
                        }
                    }
                }
            }

            // Create or update Approval Request
            ApprovalRequest approval = approvalRequestRepository.findFirstByReferenceUuidOrderByCreatedTimestampDesc(uuid).orElse(new ApprovalRequest());
            approval.setReferenceUuid(uuid);
            approval.setTriggerEvent("licenseRenewal");
            approval.setTenantId(req.getCompanyName());
            approval.setStatus(STATUS_PENDING);
            ApprovalRequest savedApp = approvalRequestRepository.save(approval);

            ApprovalHistory history = new ApprovalHistory();
            history.setApprovalRequestUuid(savedApp.getUuid());
            history.setAction("Submit");
            history.setActorUsername(user);
            history.setNotes("License renewal request submitted.");
            approvalHistoryRepository.save(history);

            callWorkflowService("licenseRenewal", req.getRequestId());

            auditService.logAuditEvent("RENEW", "License", user, "license_request", uuid, "localhost");
            return ResponseEntity.ok(Map.of("success", true));
        } catch (Exception e) {
            return ResponseEntity.status(500).body(Map.of("error", e.getMessage()));
        }
    }

    @PostMapping("/licenses/upgrade/{uuid}")
    @org.springframework.transaction.annotation.Transactional
    public ResponseEntity<?> upgradeLicense(@PathVariable UUID uuid, @RequestBody Map<String, Object> body, @RequestHeader(value = "x-user", defaultValue = "system-admin") String user) {
        try {
            LicenseRequest req = licenseRequestRepository.findById(uuid).orElseThrow();
            req.setStatus(STATUS_IN_PROG);
            req.setRequestType("Upgrade");
            
            Integer totalUsers = body.get("totalUsers") != null ? ((Number) body.get("totalUsers")).intValue() : null;
            String startDateStr = (String) body.get("startDate");
            String endDateStr = (String) body.get("endDate");
            Integer gracePeriod = body.get("gracePeriod") != null ? ((Number) body.get("gracePeriod")).intValue() : null;
            Integer maxConcurrent = body.get("maxConcurrent") != null ? ((Number) body.get("maxConcurrent")).intValue() : null;

            req.setTotalUsers(totalUsers);
            req.setMaxConcurrent(maxConcurrent);
            req.setStartDate(startDateStr != null ? LocalDate.parse(startDateStr) : null);
            req.setEndDate(endDateStr != null ? LocalDate.parse(endDateStr) : null);
            req.setGracePeriod(gracePeriod);
            licenseRequestRepository.save(req);

            // Remove existing products
            List<LicenseProduct> existing = licenseProductRepository.findAllByLicenseRequestUuid(uuid);
            licenseProductRepository.deleteAll(existing);

            // Insert new products and features
            List<Map<String, Object>> details = (List<Map<String, Object>>) body.get("licenseDetails");
            if (details != null) {
                for (Map<String, Object> pMap : details) {
                    LicenseProduct product = new LicenseProduct();
                    product.setLicenseRequestUuid(uuid);
                    product.setProductName((String) pMap.get("productName"));
                    String uSd = (String) pMap.get("startDate");
                    String uEd = (String) pMap.get("endDate");
                    product.setStartDate(uSd != null && !uSd.isEmpty() ? LocalDate.parse(uSd) : null);
                    product.setEndDate(uEd != null && !uEd.isEmpty() ? LocalDate.parse(uEd) : null);
                    product.setUserLimit(pMap.get("userLimit") != null ? ((Number) pMap.get("userLimit")).intValue() : null);
                    product.setConcurrentLimit(pMap.get("concurrentLimit") != null ? ((Number) pMap.get("concurrentLimit")).intValue() : null);
                    product.setGracePeriod(pMap.get("gracePeriod") != null ? ((Number) pMap.get("gracePeriod")).intValue() : null);
                    LicenseProduct savedProd = licenseProductRepository.save(product);

                    List<String> features = (List<String>) pMap.get("features");
                    if (features != null) {
                        for (String fName : features) {
                            LicenseFeature feat = new LicenseFeature();
                            feat.setLicenseProductUuid(savedProd.getUuid());
                            feat.setFeatureName(fName);
                            licenseFeatureRepository.save(feat);
                        }
                    }
                }
            }

            // Create or update Approval Request
            ApprovalRequest approval = approvalRequestRepository.findFirstByReferenceUuidOrderByCreatedTimestampDesc(uuid).orElse(new ApprovalRequest());
            approval.setReferenceUuid(uuid);
            approval.setTriggerEvent("licenseUpgrade");
            approval.setTenantId(req.getCompanyName());
            approval.setStatus(STATUS_PENDING);
            ApprovalRequest savedApp = approvalRequestRepository.save(approval);

            ApprovalHistory history = new ApprovalHistory();
            history.setApprovalRequestUuid(savedApp.getUuid());
            history.setAction("Submit");
            history.setActorUsername(user);
            history.setNotes("License upgrade request submitted.");
            approvalHistoryRepository.save(history);

            callWorkflowService("licenseUpgrade", req.getRequestId());

            auditService.logAuditEvent("UPGRADE", "License", user, "license_request", uuid, "localhost");
            return ResponseEntity.ok(Map.of("success", true));
        } catch (Exception e) {
            return ResponseEntity.status(500).body(Map.of("error", e.getMessage()));
        }
    }

    @PostMapping("/licenses/{action}/{uuid}")
    public ResponseEntity<?> handleLicenseApproval(@PathVariable String action, @PathVariable UUID uuid, @RequestBody Map<String, String> body,
            @RequestHeader(value = "x-user", defaultValue = "system-admin") String user,
            @RequestHeader(value = "x-tenant-uuid", required = false) String tenantHeader) {
        try {
            LicenseRequest req = licenseRequestRepository.findById(uuid).orElseThrow();
            ApprovalRequest approval = approvalRequestRepository.findFirstByReferenceUuidOrderByCreatedTimestampDesc(uuid).orElseThrow();
            String notes = body.get("notes");

            if ("approve".equals(action)) {
                // When a tenant (not Orque) approves a license, it consumes the tenant's
                // per-product user quota. Enforce the limit BEFORE activating.
                List<LicenseProduct> approveProds = licenseProductRepository.findAllByLicenseRequestUuid(uuid);
                TenantMaster approverScope = resolveScope(tenantHeader);
                QuotaContext quota = loadQuota(approverScope);
                ResponseEntity<?> quotaError = checkQuota(quota, approveProds);
                if (quotaError != null) return quotaError;

                req.setStatus(STATUS_ACTIVE);
                licenseRequestRepository.save(req);

                approval.setStatus(STATUS_APPROVED);
                approvalRequestRepository.save(approval);

                ApprovalHistory history = new ApprovalHistory();
                history.setApprovalRequestUuid(approval.getUuid());
                history.setAction("Approve");
                history.setActorUsername(user);
                history.setNotes(notes);
                approvalHistoryRepository.save(history);

                // Fetch details (reuse the products loaded for the quota check above)
                List<LicenseProduct> prods = approveProds;
                List<Map<String, Object>> productsList = new ArrayList<>();
                LocalDate maxEnd = req.getEndDate() != null ? req.getEndDate() : LocalDate.now();

                for (LicenseProduct p : prods) {
                    Map<String, Object> pMap = new HashMap<>();
                    pMap.put("productName", p.getProductName());
                    pMap.put("startDate", p.getStartDate().toString());
                    pMap.put("endDate", p.getEndDate().toString());
                    pMap.put("userLimit", p.getUserLimit());
                    pMap.put("concurrentLimit", p.getConcurrentLimit());
                    pMap.put("gracePeriod", p.getGracePeriod());

                    List<LicenseFeature> features = licenseFeatureRepository.findAllByLicenseProductUuid(p.getUuid());
                    pMap.put("features", features.stream().map(LicenseFeature::getFeatureName).collect(Collectors.toList()));
                    productsList.add(pMap);

                    if (p.getEndDate().isAfter(maxEnd)) maxEnd = p.getEndDate();
                }

                // Cryptographic license key generation
                Map<String, Object> licensePayload = new HashMap<>();
                licensePayload.put("licenseVersion", "1.0");
                licensePayload.put("issueDate", LocalDate.now().toString());
                licensePayload.put("expiryDate", maxEnd.toString());
                licensePayload.put("licenseType", "Standard");
                licensePayload.put("tenant", Map.of("tenantName", req.getCompanyName().toLowerCase().replaceAll("[^a-z0-9]", "-"), FIELD_COMPANY, req.getCompanyName()));
                licensePayload.put("products", productsList);

                String productsStr = objectMapper.writeValueAsString(productsList);
                String signature = licenseCryptService.generateHmac(productsStr);
                licensePayload.put("digitalSignature", signature);

                String encryptedKey = licenseCryptService.encrypt(objectMapper.writeValueAsString(licensePayload));

                // Email delivery may fail (SMTP); always print the key to the log so the
                // tenant admin can copy it and paste it into Tenant Configuration.
                System.out.println("\n========== LICENSE KEY GENERATED ==========");
                System.out.println("Request : " + req.getRequestId() + "  (" + req.getCompanyName() + ")");
                System.out.println("Expiry  : " + maxEnd);
                System.out.println("KEY     : " + encryptedKey);
                System.out.println("===========================================\n");

                UUID tenantUuid = req.getTenantUuid();
                if (tenantUuid == null) {
                    return ResponseEntity.badRequest().body(Map.of("error", "Tenant UUID mapping is null."));
                }

                LicenseMaster master = new LicenseMaster();
                master.setTenantUuid(tenantUuid);
                master.setLicenseKey(encryptedKey);
                master.setStatus(STATUS_ACTIVE);
                master.setExpiryDate(maxEnd);
                master.setDigitalSignature(signature);
                LicenseMaster savedMaster = licenseMasterRepository.save(master);

                // Link products
                for (LicenseProduct p : prods) {
                    p.setLicenseUuid(savedMaster.getUuid());
                    licenseProductRepository.save(p);
                }

                // Tenant-issued user license: decrement the per-product quota (live counter goes down).
                commitQuota(quota, prods);

                // NOTE: products are NOT activated on the tenant here. Approving (or upgrading)
                // only issues the license key. The tenant's products activate ONLY when their
                // System Admin applies the key in Tenant Configuration → Add License. This keeps
                // upgrades from silently changing the tenant's active products.

                // Send license activation email via template (subject/body come from the template)
                emailService.sendFromTemplate("license_approved", req.getEmail(), null,
                    Map.of(
                        FIELD_COMPANY, req.getCompanyName(),
                        "licenseKey",  encryptedKey
                    ));

                auditService.logAuditEvent("APPROVE", "License", user, "license_request", uuid, "localhost");
            } else if ("reject".equals(action)) {
                req.setStatus(STATUS_REJECTED);
                licenseRequestRepository.save(req);

                approval.setStatus(STATUS_REJECTED);
                approvalRequestRepository.save(approval);

                auditService.logAuditEvent("REJECT", "License", user, "license_request", uuid, "localhost");
            } else if ("return".equals(action)) {
                // Return for Revision: set back to Draft so requester can re-edit & re-submit
                req.setStatus(STATUS_DRAFT);
                licenseRequestRepository.save(req);

                approval.setStatus(STATUS_RETURNED);
                approvalRequestRepository.save(approval);

                auditService.logAuditEvent("RETURN", "License", user, "license_request", uuid, "localhost");
            }

            return ResponseEntity.ok(Map.of("success", true));
        } catch (Exception e) {
            return ResponseEntity.status(500).body(Map.of("error", e.getMessage()));
        }
    }

    /**
     * Decrypt-only: validates an encrypted license key (signature + expiry) and returns
     * the product details WITHOUT persisting anything. Used by the Tenant Configuration
     * screen to preview a pasted key before/while adding it.
     */
    @PostMapping("/licenses/decrypt")
    public ResponseEntity<?> decryptLicense(@RequestBody Map<String, String> body) {
        try {
            String licenseKey = body.get("licenseKey");
            if (licenseKey == null || licenseKey.isBlank()) {
                return ResponseEntity.badRequest().body(Map.of("error", "License key is required."));
            }
            Map<String, Object> payload = decryptAndVerify(licenseKey.trim());
            return ResponseEntity.ok(Map.of("success", true, "payload", payload));
        } catch (LicenseException le) {
            return ResponseEntity.badRequest().body(Map.of("error", le.getMessage()));
        } catch (Exception e) {
            return ResponseEntity.status(400).body(Map.of("error", "Decryption failed. Please provide a valid encrypted license key string."));
        }
    }

    /** Thrown for business-level license validation failures (bad signature / expired). */
    private static class LicenseException extends RuntimeException {
        LicenseException(String message) { super(message); }
    }

    /** Decrypts a license key, verifies its HMAC signature and expiry, and returns the payload. */
    @SuppressWarnings("unchecked")
    private Map<String, Object> decryptAndVerify(String licenseKey) throws Exception {
        String decryptedStr = licenseCryptService.decrypt(licenseKey);
        Map<String, Object> payload = objectMapper.readValue(decryptedStr, new TypeReference<Map<String, Object>>() {});

        List<Map<String, Object>> products = (List<Map<String, Object>>) payload.get("products");
        String signature = (String) payload.get("digitalSignature");

        String productsStr = objectMapper.writeValueAsString(products);
        String calculatedSig = licenseCryptService.generateHmac(productsStr);
        if (!calculatedSig.equals(signature)) {
            throw new LicenseException("Digital signature validation failed. License key has been tampered.");
        }

        String expiry = (String) payload.get("expiryDate");
        if (expiry != null && LocalDate.parse(expiry).isBefore(LocalDate.now())) {
            throw new LicenseException("The uploaded license key has expired.");
        }
        return payload;
    }

    /**
     * Sub-license generation: a tenant admin (e.g. AKRO) generates a license key for one of
     * their own business users, constrained to the products the tenant is licensed for
     * (from tenant_configuration.settings_json.licensedProducts). The key is signed + encrypted
     * the same way as an Orque-issued key, so the recipient can paste it in Tenant Configuration.
     */
    @PostMapping("/licenses/generate")
    @SuppressWarnings("unchecked")
    public ResponseEntity<?> generateSubLicense(@RequestBody Map<String, Object> body,
            @RequestHeader(value = "x-user", defaultValue = "system-admin") String user,
            @RequestHeader(value = "x-tenant-uuid", required = false) String tenantHeader) {
        try {
            TenantMaster scope = resolveScope(tenantHeader);
            if (scope == null) {
                return ResponseEntity.badRequest().body(Map.of("error",
                    "Only a tenant admin can generate licenses for their users."));
            }

            // Only the tenant's SYSTEM_ADMIN may issue licenses. Business users (requesters,
            // approvers, viewers) cannot generate licenses from Tenant Configuration.
            boolean isAdmin = userMasterRepository.findByUsername(user)
                    .map(u -> "SYSTEM_ADMIN".equalsIgnoreCase(u.getRole()))
                    .orElse(false);
            if (!isAdmin) {
                return ResponseEntity.status(403).body(Map.of("error",
                    "Only a tenant system admin can generate licenses."));
            }

            // Load this tenant's licensed products
            TenantConfiguration config = tenantConfigurationRepository.findByTenantUuid(scope.getUuid()).orElse(null);
            Map<String, Object> settings = new HashMap<>();
            Map<String, Object> licensed = new HashMap<>();
            if (config != null && config.getSettingsJson() != null && !config.getSettingsJson().isBlank()) {
                settings = objectMapper.readValue(config.getSettingsJson(),
                        new TypeReference<Map<String, Object>>() {});
                Object lp = settings.get("licensedProducts");
                if (lp instanceof Map) licensed = (Map<String, Object>) lp;
            }
            if (licensed.isEmpty()) {
                return ResponseEntity.badRequest().body(Map.of("error",
                    "No licensed products found. Apply your Orque license in Tenant Configuration first."));
            }

            List<Map<String, Object>> requested = (List<Map<String, Object>>) body.get("products");
            if (requested == null || requested.isEmpty()) {
                return ResponseEntity.badRequest().body(Map.of("error", "Select at least one product."));
            }
            String assignedTo = String.valueOf(body.getOrDefault("assignedTo", "")).trim();

            List<Map<String, Object>> productsList = new ArrayList<>();
            LocalDate maxEnd = LocalDate.now();
            for (Map<String, Object> rp : requested) {
                String name = String.valueOf(rp.get("productName"));
                Object licProdObj = licensed.get(name.toLowerCase());
                if (licProdObj == null) {
                    return ResponseEntity.badRequest().body(Map.of("error",
                        "Product not licensed for your tenant: " + name));
                }
                Map<String, Object> licProd = (Map<String, Object>) licProdObj;
                String expiry = (String) licProd.get("expiry");
                LocalDate end = expiry != null ? LocalDate.parse(expiry) : LocalDate.now().plusYears(1);
                if (end.isAfter(maxEnd)) maxEnd = end;

                int requestedCount = licProd.get("userLimit") == null ? 0
                        : toInt(rp.getOrDefault("userLimit", 1));
                int purchased = toInt(licProd.get("userLimit"));   // 0 when not tracked (legacy)
                int issued    = toInt(licProd.get("issued"));

                // Enforce the per-product quota: total issued may not exceed the purchased count.
                if (purchased > 0 && issued + requestedCount > purchased) {
                    return ResponseEntity.badRequest().body(Map.of("error",
                        name.toUpperCase() + ": only " + (purchased - issued) + " of " + purchased
                        + " user licenses remaining — cannot issue " + requestedCount + "."));
                }
                licProd.put("issued", issued + requestedCount);    // reserve the quota

                Map<String, Object> p = new HashMap<>();
                p.put("productName", name.toUpperCase());
                p.put("startDate", LocalDate.now().toString());
                p.put("endDate", end.toString());
                p.put("userLimit", requestedCount);
                p.put("features", licProd.get("features"));
                productsList.add(p);
            }

            Map<String, Object> payload = new HashMap<>();
            payload.put("licenseVersion", "1.0");
            payload.put("issueDate", LocalDate.now().toString());
            payload.put("expiryDate", maxEnd.toString());
            payload.put("licenseType", "SubLicense");
            payload.put("tenant", Map.of("tenantName", scope.getTenantName(), FIELD_COMPANY, scope.getCompanyName()));
            payload.put("assignedTo", assignedTo);
            payload.put("products", productsList);

            String productsStr = objectMapper.writeValueAsString(productsList);
            String signature = licenseCryptService.generateHmac(productsStr);
            payload.put("digitalSignature", signature);

            String licenseKey = licenseCryptService.encrypt(objectMapper.writeValueAsString(payload));

            System.out.println("\n===== SUB-LICENSE GENERATED (" + scope.getTenantName() + ") for " + assignedTo + " =====");
            System.out.println("KEY: " + licenseKey);
            System.out.println("====================================================\n");

            if (!assignedTo.isBlank()) {
                try {
                    emailService.sendEmail(assignedTo, null,
                        "Your OPAC License Key", licenseEmail(licenseKey));
                } catch (Exception ignored) { /* email best-effort; key is logged + returned */ }
            }

            // Persist the updated per-product issued counters so the quota survives.
            if (config != null) {
                settings.put("licensedProducts", licensed);
                config.setSettingsJson(objectMapper.writeValueAsString(settings));
                tenantConfigurationRepository.save(config);
            }

            auditService.logAuditEvent("GENERATE_SUBLICENSE", "License", user, "tenant_configuration", scope.getUuid(), "localhost");
            return ResponseEntity.ok(Map.of("success", true, "licenseKey", licenseKey, "payload", payload));
        } catch (Exception e) {
            return ResponseEntity.status(400).body(Map.of("error", "Failed to generate license: " + e.getMessage()));
        }
    }

    /** Lenient int parse for values that may arrive as Integer, Long, Double or String. */
    private int toInt(Object o) {
        if (o == null) return 0;
        if (o instanceof Number n) return n.intValue();
        try { return Integer.parseInt(o.toString().trim()); } catch (NumberFormatException e) { return 0; }
    }

    /** Holds a tenant's mutable licensed-products quota during an approval. */
    private static class QuotaContext {
        TenantConfiguration config;
        Map<String, Object> settings;
        Map<String, Object> licensed; // null when there is no quota to enforce
    }

    /** Loads the approving tenant's quota; returns an empty context for Orque (no quota). */
    @SuppressWarnings("unchecked")
    private QuotaContext loadQuota(TenantMaster scope) {
        QuotaContext q = new QuotaContext();
        if (scope == null) return q;
        q.config = tenantConfigurationRepository.findByTenantUuid(scope.getUuid()).orElse(null);
        try {
            if (q.config != null && q.config.getSettingsJson() != null && !q.config.getSettingsJson().isBlank()) {
                q.settings = objectMapper.readValue(q.config.getSettingsJson(),
                        new TypeReference<Map<String, Object>>() {});
                Object lp = q.settings.get("licensedProducts");
                if (lp instanceof Map) q.licensed = (Map<String, Object>) lp;
            }
        } catch (Exception ignored) { /* no quota */ }
        return q;
    }

    /** Returns an error response if approving these products would exceed the quota, else null. */
    @SuppressWarnings("unchecked")
    private ResponseEntity<?> checkQuota(QuotaContext q, List<LicenseProduct> prods) {
        if (q.licensed == null) return null;
        for (LicenseProduct p : prods) {
            Map<String, Object> licProd = (Map<String, Object>) q.licensed.get(p.getProductName().toLowerCase());
            if (licProd == null) continue;
            int purchased = toInt(licProd.get("userLimit"));
            int issued = toInt(licProd.get("issued"));
            int count = p.getUserLimit() != null ? p.getUserLimit() : 1;
            if (purchased > 0 && issued + count > purchased) {
                return ResponseEntity.badRequest().body(Map.of("error",
                    p.getProductName().toUpperCase() + ": only " + (purchased - issued) + " of " + purchased
                    + " user licenses remaining — cannot issue " + count + "."));
            }
        }
        return null;
    }

    /** Decrements (reserves) the quota for these products and persists it. */
    @SuppressWarnings("unchecked")
    private void commitQuota(QuotaContext q, List<LicenseProduct> prods) {
        if (q.licensed == null || q.config == null) return;
        for (LicenseProduct p : prods) {
            Map<String, Object> licProd = (Map<String, Object>) q.licensed.get(p.getProductName().toLowerCase());
            if (licProd == null) continue;
            int issued = toInt(licProd.get("issued"));
            int count = p.getUserLimit() != null ? p.getUserLimit() : 1;
            licProd.put("issued", issued + count);
        }
        try {
            q.settings.put("licensedProducts", q.licensed);
            q.config.setSettingsJson(objectMapper.writeValueAsString(q.settings));
            tenantConfigurationRepository.save(q.config);
        } catch (Exception ignored) { /* best-effort */ }
    }

    /** Per-product license quota for the scoped tenant: purchased / issued / remaining. */
    @GetMapping("/license-quota")
    @SuppressWarnings("unchecked")
    public ResponseEntity<?> getLicenseQuota(
            @RequestHeader(value = "x-tenant-uuid", required = false) String tenantHeader) {
        try {
            TenantMaster scope = resolveScope(tenantHeader);
            List<Map<String, Object>> result = new ArrayList<>();
            if (scope == null) return ResponseEntity.ok(result);

            TenantConfiguration config = tenantConfigurationRepository.findByTenantUuid(scope.getUuid()).orElse(null);
            if (config != null && config.getSettingsJson() != null && !config.getSettingsJson().isBlank()) {
                Map<String, Object> settings = objectMapper.readValue(config.getSettingsJson(),
                        new TypeReference<Map<String, Object>>() {});
                Object lp = settings.get("licensedProducts");
                if (lp instanceof Map) {
                    Map<String, Object> licensed = (Map<String, Object>) lp;
                    for (Map.Entry<String, Object> e : licensed.entrySet()) {
                        Map<String, Object> prod = (Map<String, Object>) e.getValue();
                        int purchased = toInt(prod.get("userLimit"));
                        int issued = toInt(prod.get("issued"));
                        Map<String, Object> m = new HashMap<>();
                        m.put("productName", e.getKey().toUpperCase());
                        m.put("purchased", purchased);
                        m.put("issued", issued);
                        m.put("remaining", Math.max(purchased - issued, 0));
                        m.put("expiry", prod.get("expiry"));
                        m.put("features", prod.get("features"));
                        result.add(m);
                    }
                }
            }
            return ResponseEntity.ok(result);
        } catch (Exception e) {
            return ResponseEntity.status(500).body(Map.of("error", e.getMessage()));
        }
    }

    @PostMapping("/licenses/apply")
    public ResponseEntity<?> applyLicense(@RequestBody Map<String, String> body,
            @RequestHeader(value = "x-user", defaultValue = "system-admin") String user,
            @RequestHeader(value = "x-tenant-uuid", required = false) String tenantHeader) {
        try {
            String licenseKey = body.get("licenseKey").trim();
            UUID tenantUuid = UUID.fromString(body.get("tenantUuid"));

            // Isolation: a tenant-scoped admin may only apply a license to their own tenant.
            TenantMaster scope = resolveScope(tenantHeader);
            if (scope != null) {
                tenantUuid = scope.getUuid();
            }

            // Decrypt key
            String decryptedStr = licenseCryptService.decrypt(licenseKey);
            Map<String, Object> payload = objectMapper.readValue(decryptedStr, new TypeReference<Map<String, Object>>() {});

            List<Map<String, Object>> products = (List<Map<String, Object>>) payload.get("products");
            String signature = (String) payload.get("digitalSignature");

            // Verify signature
            String productsStr = objectMapper.writeValueAsString(products);
            String calculatedSig = licenseCryptService.generateHmac(productsStr);
            if (!calculatedSig.equals(signature)) {
                return ResponseEntity.badRequest().body(Map.of("error", "Digital signature validation failed. License key has been tampered."));
            }

            // Expiry validation
            String expiry = (String) payload.get("expiryDate");
            LocalDate expiryDate = LocalDate.parse(expiry);
            if (expiryDate.isBefore(LocalDate.now())) {
                return ResponseEntity.badRequest().body(Map.of("error", "The uploaded license key has expired."));
            }

            // Save to Master
            LicenseMaster master = new LicenseMaster();
            master.setTenantUuid(tenantUuid);
            master.setLicenseKey(licenseKey);
            master.setStatus(STATUS_ACTIVE);
            master.setExpiryDate(expiryDate);
            master.setDigitalSignature(signature);
            licenseMasterRepository.save(master);

            // Update Configuration settings json — store the purchased userLimit per product
            // plus an "issued" counter used to enforce the per-product license quota.
            Map<String, Object> settingsMap = new HashMap<>();
            Map<String, Object> licensedProds = new HashMap<>();
            for (Map<String, Object> p : products) {
                Map<String, Object> prodInfo = new HashMap<>();
                prodInfo.put("enabled", true);
                prodInfo.put("expiry", p.get("endDate"));
                prodInfo.put("userLimit", p.get("userLimit"));
                prodInfo.put("issued", 0);
                prodInfo.put("features", p.get("features"));
                licensedProds.put((String) p.get("productName"), prodInfo);
            }
            settingsMap.put("licensedProducts", licensedProds);

            TenantConfiguration config = tenantConfigurationRepository.findByTenantUuid(tenantUuid).orElseThrow();
            config.setSettingsJson(objectMapper.writeValueAsString(settingsMap));
            tenantConfigurationRepository.save(config);

            auditService.logAuditEvent("APPLY_LICENSE", "License", user, "tenant_configuration", tenantUuid, "localhost");
            return ResponseEntity.ok(Map.of("success", true, "payload", payload));
        } catch (Exception e) {
            return ResponseEntity.status(400).body(Map.of("error", "Decryption failed. Please provide a valid encrypted license key string."));
        }
    }

    // =========================================================================
    // SESSION MANAGEMENT APIs  (GET /sessions moved to bottom with status mapping)
    // =========================================================================
    @PostMapping("/sessions/create")
    public ResponseEntity<?> createSession(@RequestBody SessionMaster session) {
        SessionMaster saved = sessionMasterRepository.save(session);
        return ResponseEntity.ok(saved);
    }

    @PostMapping("/sessions/terminate")
    public ResponseEntity<?> terminateSession(@RequestBody Map<String, String> body, @RequestHeader(value = "x-user", defaultValue = "system-admin") String user) {
        try {
            UUID uuid = UUID.fromString(body.get("uuid"));
            SessionMaster s = sessionMasterRepository.findById(uuid).orElseThrow();
            s.setLogoutTimestamp(LocalDateTime.now());
            s.setSessionDurationSeconds(600); // Mock 10 minutes session duration
            sessionMasterRepository.save(s);
            auditService.logAuditEvent("FORCE_LOGOUT", "Session", user, "session_master", uuid, "localhost");
            return ResponseEntity.ok(Map.of("success", true));
        } catch (Exception e) {
            return ResponseEntity.status(500).body(Map.of("error", e.getMessage()));
        }
    }

    // =========================================================================
    // UTILITY APIs (Audits, Notifications, active master list)
    // =========================================================================
    @GetMapping("/audit-logs")
    public List<Map<String, Object>> getAuditLogs(
            @RequestHeader(value = "x-tenant-uuid", required = false) String tenantHeader) {
        List<AuditLog> logs = auditLogRepository.findAllByOrderByCreatedTimestampDesc();
        TenantMaster scope = resolveScope(tenantHeader);
        List<Map<String, Object>> result = new ArrayList<>();
        for (AuditLog l : logs) {
            if (scope != null && !scope.getUuid().equals(l.getTenantUuid())) continue;
            Map<String, Object> map = new HashMap<>();
            map.put("uuid", l.getUuid());
            map.put("action", l.getAction());
            map.put("module", l.getModule());
            map.put("username", l.getUsername());
            map.put("entity_name", l.getEntityName());
            map.put("entity_uuid", l.getEntityUuid());
            map.put("ip_address", l.getIpAddress());
            map.put("created_timestamp", l.getCreatedTimestamp());

            List<AuditHistory> history = auditHistoryRepository.findAllByAuditLogUuid(l.getUuid());
            map.put("changes", history.stream().map(h -> h.getFieldName() + ": " + h.getOldValue() + " -> " + h.getNewValue()).collect(Collectors.toList()));
            result.add(map);
        }
        return result;
    }

    @GetMapping("/tenants-master")
    public List<Map<String, Object>> getTenantsMaster(
            @RequestHeader(value = "x-tenant-uuid", required = false) String tenantHeader) {
        List<TenantMaster> masters = tenantMasterRepository.findAll();
        TenantMaster scope = resolveScope(tenantHeader);
        List<Map<String, Object>> result = new ArrayList<>();
        for (TenantMaster m : masters) {
            if (scope != null && !scope.getUuid().equals(m.getUuid())) continue;
            Map<String, Object> map = new HashMap<>();
            map.put("uuid", m.getUuid());
            map.put("tenant_name", m.getTenantName());
            map.put("company_name", m.getCompanyName());
            map.put("status", m.getStatus());

            Optional<TenantConfiguration> config = tenantConfigurationRepository.findByTenantUuid(m.getUuid());
            if (config.isPresent()) {
                map.put("branding_logo_url", config.get().getBrandingLogoUrl());
                map.put("theme_primary_color", config.get().getThemePrimaryColor());
                try {
                    map.put("settings_json", objectMapper.readValue(config.get().getSettingsJson(), new TypeReference<Map<String, Object>>() {}));
                } catch (Exception e) {
                    map.put("settings_json", Collections.emptyMap());
                }
            }
            result.add(map);
        }
        return result;
    }

    @GetMapping("/tenant-configuration")
    public List<Map<String, Object>> getTenantConfiguration(
            @RequestHeader(value = "x-tenant-uuid", required = false) String tenantHeader) {
        TenantMaster scope = resolveScope(tenantHeader);
        List<TenantConfiguration> configs = tenantConfigurationRepository.findAll();
        List<Map<String, Object>> result = new ArrayList<>();
        for (TenantConfiguration cfg : configs) {
            if (scope != null && !scope.getUuid().equals(cfg.getTenantUuid())) continue;

            String tenantName = tenantMasterRepository.findById(cfg.getTenantUuid())
                    .map(TenantMaster::getTenantName).orElse("");

            String licensedProducts = "";
            try {
                Map<String, Object> settings = objectMapper.readValue(
                        cfg.getSettingsJson() == null || cfg.getSettingsJson().isBlank() ? "{}" : cfg.getSettingsJson(),
                        new TypeReference<Map<String, Object>>() {});
                Object lp = settings.get("licensedProducts");
                if (lp instanceof Map<?, ?> lpMap) {
                    licensedProducts = String.join(", ", lpMap.keySet().stream().map(Object::toString).toList());
                }
            } catch (Exception ignored) { /* malformed settings json */ }

            Map<String, Object> map = new HashMap<>();
            map.put("configId", cfg.getUuid());
            map.put("tenantUuid", cfg.getTenantUuid());
            map.put("tenantName", tenantName);
            map.put("configKey", "Licensed Products");
            map.put("configValue", licensedProducts.isEmpty() ? "—" : licensedProducts);
            map.put("category", "License");
            map.put("status", "Active");
            map.put("updatedAt", cfg.getUpdatedTimestamp());
            result.add(map);
        }
        return result;
    }

    @GetMapping("/notifications")
    public List<NotificationMaster> getNotifications(
            @RequestHeader(value = "x-tenant-uuid", required = false) String tenantHeader) {
        List<NotificationMaster> all = notificationMasterRepository.findAllByOrderByCreatedTimestampDesc();
        TenantMaster scope = resolveScope(tenantHeader);
        if (scope == null) return all;
        return all.stream()
            .filter(n -> scope.getUuid().equals(n.getTenantUuid()))
            .collect(Collectors.toList());
    }

    @GetMapping("/email-queue")
    public List<EmailQueue> getEmailQueue() {
        return emailQueueRepository.findAllByOrderByCreatedTimestampDesc();
    }

    /** Share dialog — send a manual email from the UI */
    @PostMapping("/email/send")
    public ResponseEntity<?> sendEmail(@RequestBody Map<String, Object> body) {
        try {
            String to      = (String) body.get("to");
            String cc      = (String) body.get("cc");
            String subject = (String) body.get("subject");
            String text    = (String) body.getOrDefault("body", "");
            String type    = (String) body.get("type");
            String uuidStr = (String) body.get("uuid");
            if (to == null || to.isBlank()) {
                return ResponseEntity.badRequest().body(Map.of("error", "Recipient email is required."));
            }
            // Enrich the message with the relevant secret: tenant/user → login credentials,
            // license → the license key. (Generic 'Active' shares stay as-is.)
            String enriched = buildShareBody(text == null ? "" : text, type, uuidStr);
            emailService.sendEmail(to, cc, subject, enriched);
            return ResponseEntity.ok(Map.of("success", true));
        } catch (Exception e) {
            return ResponseEntity.status(500).body(Map.of("error", e.getMessage()));
        }
    }

    /** Resets a user's password to a known temporary value so shared credentials work. */
    private String resetTempPassword(UserMaster u, String pwd) {
        try {
            u.setPassword(passwordService.hashPassword(pwd));
            userMasterRepository.save(u);
        } catch (Exception ignored) { /* keep existing password on hash failure */ }
        return pwd;
    }

    /** Appends credentials / license key to a share email based on the record type. */
    private String nz(String s) { return s == null ? "" : s; }

    private String buildShareBody(String base, String type, String uuidStr) {
        if (type == null || uuidStr == null) return base;
        final String temp = "Welcome@123!";
        try {
            UUID uuid = UUID.fromString(uuidStr);

            if ("user".equalsIgnoreCase(type)) {
                return userMasterRepository.findById(uuid).map(u -> {
                    resetTempPassword(u, temp);
                    String first = (u.getFirstName() != null && !u.getFirstName().isBlank())
                            ? u.getFirstName() : nz(u.getUsername());
                    return "Dear " + first + ",\n\n"
                        + "A user account has been created for you on the OPAC platform. "
                        + "Your login credentials are provided below.\n\n"
                        + "Login Credentials\n"
                        + "  Login Mode : Business User\n"
                        + "  Tenant     : " + nz(u.getTenantName()) + "\n"
                        + "  Username   : " + nz(u.getUsername()) + "\n"
                        + "  Password   : " + temp + "\n"
                        + "  Role       : " + nz(u.getRole()) + "\n\n"
                        + "Getting Started\n"
                        + "  1. Open the OPAC portal.\n"
                        + "  2. Select the Business User login tab.\n"
                        + "  3. Enter the Tenant, Username, and Password provided above.\n"
                        + "  4. Click Login.\n"
                        + "  5. Change your password after your first successful login.\n\n"
                        + "Security Notice\n"
                        + "For security reasons, please do not share your login credentials with anyone. "
                        + "If you experience any issues accessing your account, contact your system administrator.\n\n"
                        + "Best regards,\nOPAC System";
                }).orElse(base);

            } else if ("tenant".equalsIgnoreCase(type)) {
                return tenantRequestRepository.findById(uuid).map(req -> {
                    userMasterRepository.findByUsername(req.getAdminUsername())
                            .ifPresent(admin -> resetTempPassword(admin, temp));
                    return "Dear Team,\n\n"
                        + "We are pleased to inform you that your tenant \"" + nz(req.getCompanyName())
                        + "\" has been successfully activated on the OPAC platform.\n\n"
                        + "Your System Administrator account has been created and is ready for use.\n\n"
                        + "System Administrator Credentials\n"
                        + "  Login Mode : System Admin\n"
                        + "  Tenant     : " + nz(req.getTenantName()) + "\n"
                        + "  Username   : " + nz(req.getAdminUsername()) + "\n"
                        + "  Password   : " + temp + "\n\n"
                        + "Getting Started\n"
                        + "  1. Open the OPAC portal.\n"
                        + "  2. Select the System Admin login tab.\n"
                        + "  3. Enter the Tenant, Username, and Password provided above.\n"
                        + "  4. Navigate to System Settings to create users and assign roles.\n"
                        + "  5. Open Tenant Configuration and apply your OPAC license key to activate products and features.\n"
                        + "  6. Review tenant settings and complete any required configuration.\n"
                        + "  7. Change your password after your first successful login.\n\n"
                        + "Important Security Notice\n"
                        + "Please keep these credentials confidential and share them only with authorized personnel. "
                        + "For security reasons, we strongly recommend updating the default password immediately after your first login.\n\n"
                        + "If you require assistance with tenant setup, user onboarding, or license activation, "
                        + "please contact the OPAC support team.\n\n"
                        + "Best regards,\nOPAC System";
                }).orElse(base);

            } else if ("license".equalsIgnoreCase(type)) {
                return licenseRequestRepository.findById(uuid).map(req -> {
                    String key = "";
                    if (req.getTenantUuid() != null) {
                        key = licenseMasterRepository.findFirstByTenantUuidOrderByCreatedTimestampDesc(req.getTenantUuid())
                                .map(LicenseMaster::getLicenseKey).orElse("");
                    }
                    return licenseEmail(key);
                }).orElse(base);
            }
        } catch (Exception ignored) { /* enrichment is best-effort */ }
        return base;
    }

    /** Shared license-activation email body (used by Share and sub-license generation). */
    private String licenseEmail(String licenseKey) {
        return "Dear Team,\n\n"
            + "Your OPAC license has been successfully activated.\n\n"
            + "To complete the setup, please log in to the OPAC application and navigate to:\n\n"
            + "Tenant Configuration -> Add License\n\n"
            + "Then paste the license key provided below.\n\n"
            + "OPAC License Key\n\n"
            + nz(licenseKey) + "\n\n"
            + "If you encounter any issues during activation, please contact the system administrator or support team.\n\n"
            + "Best regards,\nOPAC System";
    }

    // =========================================================================
    // USERS MANAGEMENT APIs
    // =========================================================================
    @GetMapping("/users")
    public List<Map<String, Object>> getUsers(
            @RequestHeader(value = "x-tenant-uuid", required = false) String tenantHeader) {
        List<UserMaster> users = userService.getAllUsers();
        TenantMaster scope = resolveScope(tenantHeader);
        List<Map<String, Object>> result = new ArrayList<>();
        for (UserMaster u : users) {
            if (scope != null && !scope.getUuid().equals(u.getTenantUuid())) continue;
            Map<String, Object> map = new HashMap<>();
            map.put("userId", u.getUuid());
            map.put("tenantUuid", u.getTenantUuid());
            map.put("username", u.getUsername());
            map.put("email", u.getEmail());
            map.put("status", u.getStatus());
            map.put("firstName", u.getFirstName());
            map.put("lastName", u.getLastName());
            map.put("role", u.getRole());
            map.put("tenantName", u.getTenantName());
            map.put("contactNumber", u.getContactNumber());
            map.put("createdTimestamp", u.getCreatedTimestamp());
            result.add(map);
        }
        return result;
    }

    @PostMapping("/users")
    public ResponseEntity<?> saveUser(@RequestBody Map<String, Object> body,
            @RequestHeader(value = "x-user", defaultValue = "system-admin") String actor,
            @RequestHeader(value = "x-tenant-uuid", required = false) String tenantHeader) {
        try {
            // Force tenant-scoped admins (e.g. AKRO) to create users only within their tenant.
            TenantMaster scope = resolveScope(tenantHeader);
            if (scope != null) {
                body.put("tenantName", scope.getTenantName());
            }
            String userIdStr = (String) body.get("userId");
            if (userIdStr == null) {
                userIdStr = (String) body.get("uuid");
            }
            boolean isNew = (userIdStr == null || userIdStr.isEmpty());

            UserMaster user = new UserMaster();
            if (!isNew) {
                user.setUuid(UUID.fromString(userIdStr));
            }
            user.setUsername((String) body.get("username"));
            user.setEmail((String) body.get("email"));
            user.setFirstName((String) body.get("firstName"));
            user.setLastName((String) body.get("lastName"));
            user.setRole((String) body.get("role"));
            user.setTenantName((String) body.get("tenantName"));
            user.setContactNumber((String) body.get("contactNumber"));
            user.setStatus((String) body.get("status"));

            // The user form delegates auth and sends no password. For new users assign a
            // temporary password (default if none supplied) so they can sign in; the admin
            // shares it with the user. Updates keep the existing password untouched.
            String tempPassword = null;
            if (isNew) {
                String rawPassword = (String) body.get("password");
                if (rawPassword == null || rawPassword.isBlank()) {
                    rawPassword = "Welcome@123!";
                }
                tempPassword = rawPassword;
                user.setPassword(passwordService.hashPassword(rawPassword));
            }

            UserMaster saved = userService.saveUser(user, actor);
            Map<String, Object> resp = new HashMap<>();
            resp.put("userId", saved.getUuid());
            resp.put("success", true);
            if (tempPassword != null) {
                resp.put("tempPassword", tempPassword);
            }
            return ResponseEntity.ok(resp);
        } catch (IllegalArgumentException e) {
            return ResponseEntity.badRequest().body(Map.of("error", e.getMessage()));
        } catch (Exception e) {
            return ResponseEntity.status(500).body(Map.of("error", e.getMessage()));
        }
    }

    @PostMapping("/users/{userId}")
    public ResponseEntity<?> updateUser(@PathVariable UUID userId, @RequestBody Map<String, Object> body,
            @RequestHeader(value = "x-user", defaultValue = "system-admin") String actor,
            @RequestHeader(value = "x-tenant-uuid", required = false) String tenantHeader) {
        body.put("userId", userId.toString());
        return saveUser(body, actor, tenantHeader);
    }

    @PostMapping("/users/deactivate/{userId}")
    public ResponseEntity<?> deactivateUser(@PathVariable UUID userId, @RequestHeader(value = "x-user", defaultValue = "system-admin") String actor) {
        try {
            userService.deactivateUser(userId, actor);
            return ResponseEntity.ok(Map.of("success", true));
        } catch (Exception e) {
            return ResponseEntity.status(500).body(Map.of("error", e.getMessage()));
        }
    }

    @PostMapping("/users/activate/{userId}")
    public ResponseEntity<?> activateUser(@PathVariable UUID userId, @RequestHeader(value = "x-user", defaultValue = "system-admin") String actor) {
        try {
            userService.activateUser(userId, actor);
            return ResponseEntity.ok(Map.of("success", true));
        } catch (Exception e) {
            return ResponseEntity.status(500).body(Map.of("error", e.getMessage()));
        }
    }

    // =========================================================================
    // ROLES MANAGEMENT APIs
    // =========================================================================
    @GetMapping("/roles")
    public List<Map<String, Object>> getRoles(
            @RequestHeader(value = "x-tenant-uuid", required = false) String tenantHeader) {
        List<RoleMaster> roles = roleService.getAllRoles();
        TenantMaster scope = resolveScope(tenantHeader);
        List<Map<String, Object>> result = new ArrayList<>();
        for (RoleMaster r : roles) {
            if (scope != null && !scope.getUuid().equals(r.getTenantUuid())) continue;
            Map<String, Object> map = new HashMap<>();
            map.put("roleId", r.getUuid());
            map.put("tenantUuid", r.getTenantUuid());
            map.put("roleName", r.getRoleName());
            map.put("isSystemDefault", r.getIsSystemDefault());
            map.put("roleCode", r.getRoleCode());
            map.put("roleType", r.getRoleType());
            map.put("description", r.getDescription());
            map.put("status", r.getStatus());
            map.put("tenantName", r.getTenantName());
            map.put("createdTimestamp", r.getCreatedTimestamp());

            List<RolePermission> perms = rolePermissionRepository.findAllByRoleUuid(r.getUuid());
            List<String> actions = new ArrayList<>();
            for (RolePermission p : perms) {
                if (p.getAccessPolicyKey() != null && p.getAccessPolicyKey().startsWith("action:")) {
                    actions.add(p.getAccessPolicyKey().substring(7));
                }
            }
            map.put("actions", actions);
            map.put("permissionCount", perms.size());
            result.add(map);
        }
        return result;
    }

    @PostMapping("/roles")
    @org.springframework.transaction.annotation.Transactional
    public ResponseEntity<?> saveRole(@RequestBody Map<String, Object> body,
            @RequestHeader(value = "x-user", defaultValue = "system-admin") String actor,
            @RequestHeader(value = "x-tenant-uuid", required = false) String tenantHeader) {
        try {
            // Force tenant-scoped admins (e.g. AKRO) to create roles only within their tenant.
            TenantMaster scope = resolveScope(tenantHeader);
            if (scope != null) {
                body.put("tenantName", scope.getTenantName());
            }
            String roleIdStr = (String) body.get("roleId");
            if (roleIdStr == null) {
                roleIdStr = (String) body.get("uuid");
            }
            RoleMaster role = new RoleMaster();
            if (roleIdStr != null && !roleIdStr.isEmpty()) {
                role.setUuid(UUID.fromString(roleIdStr));
            }
            role.setRoleName((String) body.get("roleName"));
            role.setRoleCode((String) body.get("roleCode"));
            role.setRoleType((String) body.get("roleType"));
            role.setDescription((String) body.get("description"));
            role.setStatus((String) body.get("status"));
            role.setTenantName((String) body.get("tenantName"));

            RoleMaster saved = roleService.saveRole(role, actor);

            List<String> actions = (List<String>) body.get("actions");
            if (actions != null) {
                rolePermissionRepository.deleteAllByRoleUuid(saved.getUuid());
                for (String actionName : actions) {
                    RolePermission rp = new RolePermission();
                    rp.setRoleUuid(saved.getUuid());
                    rp.setAccessPolicyKey("action:" + actionName);
                    rolePermissionRepository.save(rp);
                }
            }

            return ResponseEntity.ok(Map.of("roleId", saved.getUuid(), "success", true));
        } catch (IllegalArgumentException e) {
            return ResponseEntity.badRequest().body(Map.of("error", e.getMessage()));
        } catch (Exception e) {
            return ResponseEntity.status(500).body(Map.of("error", e.getMessage()));
        }
    }

    @PostMapping("/roles/{roleId}")
    public ResponseEntity<?> updateRole(@PathVariable UUID roleId, @RequestBody Map<String, Object> body,
            @RequestHeader(value = "x-user", defaultValue = "system-admin") String actor,
            @RequestHeader(value = "x-tenant-uuid", required = false) String tenantHeader) {
        body.put("roleId", roleId.toString());
        return saveRole(body, actor, tenantHeader);
    }

    @PostMapping("/roles/delete/{roleId}")
    public ResponseEntity<?> deleteRole(@PathVariable UUID roleId, @RequestHeader(value = "x-user", defaultValue = "system-admin") String actor) {
        try {
            roleService.deleteRole(roleId, actor);
            return ResponseEntity.ok(Map.of("success", true));
        } catch (Exception e) {
            return ResponseEntity.status(500).body(Map.of("error", e.getMessage()));
        }
    }

    // =========================================================================
    // AUDITS — frontend-friendly mapping
    // =========================================================================
    @GetMapping("/audits")
    public List<Map<String, Object>> getAudits(
            @RequestHeader(value = "x-tenant-uuid", required = false) String tenantHeader) {
        List<AuditLog> logs = auditLogRepository.findAllByOrderByCreatedTimestampDesc();
        TenantMaster scope = resolveScope(tenantHeader);
        List<Map<String, Object>> result = new ArrayList<>();
        for (AuditLog l : logs) {
            if (scope != null && !scope.getUuid().equals(l.getTenantUuid())) continue;
            Map<String, Object> map = new HashMap<>();
            map.put("auditId",          l.getUuid());
            map.put("action",           l.getAction());
            map.put("status",           l.getAction());   // drives toggle-tab filtering
            map.put("entityType",       l.getModule());
            map.put("entityId",         l.getEntityUuid() != null ? l.getEntityUuid().toString() : "");
            map.put("performedBy",      l.getUsername());
            map.put("tenantName",       l.getEntityName());
            map.put("createdTimestamp", l.getCreatedTimestamp());

            List<AuditHistory> history = auditHistoryRepository.findAllByAuditLogUuid(l.getUuid());
            String desc = history.stream()
                    .map(h -> h.getFieldName() + ": " + h.getOldValue() + " → " + h.getNewValue())
                    .collect(Collectors.joining("; "));
            map.put("description", desc.isEmpty() ? l.getAction() + " on " + l.getModule() : desc);
            result.add(map);
        }
        return result;
    }

    // =========================================================================
    // SESSIONS — with computed status (Active / Inactive)
    // =========================================================================
    @GetMapping("/sessions")
    public List<Map<String, Object>> getSessions(
            @RequestHeader(value = "x-tenant-uuid", required = false) String tenantHeader) {
        List<SessionMaster> sessions = sessionMasterRepository.findAllByOrderByLoginTimestampDesc();
        TenantMaster scope = resolveScope(tenantHeader);
        List<Map<String, Object>> result = new ArrayList<>();
        for (SessionMaster s : sessions) {
            if (scope != null && !scope.getUuid().equals(s.getTenantUuid())) continue;
            Map<String, Object> map = new HashMap<>();
            map.put("sessionId",        s.getUuid());
            map.put("username",         s.getUsername());
            map.put("tenantName",       "");
            map.put("ipAddress",        s.getIpAddress());
            map.put("browser",          s.getBrowser());
            map.put("device",           s.getDevice());
            map.put("status",           s.getLogoutTimestamp() == null ? STATUS_ACTIVE : STATUS_INACTIVE);
            map.put("createdTimestamp", s.getLoginTimestamp());
            map.put("logoutTimestamp",  s.getLogoutTimestamp());
            result.add(map);
        }
        return result;
    }

    @PostMapping("/sessions/terminate/{sessionId}")
    public ResponseEntity<?> terminateSessionById(@PathVariable UUID sessionId, @RequestHeader(value = "x-user", defaultValue = "system-admin") String user) {
        try {
            SessionMaster s = sessionMasterRepository.findById(sessionId).orElseThrow();
            s.setLogoutTimestamp(LocalDateTime.now());
            s.setSessionDurationSeconds(600);
            sessionMasterRepository.save(s);
            auditService.logAuditEvent("FORCE_LOGOUT", "Session", user, "session_master", sessionId, "localhost");
            return ResponseEntity.ok(Map.of("success", true));
        } catch (Exception e) {
            return ResponseEntity.status(500).body(Map.of("error", e.getMessage()));
        }
    }

    @GetMapping("/active-tenants")
    public ResponseEntity<?> getActiveTenants(
            @RequestHeader(value = "x-tenant-uuid", required = false) String tenantHeader) {
        try {
            List<TenantMaster> tenants = tenantMasterRepository.findByStatus(STATUS_ACTIVE);
            TenantMaster scope = resolveScope(tenantHeader);
            List<Map<String, Object>> result = new ArrayList<>();
            for (TenantMaster t : tenants) {
                if (scope != null && !scope.getUuid().equals(t.getUuid())) continue;
                Map<String, Object> map = new HashMap<>();
                map.put("uuid", t.getUuid());
                map.put("label", t.getTenantName());
                map.put("value", t.getUuid());
                map.put("companyName", t.getCompanyName());
                result.add(map);
            }
            return ResponseEntity.ok(result);
        } catch (Exception e) {
            return ResponseEntity.status(500).body(Map.of("error", e.getMessage()));
        }
    }

    @GetMapping("/tenant-requesters/{tenantUuid}")
    public ResponseEntity<?> getTenantRequesters(@PathVariable UUID tenantUuid) {
        try {
            List<UserMaster> users = userMasterRepository.findByTenantUuid(tenantUuid);
            List<Map<String, Object>> result = new ArrayList<>();
            for (UserMaster u : users) {
                // "Requested By" must be an ACTIVE Requester of THIS tenant only.
                // Status case varies ("Active"/"ACTIVE"), so compare case-insensitively.
                boolean inactive = u.getStatus() != null && u.getStatus().equalsIgnoreCase(STATUS_INACTIVE);
                if (inactive) continue;
                if (!"REQUESTER".equalsIgnoreCase(u.getRole())) continue;
                Map<String, Object> map = new HashMap<>();
                map.put("uuid", u.getUuid());
                map.put("label", u.getUsername());
                map.put("value", u.getUsername());
                result.add(map);
            }
            return ResponseEntity.ok(result);
        } catch (Exception e) {
            return ResponseEntity.status(500).body(Map.of("error", e.getMessage()));
        }
    }
}
