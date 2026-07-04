package com.orque.opac.controller;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.orque.opac.entity.*;
import com.orque.opac.repository.*;
import com.orque.opac.service.*;
import org.springframework.beans.factory.annotation.Value;
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
    private static final String KEY_LICENSED_PRODUCTS = "licensedProducts";

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
    private final TenantLookupService tenantLookupService;
    private final LicenseFlagsCacheService licenseFlagsCacheService;

    // Single shared HttpClient reused across all outbound calls (workflow, CRM sync)
    // instead of allocating a new client (and its connection pool/threads) per request.
    private final java.net.http.HttpClient httpClient = java.net.http.HttpClient.newHttpClient();

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
                           ObjectMapper objectMapper,
                           TenantLookupService tenantLookupService,
                           LicenseFlagsCacheService licenseFlagsCacheService) {
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
        this.tenantLookupService = tenantLookupService;
        this.licenseFlagsCacheService = licenseFlagsCacheService;
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

            // Resolve tenant – required for all login modes
            if (tenantName == null || tenantName.isEmpty()) {
                return ResponseEntity.status(400).body(Map.of(
                    "success", false,
                    "message", "Tenant name is required"
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

            // The user must belong to this tenant
            if (user.getTenantUuid() != null && !user.getTenantUuid().equals(tenant.getUuid())) {
                return ResponseEntity.status(401).body(Map.of(
                    "success", false,
                    "message", "User does not belong to this tenant"
                ));
            }

            // Check login mode
            if ("business".equals(loginMode)) {
                // Business users (REQUESTER, APPROVER, VIEWER) can log in
                if ("SYSTEM_ADMIN".equals(user.getRole())) {
                    return ResponseEntity.status(403).body(Map.of(
                        "success", false,
                        "message", "System admins must use the System Admin login mode"
                    ));
                }

                Map<String, Object> response = new HashMap<>();
                response.put("success", true);
                response.put("message", "Login successful");
                Map<String, Object> data = new HashMap<>();
                data.put("uuid", user.getUuid().toString());
                data.put("username", user.getUsername());
                data.put("email", user.getEmail());
                data.put("role", user.getRole());
                data.put("tenantUuid", tenant.getUuid().toString());
                data.put("tenantName", tenant.getTenantName());
                data.put("isPlatformOwner", false);
                LicenseFlagsCacheService.LicenseFlags licenseFlags = licenseFlagsCacheService.resolveLicenseFlags(tenant.getUuid());
                data.put("hasActiveLicense", licenseFlags.hasActiveLicense());
                data.put("hasCrmLicense", licenseFlags.hasCrmLicense());
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
                boolean isOwner = isPlatformOwner(tenant);
                data.put("isPlatformOwner", isOwner);
                LicenseFlagsCacheService.LicenseFlags licenseFlags = isOwner
                    ? new LicenseFlagsCacheService.LicenseFlags(true, true)
                    : licenseFlagsCacheService.resolveLicenseFlags(tenant.getUuid());
                data.put("hasActiveLicense", licenseFlags.hasActiveLicense());
                data.put("hasCrmLicense", licenseFlags.hasCrmLicense());
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

    /**
     * Credential validation endpoint used by CRM (and future products) for direct login.
     * OPAC is the single source of truth for identity — products never store their own passwords.
     *
     * POST /api/auth/validate
     * Body: { username, password }
     * Returns: { valid, username, email, role, tenantUuid, tenantName, assignedProducts[], features[] }
     */
    @PostMapping("/auth/validate")
    @SuppressWarnings("unchecked")
    public ResponseEntity<?> validateCredentials(@RequestBody Map<String, Object> body) {
        try {
            String username = (String) body.get("username");
            String password = (String) body.get("password");
            if (username == null || username.isBlank() || password == null || password.isBlank()) {
                return ResponseEntity.status(401).body(Map.of("valid", false, "error", "Credentials required"));
            }

            Optional<UserMaster> userOpt = userMasterRepository.findByUsername(username);
            if (userOpt.isEmpty()) {
                return ResponseEntity.status(401).body(Map.of("valid", false, "error", "Invalid credentials"));
            }
            UserMaster user = userOpt.get();

            if (!"Active".equalsIgnoreCase(user.getStatus())) {
                return ResponseEntity.status(403).body(Map.of("valid", false, "error", "Account is disabled"));
            }

            try {
                if (!passwordService.verifyPassword(password, user.getPassword())) {
                    return ResponseEntity.status(401).body(Map.of("valid", false, "error", "Invalid credentials"));
                }
            } catch (Exception e) {
                return ResponseEntity.status(401).body(Map.of("valid", false, "error", "Invalid credentials"));
            }

            // Collect assigned products
            List<String> assignedProducts = new ArrayList<>();
            if (user.getAssignedProducts() != null && !user.getAssignedProducts().isBlank()) {
                for (String p : user.getAssignedProducts().split(",")) {
                    String t = p.trim();
                    if (!t.isEmpty()) assignedProducts.add(t);
                }
            }

            List<String> features = resolveUserCrmFeatures(username, user.getTenantUuid(), user.getRole());

            // Tenant info
            TenantMaster tenant = tenantMasterRepository.findById(user.getTenantUuid()).orElse(null);

            Map<String, Object> resp = new java.util.LinkedHashMap<>();
            resp.put("valid", true);
            resp.put("username", user.getUsername());
            resp.put("email", user.getEmail());
            resp.put("firstName", user.getFirstName() != null ? user.getFirstName() : "");
            resp.put("lastName", user.getLastName() != null ? user.getLastName() : "");
            resp.put("role", user.getRole());
            resp.put("tenantUuid", user.getTenantUuid() != null ? user.getTenantUuid().toString() : "");
            resp.put("tenantName", tenant != null ? tenant.getTenantName() : "");
            resp.put("assignedProducts", assignedProducts);
            resp.put("features", features);
            return ResponseEntity.ok(resp);
        } catch (Exception e) {
            return ResponseEntity.status(500).body(Map.of("valid", false, "error", e.getMessage()));
        }
    }

    private void callWorkflowService(String triggerEvent, String referenceId) {
        try {
            String url = workflowServiceUrl + "/api/workflow-process/start-workflow-process"
                       + "?triggerEvent=" + java.net.URLEncoder.encode(triggerEvent, java.nio.charset.StandardCharsets.UTF_8)
                       + "&referenceId=" + java.net.URLEncoder.encode(referenceId, java.nio.charset.StandardCharsets.UTF_8);
            java.net.http.HttpRequest req = java.net.http.HttpRequest.newBuilder()
                    .uri(java.net.URI.create(url))
                    .GET()
                    .build();
            java.net.http.HttpResponse<String> resp = httpClient.send(req, java.net.http.HttpResponse.BodyHandlers.ofString());
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

    /** Lightweight endpoint so the frontend can refresh the CRM license flag without re-login. */
    @GetMapping("/my-crm-license")
    public Map<String, Object> getMyCrmLicense(
            @RequestHeader(value = "x-tenant-uuid", required = false) String tenantHeader) {
        TenantMaster tenant = resolveScope(tenantHeader);
        boolean hasCrm;
        if (tenant == null) {
            // Platform owner always has access
            hasCrm = true;
        } else {
            hasCrm = licenseFlagsCacheService.resolveLicenseFlags(tenant.getUuid()).hasCrmLicense();
        }
        return Map.of("hasCrmLicense", hasCrm);
    }

    /**
     * True when THIS specific business user has applied their personal sub-license key.
     * Stored in settings.userActivations[username] — completely separate from the quota block.
     */
    @SuppressWarnings("unchecked")
    private boolean userHasAppliedLicense(UUID tenantUuid, String username) {
        try {
            TenantConfiguration cfg = tenantConfigurationRepository.findByTenantUuid(tenantUuid).orElse(null);
            if (cfg == null || cfg.getSettingsJson() == null || cfg.getSettingsJson().isBlank()) return false;
            Map<String, Object> settings = objectMapper.readValue(cfg.getSettingsJson(), new TypeReference<>() {});
            Object ua = settings.get("userActivations");
            if (!(ua instanceof Map)) return false;
            Object userEntry = ((Map<?, ?>) ua).get(username);
            return userEntry instanceof Map && !((Map<?, ?>) userEntry).isEmpty();
        } catch (Exception ignored) {
            return false;
        }
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
        // Primary path: UUID belongs to tenant_master directly (Redis-cached — this
        // lookup runs once per request across ~24 endpoints).
        TenantMaster tenant = tenantLookupService.findTenantById(tid);
        if (tenant == null) {
            // Fallback: UUID may be from tenant_request table (used by the frontend tenant picker).
            // Look up the corresponding tenant_master via the request's tenantName.
            tenant = tenantRequestRepository.findById(tid)
                    .map(req -> tenantMasterRepository.findByTenantName(req.getTenantName())
                            .orElse(null))
                    .orElse(null);
        }
        if (tenant == null || isPlatformOwner(tenant)) return null;
        return tenant;
    }

    /**
     * Returns a 403 ResponseEntity when the caller is a tenant-scoped user (non-ORQUE)
     * and the resource they are accessing belongs to a different tenant.
     * Returns null when the caller is ORQUE (no restriction) or when ownership matches.
     */
    private ResponseEntity<?> tenantOwnershipError(UUID resourceTenantUuid, String callerTenantHeader) {
        TenantMaster callerScope = resolveScope(callerTenantHeader);
        if (callerScope == null) return null; // ORQUE platform owner — unrestricted
        if (resourceTenantUuid == null || !callerScope.getUuid().equals(resourceTenantUuid)) {
            return ResponseEntity.status(403).body(Map.of("error", "Access denied: resource belongs to a different tenant"));
        }
        return null;
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
    @org.springframework.transaction.annotation.Transactional
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
    @org.springframework.transaction.annotation.Transactional
    public ResponseEntity<?> handleTenantApproval(@PathVariable String action, @PathVariable UUID uuid,
            @RequestBody Map<String, String> body,
            @RequestHeader(value = "x-user", defaultValue = "system-admin") String user,
            @RequestHeader(value = "x-tenant-uuid", required = false) String tenantHeader) {
        // Tenant onboarding workflow is a platform-owner (ORQUE) only function.
        if (resolveScope(tenantHeader) != null) {
            return ResponseEntity.status(403).body(Map.of("error", "Access denied: tenant lifecycle management requires ORQUE platform owner access"));
        }
        try {
            TenantRequest request = tenantRequestRepository.findById(uuid).orElseThrow();
            ApprovalRequest approval = approvalRequestRepository
                .findFirstByReferenceUuidOrderByCreatedTimestampDesc(uuid)
                .orElseGet(() -> {
                    ApprovalRequest ar = new ApprovalRequest();
                    ar.setReferenceUuid(uuid);
                    ar.setTriggerEvent("TENANT_ONBOARDING");
                    ar.setTenantId("ORQUE");
                    ar.setStatus("Pending");
                    return approvalRequestRepository.save(ar);
                });
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
                tenantLookupService.evict(savedMaster.getUuid());

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
                        "  URL:             " + opacFrontendUrl + "\n" +
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
                        "opacUrl",      opacFrontendUrl
                    ));

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
                // Return for Revision: requester can re-edit & re-submit
                request.setStatus(STATUS_RETURNED);
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
            @RequestHeader(value = "x-user", required = false) String userHeader) {
        List<LicenseRequest> requests;
        if (status != null && !status.isEmpty()) {
            requests = licenseRequestRepository.findAllByStatusOrderByCreatedTimestampDesc(status);
        } else {
            requests = licenseRequestRepository.findAllByOrderByCreatedTimestampDesc();
        }

        // Each user sees only the license requests they personally created.
        // requestedBy is always stamped from the x-user header on save, so this is reliable.
        if (userHeader != null && !userHeader.isBlank()) {
            final String caller = userHeader;
            requests = requests.stream()
                .filter(r -> caller.equals(r.getRequestedBy()))
                .collect(Collectors.toList());
        }

        // Batch-load all products and features up front (2 queries total) instead of
        // querying per license request / per product inside the loop below.
        List<UUID> requestUuids = requests.stream().map(LicenseRequest::getUuid).collect(Collectors.toList());
        List<LicenseProduct> allProducts = requestUuids.isEmpty()
            ? Collections.emptyList()
            : licenseProductRepository.findAllByLicenseRequestUuidIn(requestUuids);

        List<UUID> productUuids = allProducts.stream().map(LicenseProduct::getUuid).collect(Collectors.toList());
        List<LicenseFeature> allFeatures = productUuids.isEmpty()
            ? Collections.emptyList()
            : licenseFeatureRepository.findAllByLicenseProductUuidIn(productUuids);

        Map<UUID, List<LicenseProduct>> productsByRequest = allProducts.stream()
            .collect(Collectors.groupingBy(LicenseProduct::getLicenseRequestUuid));
        Map<UUID, List<LicenseFeature>> featuresByProduct = allFeatures.stream()
            .collect(Collectors.groupingBy(LicenseFeature::getLicenseProductUuid));

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

            List<LicenseProduct> products = productsByRequest.getOrDefault(req.getUuid(), Collections.emptyList());

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

                List<LicenseFeature> features = featuresByProduct.getOrDefault(p.getUuid(), Collections.emptyList());
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
    public ResponseEntity<?> saveLicense(@RequestBody Map<String, Object> body,
            @RequestHeader(value = "x-user", defaultValue = "system-admin") String user,
            @RequestHeader(value = "x-tenant-uuid", required = false) String callerTenantHeader) {
        try {
            String uuidStr = (String) body.get("uuid");
            // Use form body tenantUuid if provided, else fall back to caller's header
            String tenantUuidStr = (String) body.get("tenantUuid");
            if ((tenantUuidStr == null || tenantUuidStr.isBlank()) && callerTenantHeader != null && !callerTenantHeader.isBlank()) {
                tenantUuidStr = callerTenantHeader;
            }
            String companyName = (String) body.get(FIELD_COMPANY);
            String email = (String) body.get("email");
            String requestedBy = (String) body.get("requestedBy");

            // A tenant-scoped System Admin's form only knows its own tenant, so it doesn't
            // always send companyName/email explicitly — fall back to the tenant's own
            // record instead of letting a null companyName hit the DB's not-null constraint.
            TenantMaster callerScope = resolveScope(callerTenantHeader);
            if (callerScope != null) {
                if (companyName == null || companyName.isBlank()) {
                    companyName = callerScope.getCompanyName();
                }
                if (email == null || email.isBlank()) {
                    email = tenantRequestRepository.findByTenantName(callerScope.getTenantName())
                            .map(TenantRequest::getAdminEmail).orElse(null);
                }
            }
            if (companyName == null || companyName.isBlank()) {
                return ResponseEntity.badRequest().body(Map.of("error", "Company name is required."));
            }

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
                // Always stamp the actual logged-in user as the creator so the license
                // is visible only to them when they view the license list.
                req.setRequestedBy(user);
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

            // Insert products & features — build entities first, then persist in two
            // batched saveAll() calls instead of one save() per product/feature.
            if (details != null) {
                List<LicenseProduct> productsToSave = new ArrayList<>();
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
                    productsToSave.add(product);
                }
                List<LicenseProduct> savedProducts = licenseProductRepository.saveAll(productsToSave);

                List<LicenseFeature> featuresToSave = new ArrayList<>();
                for (int i = 0; i < savedProducts.size(); i++) {
                    LicenseProduct savedProd = savedProducts.get(i);
                    List<String> features = (List<String>) details.get(i).get("features");
                    if (features != null) {
                        for (String fName : features) {
                            LicenseFeature feat = new LicenseFeature();
                            feat.setLicenseProductUuid(savedProd.getUuid());
                            feat.setFeatureName(fName);
                            featuresToSave.add(feat);
                        }
                    }
                }
                if (!featuresToSave.isEmpty()) {
                    licenseFeatureRepository.saveAll(featuresToSave);
                }
            }

            auditService.logAuditEvent(uuidStr != null ? "UPDATE" : "INSERT", "License", user, "license_request", requestUuid, "localhost");
            return ResponseEntity.ok(Map.of("uuid", requestUuid, "success", true));
        } catch (Exception e) {
            return ResponseEntity.status(500).body(Map.of("error", e.getMessage()));
        }
    }

    @PostMapping("/licenses/submit/{uuid}")
    @org.springframework.transaction.annotation.Transactional
    public ResponseEntity<?> submitLicense(@PathVariable UUID uuid,
            @RequestHeader(value = "x-user", defaultValue = "system-admin") String user,
            @RequestHeader(value = "x-tenant-uuid", required = false) String tenantHeader) {
        try {
            LicenseRequest req = licenseRequestRepository.findById(uuid).orElseThrow();
            ResponseEntity<?> ownerErr = tenantOwnershipError(req.getTenantUuid(), tenantHeader);
            if (ownerErr != null) return ownerErr;
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
    public ResponseEntity<?> renewLicense(@PathVariable UUID uuid, @RequestBody Map<String, Object> body,
            @RequestHeader(value = "x-user", defaultValue = "system-admin") String user,
            @RequestHeader(value = "x-tenant-uuid", required = false) String tenantHeader) {
        try {
            LicenseRequest req = licenseRequestRepository.findById(uuid).orElseThrow();
            ResponseEntity<?> ownerErr = tenantOwnershipError(req.getTenantUuid(), tenantHeader);
            if (ownerErr != null) return ownerErr;
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

            // Insert new products and features — batched via saveAll() instead of
            // one save() per product/feature.
            List<Map<String, Object>> details = (List<Map<String, Object>>) body.get("licenseDetails");
            if (details != null) {
                List<LicenseProduct> productsToSave = new ArrayList<>();
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
                    productsToSave.add(product);
                }
                List<LicenseProduct> savedProducts = licenseProductRepository.saveAll(productsToSave);

                List<LicenseFeature> featuresToSave = new ArrayList<>();
                for (int i = 0; i < savedProducts.size(); i++) {
                    LicenseProduct savedProd = savedProducts.get(i);
                    List<String> features = (List<String>) details.get(i).get("features");
                    if (features != null) {
                        for (String fName : features) {
                            LicenseFeature feat = new LicenseFeature();
                            feat.setLicenseProductUuid(savedProd.getUuid());
                            feat.setFeatureName(fName);
                            featuresToSave.add(feat);
                        }
                    }
                }
                if (!featuresToSave.isEmpty()) {
                    licenseFeatureRepository.saveAll(featuresToSave);
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
    public ResponseEntity<?> upgradeLicense(@PathVariable UUID uuid, @RequestBody Map<String, Object> body,
            @RequestHeader(value = "x-user", defaultValue = "system-admin") String user,
            @RequestHeader(value = "x-tenant-uuid", required = false) String tenantHeader) {
        try {
            LicenseRequest req = licenseRequestRepository.findById(uuid).orElseThrow();
            ResponseEntity<?> ownerErr = tenantOwnershipError(req.getTenantUuid(), tenantHeader);
            if (ownerErr != null) return ownerErr;
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

            // Insert new products and features — batched via saveAll() instead of
            // one save() per product/feature.
            List<Map<String, Object>> details = (List<Map<String, Object>>) body.get("licenseDetails");
            if (details != null) {
                List<LicenseProduct> productsToSave = new ArrayList<>();
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
                    productsToSave.add(product);
                }
                List<LicenseProduct> savedProducts = licenseProductRepository.saveAll(productsToSave);

                List<LicenseFeature> featuresToSave = new ArrayList<>();
                for (int i = 0; i < savedProducts.size(); i++) {
                    LicenseProduct savedProd = savedProducts.get(i);
                    List<String> features = (List<String>) details.get(i).get("features");
                    if (features != null) {
                        for (String fName : features) {
                            LicenseFeature feat = new LicenseFeature();
                            feat.setLicenseProductUuid(savedProd.getUuid());
                            feat.setFeatureName(fName);
                            featuresToSave.add(feat);
                        }
                    }
                }
                if (!featuresToSave.isEmpty()) {
                    licenseFeatureRepository.saveAll(featuresToSave);
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
    @org.springframework.transaction.annotation.Transactional
    public ResponseEntity<?> handleLicenseApproval(@PathVariable String action, @PathVariable UUID uuid, @RequestBody Map<String, String> body,
            @RequestHeader(value = "x-user", defaultValue = "system-admin") String user,
            @RequestHeader(value = "x-tenant-uuid", required = false) String tenantHeader) {
        try {
            LicenseRequest req = licenseRequestRepository.findById(uuid).orElseThrow();
            ResponseEntity<?> ownerErr = tenantOwnershipError(req.getTenantUuid(), tenantHeader);
            if (ownerErr != null) return ownerErr;
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
                    pMap.put("startDate", p.getStartDate() != null ? p.getStartDate().toString() : "");
                    pMap.put("endDate", p.getEndDate() != null ? p.getEndDate().toString() : "");
                    pMap.put("userLimit", p.getUserLimit());
                    pMap.put("concurrentLimit", p.getConcurrentLimit());
                    pMap.put("gracePeriod", p.getGracePeriod());

                    List<LicenseFeature> features = licenseFeatureRepository.findAllByLicenseProductUuid(p.getUuid());
                    pMap.put("features", features.stream().map(LicenseFeature::getFeatureName).collect(Collectors.toList()));
                    productsList.add(pMap);

                    if (p.getEndDate() != null && p.getEndDate().isAfter(maxEnd)) maxEnd = p.getEndDate();
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
                }
                licenseProductRepository.saveAll(prods);

                // Consume the tenant's per-product user-seat quota for this issued license.
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

                ApprovalHistory rejectHistory = new ApprovalHistory();
                rejectHistory.setApprovalRequestUuid(approval.getUuid());
                rejectHistory.setAction("Reject");
                rejectHistory.setActorUsername(user);
                rejectHistory.setNotes(notes);
                approvalHistoryRepository.save(rejectHistory);

                auditService.logAuditEvent("REJECT", "License", user, "license_request", uuid, "localhost");
            } else if ("return".equals(action)) {
                // Return for Revision: requester can re-edit & re-submit
                req.setStatus(STATUS_RETURNED);
                licenseRequestRepository.save(req);

                approval.setStatus(STATUS_RETURNED);
                approvalRequestRepository.save(approval);

                ApprovalHistory returnHistory = new ApprovalHistory();
                returnHistory.setApprovalRequestUuid(approval.getUuid());
                returnHistory.setAction("Return");
                returnHistory.setActorUsername(user);
                returnHistory.setNotes(notes);
                approvalHistoryRepository.save(returnHistory);

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
    // ── SSO Token (short-lived HMAC token for CRM SSO) ───────────────────────

    @Value("${sso.shared-secret}")
    private String ssoSharedSecret;

    @Value("${crm.base-url:http://localhost:8085}")
    private String crmBaseUrl;

    @Value("${opac.frontend-url:http://localhost:8083}")
    private String opacFrontendUrl;

    @PostMapping("/sso/token")
    public ResponseEntity<?> generateSsoToken(
            @RequestHeader(value = "x-user", required = false) String username,
            @RequestHeader(value = "x-tenant-uuid", required = false) String tenantHeader) {
        try {
            if (username == null || username.isBlank()) {
                return ResponseEntity.status(401).body(Map.of("error", "Not authenticated"));
            }
            Optional<UserMaster> userOpt = userMasterRepository.findByUsername(username);
            if (userOpt.isEmpty()) {
                return ResponseEntity.status(404).body(Map.of("error", "User not found"));
            }
            UserMaster user = userOpt.get();
            long timestamp = System.currentTimeMillis();
            String payload = username + ":" + timestamp + ":" + user.getEmail();
            javax.crypto.Mac mac = javax.crypto.Mac.getInstance("HmacSHA256");
            mac.init(new javax.crypto.spec.SecretKeySpec(
                ssoSharedSecret.getBytes(java.nio.charset.StandardCharsets.UTF_8), "HmacSHA256"));
            byte[] hmacBytes = mac.doFinal(payload.getBytes(java.nio.charset.StandardCharsets.UTF_8));
            StringBuilder sb = new StringBuilder();
            for (byte b : hmacBytes) sb.append(String.format("%02x", b));
            String token = java.util.Base64.getUrlEncoder().withoutPadding()
                .encodeToString(payload.getBytes(java.nio.charset.StandardCharsets.UTF_8))
                + "." + sb;
            return ResponseEntity.ok(Map.of(
                "success", true,
                "token", token,
                "username", username,
                "email", user.getEmail()
            ));
        } catch (Exception e) {
            return ResponseEntity.status(500).body(Map.of("error", e.getMessage()));
        }
    }

    @PostMapping("/sso/validate")
    public ResponseEntity<?> validateSsoToken(@RequestBody Map<String, String> body) {
        try {
            String token = body.get("token");
            if (token == null || !token.contains(".")) {
                return ResponseEntity.status(401).body(Map.of("valid", false, "error", "Invalid token format"));
            }
            int dot = token.lastIndexOf('.');
            String encodedPayload = token.substring(0, dot);
            String receivedHmac = token.substring(dot + 1);
            String payload = new String(java.util.Base64.getUrlDecoder().decode(encodedPayload),
                java.nio.charset.StandardCharsets.UTF_8);
            String[] parts = payload.split(":");
            if (parts.length < 3) {
                return ResponseEntity.status(401).body(Map.of("valid", false, "error", "Malformed token"));
            }
            long timestamp = Long.parseLong(parts[1]);
            if (System.currentTimeMillis() - timestamp > 60_000) {
                return ResponseEntity.status(401).body(Map.of("valid", false, "error", "Token expired"));
            }
            javax.crypto.Mac mac = javax.crypto.Mac.getInstance("HmacSHA256");
            mac.init(new javax.crypto.spec.SecretKeySpec(
                ssoSharedSecret.getBytes(java.nio.charset.StandardCharsets.UTF_8), "HmacSHA256"));
            byte[] hmacBytes = mac.doFinal(payload.getBytes(java.nio.charset.StandardCharsets.UTF_8));
            StringBuilder sb = new StringBuilder();
            for (byte b : hmacBytes) sb.append(String.format("%02x", b));
            if (!sb.toString().equals(receivedHmac)) {
                return ResponseEntity.status(401).body(Map.of("valid", false, "error", "Invalid signature"));
            }
            String username = parts[0];
            String email = parts[2];
            Optional<UserMaster> userOpt = userMasterRepository.findByUsername(username);
            if (userOpt.isEmpty()) {
                return ResponseEntity.status(404).body(Map.of("valid", false, "error", "User not found"));
            }
            UserMaster user = userOpt.get();

            List<String> userFeatures = resolveUserCrmFeatures(username, user.getTenantUuid(), user.getRole());

            Map<String, Object> resp = new java.util.LinkedHashMap<>();
            resp.put("valid", true);
            resp.put("username", username);
            resp.put("email", email);
            resp.put("opacRole", user.getRole());
            resp.put("firstName", user.getFirstName() != null ? user.getFirstName() : username);
            resp.put("lastName", user.getLastName() != null ? user.getLastName() : "");
            resp.put("features", userFeatures);
            return ResponseEntity.ok(resp);
        } catch (Exception e) {
            return ResponseEntity.status(500).body(Map.of("valid", false, "error", e.getMessage()));
        }
    }

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
                Object lp = settings.get(KEY_LICENSED_PRODUCTS);
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

                // Quota unit = number of licenses. Each generated license counts as 1.
                int purchased = toInt(licProd.get("userLimit"));   // 0 when not tracked (legacy)
                int issued    = toInt(licProd.get("issued"));
                if (purchased > 0 && issued + 1 > purchased) {
                    return ResponseEntity.badRequest().body(Map.of("error",
                        name.toUpperCase() + ": " + (purchased - issued) + " of " + purchased
                        + " " + name.toUpperCase() + " licenses remaining — cannot issue another. "
                        + "Increase your " + name.toUpperCase() + " plan to add more licenses."));
                }
                licProd.put("issued", issued + 1);    // reserve one license

                Map<String, Object> p = new HashMap<>();
                p.put("productName", name.toUpperCase());
                p.put("startDate", LocalDate.now().toString());
                p.put("endDate", end.toString());
                p.put("userLimit", 1);                 // each individual license = 1 user
                p.put("gracePeriod", licProd.get("gracePeriod"));

                // Feature restriction: sub-license may only include features that exist in
                // the master license. Any requested feature not in the master is silently dropped.
                List<String> masterFeatures = licProd.get("features") instanceof List
                        ? (List<String>) licProd.get("features") : new ArrayList<>();
                List<String> requestedFeatures = rp.get("features") instanceof List
                        ? (List<String>) rp.get("features") : new ArrayList<>();
                List<String> allowedFeatures = requestedFeatures.isEmpty()
                        ? masterFeatures   // no specific request → grant all master features
                        : requestedFeatures.stream().filter(masterFeatures::contains).toList();
                if (allowedFeatures.isEmpty() && !masterFeatures.isEmpty()) {
                    // Caller requested features none of which are in master — block it
                    return ResponseEntity.badRequest().body(Map.of("error",
                        "None of the requested features for " + name.toUpperCase()
                        + " are available in your master license."));
                }
                p.put("features", allowedFeatures.isEmpty() ? masterFeatures : allowedFeatures);
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
                settings.put(KEY_LICENSED_PRODUCTS, licensed);
                config.setSettingsJson(objectMapper.writeValueAsString(settings));
                tenantConfigurationRepository.save(config);
                licenseFlagsCacheService.evict(config.getTenantUuid());
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
                Object lp = q.settings.get(KEY_LICENSED_PRODUCTS);
                if (lp instanceof Map) q.licensed = (Map<String, Object>) lp;
            }
        } catch (Exception ignored) { /* no quota */ }
        return q;
    }

    /**
     * Returns an error response if these products are not licensed, or if issuing them
     * would exceed the tenant's purchased user-seat quota; else null.
     * Quota model: ORQUE's license sets `userLimit` seats per product; each user license the
     * System Admin generates consumes seats. When seats run out, the plan must be increased.
     */
    @SuppressWarnings("unchecked")
    private ResponseEntity<?> checkQuota(QuotaContext q, List<LicenseProduct> prods) {
        if (q.licensed == null) return null;
        for (LicenseProduct p : prods) {
            String key = p.getProductName().toLowerCase();
            Map<String, Object> licProd = (Map<String, Object>) q.licensed.get(key);
            if (licProd == null) {
                return ResponseEntity.badRequest().body(Map.of("error",
                    p.getProductName().toUpperCase() + " is not included in your tenant's license. "
                    + "Contact Orque to add this product to your plan."));
            }
            // Quota unit = number of licenses. Each issued license counts as 1, regardless of users.
            int purchased = toInt(licProd.get("userLimit"));
            int issued = toInt(licProd.get("issued"));
            if (purchased > 0 && issued + 1 > purchased) {
                return ResponseEntity.badRequest().body(Map.of("error",
                    p.getProductName().toUpperCase() + ": " + (purchased - issued) + " of " + purchased
                    + " " + p.getProductName().toUpperCase() + " licenses remaining — cannot issue another. "
                    + "Increase your " + p.getProductName().toUpperCase() + " plan to add more licenses."));
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
            licProd.put("issued", issued + 1);   // one license consumed per product
        }
        try {
            q.settings.put(KEY_LICENSED_PRODUCTS, q.licensed);
            q.config.setSettingsJson(objectMapper.writeValueAsString(q.settings));
            tenantConfigurationRepository.save(q.config);
            licenseFlagsCacheService.evict(q.config.getTenantUuid());
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
                Object lp = settings.get(KEY_LICENSED_PRODUCTS);
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
                        m.put("activated", prod.get("activated") != null && (Boolean) prod.get("activated"));
                        m.put("activatedOn", prod.get("activatedOn"));
                        m.put("graceUntil", prod.get("graceUntil"));
                        result.add(m);
                    }
                }
            }
            return ResponseEntity.ok(result);
        } catch (Exception e) {
            return ResponseEntity.status(500).body(Map.of("error", e.getMessage()));
        }
    }

    /**
     * Returns the personal activated products for the calling business user.
     * Reads from settings.userActivations[username] — completely separate from
     * the System Admin's quota block (licensedProducts).
     */
    @GetMapping("/my-products")
    @SuppressWarnings("unchecked")
    public ResponseEntity<?> getMyProducts(
            @RequestHeader(value = "x-user", required = false) String username,
            @RequestHeader(value = "x-tenant-uuid", required = false) String tenantHeader) {
        try {
            TenantMaster scope = resolveScope(tenantHeader);
            List<Map<String, Object>> result = new ArrayList<>();
            if (scope == null || username == null || username.isBlank()) return ResponseEntity.ok(result);

            TenantConfiguration config = tenantConfigurationRepository.findByTenantUuid(scope.getUuid()).orElse(null);
            if (config == null || config.getSettingsJson() == null || config.getSettingsJson().isBlank())
                return ResponseEntity.ok(result);

            Map<String, Object> settings = objectMapper.readValue(config.getSettingsJson(),
                    new TypeReference<Map<String, Object>>() {});
            Object ua = settings.get("userActivations");
            if (!(ua instanceof Map)) return ResponseEntity.ok(result);

            Object userEntry = ((Map<?, ?>) ua).get(username);
            if (!(userEntry instanceof Map)) return ResponseEntity.ok(result);

            Map<String, Object> activations = (Map<String, Object>) userEntry;
            for (Map.Entry<String, Object> e : activations.entrySet()) {
                Map<String, Object> act = (Map<String, Object>) e.getValue();
                Map<String, Object> m = new HashMap<>();
                m.put("productName", e.getKey().toUpperCase());
                m.put("activatedOn", act.get("activatedOn"));
                m.put("expiry",      act.get("expiry"));
                m.put("gracePeriod", act.get("gracePeriod"));
                m.put("graceUntil",  act.get("graceUntil"));
                m.put("features",    act.get("features"));
                result.add(m);
            }
            return ResponseEntity.ok(result);
        } catch (Exception e) {
            return ResponseEntity.status(500).body(Map.of("error", e.getMessage()));
        }
    }

    @PostMapping("/licenses/apply")
    public ResponseEntity<?> applyLicense(@RequestBody Map<String, String> body,
            @RequestHeader(value = "x-user", defaultValue = "system-admin") String user,
            @RequestHeader(value = "x-role", defaultValue = "") String callerRole,
            @RequestHeader(value = "x-tenant-uuid", required = false) String tenantHeader) {
        try {
            String licenseKey = body.get("licenseKey");
            if (licenseKey == null || licenseKey.isBlank()) {
                return ResponseEntity.badRequest().body(Map.of("error", "License key is required."));
            }
            licenseKey = licenseKey.trim();
            String tenantUuidStr = body.get("tenantUuid");
            if (tenantUuidStr == null || tenantUuidStr.isBlank()) {
                return ResponseEntity.badRequest().body(Map.of("error", "Tenant UUID is required."));
            }
            UUID tenantUuid = UUID.fromString(tenantUuidStr);

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

            // Merge into the EXISTING tenant settings — never blow away the quota counters.
            TenantConfiguration config = tenantConfigurationRepository.findByTenantUuid(tenantUuid).orElseThrow();
            Map<String, Object> settingsMap = (config.getSettingsJson() != null && !config.getSettingsJson().isBlank())
                ? objectMapper.readValue(config.getSettingsJson(), new TypeReference<Map<String, Object>>() {})
                : new HashMap<>();

            // Route by WHO is applying, not by key type — the approval workflow always
            // produces "Standard" keys, so licenseType is unreliable as a discriminator.
            // System Admins apply the master quota license; everyone else activates personally.
            boolean isBusinessUserApply = !"SYSTEM_ADMIN".equalsIgnoreCase(callerRole);

            if (isBusinessUserApply) {
                // Per-user activation map: settings.userActivations.<username>.<productKey>
                Map<String, Object> userActivations = settingsMap.get("userActivations") instanceof Map
                    ? (Map<String, Object>) settingsMap.get("userActivations") : new HashMap<>();
                Map<String, Object> userEntry = userActivations.get(user) instanceof Map
                    ? (Map<String, Object>) userActivations.get(user) : new HashMap<>();

                for (Map<String, Object> p : products) {
                    String key = ((String) p.get("productName")).toLowerCase();
                    int grace = p.get("gracePeriod") != null ? ((Number) p.get("gracePeriod")).intValue() : 0;
                    LocalDate end = LocalDate.parse((String) p.get("endDate"));
                    Map<String, Object> activation = new HashMap<>();
                    activation.put("activatedOn", LocalDate.now().toString());
                    activation.put("expiry", p.get("endDate"));
                    activation.put("gracePeriod", grace);
                    activation.put("graceUntil", end.plusDays(grace).toString());
                    activation.put("features", p.get("features"));
                    userEntry.put(key, activation);
                }
                userActivations.put(user, userEntry);
                settingsMap.put("userActivations", userActivations);
            } else {
                // Master license: (re)establish this product's quota; preserve grace/concurrency.
                Map<String, Object> licensedProds = settingsMap.get(KEY_LICENSED_PRODUCTS) instanceof Map
                    ? (Map<String, Object>) settingsMap.get(KEY_LICENSED_PRODUCTS) : new HashMap<>();
                for (Map<String, Object> p : products) {
                    String key = ((String) p.get("productName")).toLowerCase();
                    Map<String, Object> prodInfo = new HashMap<>();
                    prodInfo.put("enabled", true);
                    prodInfo.put("expiry", p.get("endDate"));
                    prodInfo.put("userLimit", p.get("userLimit"));
                    prodInfo.put("issued", 0);
                    prodInfo.put("gracePeriod", p.get("gracePeriod"));
                    prodInfo.put("concurrentLimit", p.get("concurrentLimit"));
                    prodInfo.put("features", p.get("features"));
                    licensedProds.put(key, prodInfo);
                }
                settingsMap.put(KEY_LICENSED_PRODUCTS, licensedProds);
            }

            config.setSettingsJson(objectMapper.writeValueAsString(settingsMap));
            tenantConfigurationRepository.save(config);
            licenseFlagsCacheService.evict(tenantUuid);

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
    public ResponseEntity<?> createSession(@RequestBody SessionMaster session,
            @RequestHeader(value = "X-Forwarded-For", required = false) String forwardedFor,
            jakarta.servlet.http.HttpServletRequest request) {
        // Never trust a client-supplied IP for an audit/security record — derive it
        // server-side. nginx sets X-Forwarded-For to the real client IP; fall back to
        // the direct socket address only when there's no proxy in front of us.
        String clientIp = (forwardedFor != null && !forwardedFor.isBlank())
                ? forwardedFor.split(",")[0].trim()
                : request.getRemoteAddr();
        session.setIpAddress(clientIp);
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
        TenantMaster scope = resolveScope(tenantHeader);
        List<AuditLog> logs = (scope != null)
                ? auditLogRepository.findByTenantUuidOrderByCreatedTimestampDesc(scope.getUuid())
                : auditLogRepository.findAllByOrderByCreatedTimestampDesc();
        List<Map<String, Object>> result = new ArrayList<>();
        for (AuditLog l : logs) {
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

    /**
     * Returns products (and their allowed features) from the tenant's master license.
     * Used by the sub-license generation form to restrict the product/feature picker
     * to only what the master license allows.
     */
    @GetMapping("/master-license-products")
    @SuppressWarnings("unchecked")
    public ResponseEntity<?> getMasterLicenseProducts(
            @RequestHeader(value = "x-tenant-uuid", required = false) String tenantHeader) {
        TenantMaster scope = resolveScope(tenantHeader);
        if (scope == null) {
            return ResponseEntity.badRequest().body(Map.of("error", "Tenant context required."));
        }
        TenantConfiguration config = tenantConfigurationRepository.findByTenantUuid(scope.getUuid()).orElse(null);
        if (config == null || config.getSettingsJson() == null || config.getSettingsJson().isBlank()) {
            return ResponseEntity.ok(List.of());
        }
        try {
            Map<String, Object> settings = objectMapper.readValue(config.getSettingsJson(),
                    new TypeReference<Map<String, Object>>() {});
            Object lp = settings.get(KEY_LICENSED_PRODUCTS);
            if (!(lp instanceof Map)) return ResponseEntity.ok(List.of());

            Map<String, Object> licensed = (Map<String, Object>) lp;
            List<Map<String, Object>> products = new ArrayList<>();
            for (Map.Entry<String, Object> entry : licensed.entrySet()) {
                if (!(entry.getValue() instanceof Map)) continue;
                Map<String, Object> pd = (Map<String, Object>) entry.getValue();
                Boolean enabled = pd.get("enabled") instanceof Boolean b ? b : true;
                if (!enabled) continue;
                Map<String, Object> out = new HashMap<>();
                out.put("productName", entry.getKey().toUpperCase());
                out.put("features",   pd.getOrDefault("features", List.of()));
                out.put("expiry",     pd.get("expiry"));
                out.put("userLimit",  pd.getOrDefault("userLimit", 0));
                out.put("issued",     pd.getOrDefault("issued", 0));
                out.put("remaining",  toInt(pd.get("userLimit")) - toInt(pd.get("issued")));
                products.add(out);
            }
            return ResponseEntity.ok(products);
        } catch (Exception e) {
            return ResponseEntity.status(500).body(Map.of("error", "Failed to read master license: " + e.getMessage()));
        }
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
                Object lp = settings.get(KEY_LICENSED_PRODUCTS);
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
    public List<EmailQueue> getEmailQueue(
            @RequestHeader(value = "x-tenant-uuid", required = false) String tenantHeader) {
        TenantMaster scope = resolveScope(tenantHeader);
        if (scope != null) {
            return emailQueueRepository.findByTenantUuidOrderByCreatedTimestampDesc(scope.getUuid());
        }
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
                    return userWelcomeEmail(u, temp);
                }).orElse(base);

            } else if ("tenant".equalsIgnoreCase(type)) {
                return tenantRequestRepository.findById(uuid).map(req -> {
                    userMasterRepository.findByUsername(req.getAdminUsername())
                            .ifPresent(admin -> resetTempPassword(admin, temp));
                    return EmailTemplateBuilder.wrap("Your tenant is now active",
                        "<p style=\"margin:0 0 16px;\">Dear Team,</p>"
                        + "<p style=\"margin:0 0 16px;\">We are pleased to inform you that your tenant "
                        + "<strong>" + EmailTemplateBuilder.esc(nz(req.getCompanyName())) + "</strong> has been "
                        + "successfully activated on the OPAC platform. Your System Administrator account has been "
                        + "created and is ready for use.</p>"
                        + EmailTemplateBuilder.credentialsBox(
                              EmailTemplateBuilder.credentialRow("Login Mode", "System Admin")
                            + EmailTemplateBuilder.credentialRow("Tenant", nz(req.getTenantName()))
                            + EmailTemplateBuilder.credentialRow("Username", nz(req.getAdminUsername()))
                            + EmailTemplateBuilder.credentialRow("Password", temp))
                        + "<p style=\"margin:16px 0 8px;font-weight:600;\">Getting Started</p>"
                        + "<ol style=\"margin:0 0 16px;padding-left:20px;\">"
                        + "<li>Open the OPAC portal.</li>"
                        + "<li>Select the System Admin login tab.</li>"
                        + "<li>Enter the Tenant, Username, and Password provided above.</li>"
                        + "<li>Navigate to System Settings to create users and assign roles.</li>"
                        + "<li>Open Tenant Configuration and apply your OPAC license key to activate products and features.</li>"
                        + "<li>Review tenant settings and complete any required configuration.</li>"
                        + "<li>Change your password after your first successful login.</li>"
                        + "</ol>"
                        + "<p style=\"margin:0;color:#6b7280;font-size:13px;\">Please keep these credentials confidential "
                        + "and share them only with authorized personnel. If you require assistance with tenant setup, "
                        + "user onboarding, or license activation, please contact the OPAC support team.</p>");
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

    /** Shared new-user welcome email (used by automatic send-on-create and the Share dialog). */
    /**
     * A user's CRM feature access: their tenant's per-user activation grants if any exist,
     * else — for SYSTEM_ADMIN, or any user with no per-user activation on file — the full
     * set of features on the tenant's master CRM license (what was picked at license-issue
     * time, e.g. "select all"). Per-user activation is an opt-in narrowing for individual
     * business users; it was never meant to be the only way to get any access at all.
     */
    @SuppressWarnings("unchecked")
    private List<String> resolveUserCrmFeatures(String username, UUID tenantUuid, String role) {
        List<String> features = new ArrayList<>();
        try {
            TenantConfiguration cfg = tenantConfigurationRepository.findByTenantUuid(tenantUuid).orElse(null);
            if (cfg == null || cfg.getSettingsJson() == null || cfg.getSettingsJson().isBlank()) {
                return features;
            }
            Map<String, Object> settings = objectMapper.readValue(cfg.getSettingsJson(),
                new TypeReference<Map<String, Object>>() {});

            if (!"SYSTEM_ADMIN".equals(role)) {
                Object ua = settings.get("userActivations");
                if (ua instanceof Map) {
                    Object userEntry = ((Map<String, Object>) ua).get(username);
                    if (userEntry instanceof Map) {
                        for (Object act : ((Map<String, Object>) userEntry).values()) {
                            if (act instanceof Map) {
                                Object feats = ((Map<String, Object>) act).get("features");
                                if (feats instanceof List) {
                                    for (Object f : (List<?>) feats) {
                                        if (f instanceof String s && !features.contains(s)) {
                                            features.add(s);
                                        }
                                    }
                                }
                            }
                        }
                    }
                }
            }

            if (features.isEmpty()) {
                Object lp = settings.get(KEY_LICENSED_PRODUCTS);
                if (lp instanceof Map) {
                    Object crmProd = ((Map<String, Object>) lp).get("crm");
                    if (crmProd instanceof Map) {
                        Object feats = ((Map<String, Object>) crmProd).get("features");
                        if (feats instanceof List) {
                            for (Object f : (List<?>) feats) {
                                if (f instanceof String s && !features.contains(s)) {
                                    features.add(s);
                                }
                            }
                        }
                    }
                }
            }
        } catch (Exception ignored) { /* features stay empty */ }
        return features;
    }

    private String userWelcomeEmail(UserMaster u, String tempPassword) {
        String first = (u.getFirstName() != null && !u.getFirstName().isBlank())
                ? u.getFirstName() : nz(u.getUsername());
        return EmailTemplateBuilder.wrap("Your OPAC account is ready",
            "<p style=\"margin:0 0 16px;\">Dear " + EmailTemplateBuilder.esc(first) + ",</p>"
            + "<p style=\"margin:0 0 16px;\">A user account has been created for you on the OPAC platform. "
            + "Your login credentials are provided below.</p>"
            + EmailTemplateBuilder.credentialsBox(
                  EmailTemplateBuilder.credentialRow("Login Mode", "Business User")
                + EmailTemplateBuilder.credentialRow("Tenant", nz(u.getTenantName()))
                + EmailTemplateBuilder.credentialRow("Username", nz(u.getUsername()))
                + EmailTemplateBuilder.credentialRow("Password", tempPassword)
                + EmailTemplateBuilder.credentialRow("Role", nz(u.getRole())))
            + "<p style=\"margin:16px 0 8px;font-weight:600;\">Getting Started</p>"
            + "<ol style=\"margin:0 0 16px;padding-left:20px;\">"
            + "<li>Open the OPAC portal.</li>"
            + "<li>Select the Business User login tab.</li>"
            + "<li>Enter the Tenant, Username, and Password provided above.</li>"
            + "<li>Click Login.</li>"
            + "<li>Change your password after your first successful login.</li>"
            + "</ol>"
            + "<p style=\"margin:0;color:#6b7280;font-size:13px;\">For security reasons, please do not share "
            + "your login credentials with anyone. If you experience any issues accessing your account, "
            + "contact your system administrator.</p>");
    }

    /** Shared license-activation email body (used by Share and sub-license generation). */
    private String licenseEmail(String licenseKey) {
        return EmailTemplateBuilder.wrap("Your OPAC license key",
            "<p style=\"margin:0 0 16px;\">Dear Team,</p>"
            + "<p style=\"margin:0 0 16px;\">Your OPAC license has been successfully activated. To complete the "
            + "setup, please log in to the OPAC application and navigate to <strong>Tenant Configuration &rarr; "
            + "Add License</strong>, then paste the license key below.</p>"
            + "<p style=\"margin:0 0 8px;font-weight:600;\">OPAC License Key</p>"
            + "<div style=\"background:#f9fafb;border:1px solid #e5e7eb;border-radius:6px;padding:14px 16px;"
            + "font-family:'Courier New',monospace;font-size:13px;color:#111827;word-break:break-all;margin:0 0 16px;\">"
            + EmailTemplateBuilder.esc(nz(licenseKey)) + "</div>"
            + "<p style=\"margin:0;color:#6b7280;font-size:13px;\">If you encounter any issues during activation, "
            + "please contact the system administrator or support team.</p>");
    }

    // =========================================================================
    // USERS MANAGEMENT APIs
    // =========================================================================
    @GetMapping("/users")
    public List<Map<String, Object>> getUsers(
            @RequestHeader(value = "x-tenant-uuid", required = false) String tenantHeader) {
        TenantMaster scope = resolveScope(tenantHeader);
        List<UserMaster> users = (scope != null)
                ? userService.getTenantUsers(scope.getUuid())
                : userService.getAllUsers();
        List<Map<String, Object>> result = new ArrayList<>();
        for (UserMaster u : users) {
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
            map.put("assignedProducts", u.getAssignedProducts() != null ? u.getAssignedProducts() : "");
            map.put("createdTimestamp", u.getCreatedTimestamp());
            result.add(map);
        }
        return result;
    }

    /** Fire-and-forget: provision this OPAC user in CRM so they can log in via SSO immediately. */
    private void syncUserToCrm(UserMaster u, String plainPassword) {
        try {
            String crmRole = "SYSTEM_ADMIN".equals(u.getRole()) ? "SYSTEM_ADMIN" : "SALES_USER";
            String lastName = u.getLastName() != null && !u.getLastName().isBlank() ? u.getLastName() : "-";
            // Tenant name lets CRM resolve/create the matching Organization for this user
            // (blank/ORQUE = platform owner, no org) — without it every synced user lands
            // orphaned with no organizationId, same bug fixed for direct/SSO CRM login.
            String tenantName = u.getTenantUuid() != null
                ? tenantMasterRepository.findById(u.getTenantUuid()).map(TenantMaster::getTenantName).orElse("")
                : "";
            String body = String.format(
                "{\"firstName\":\"%s\",\"lastName\":\"%s\",\"username\":\"%s\",\"email\":\"%s\",\"phone\":\"%s\",\"password\":\"%s\",\"role\":\"%s\",\"tenantName\":\"%s\",\"status\":\"ACTIVE\"}",
                escJson(u.getFirstName()), escJson(lastName), escJson(u.getUsername()),
                escJson(u.getEmail() != null ? u.getEmail() : ""),
                escJson(u.getContactNumber() != null ? u.getContactNumber() : ""),
                escJson(plainPassword), crmRole, escJson(tenantName));
            java.net.http.HttpRequest req = java.net.http.HttpRequest.newBuilder()
                .uri(java.net.URI.create(crmBaseUrl + "/api/v1/auth/sync-user"))
                .header("Content-Type", "application/json")
                .header("X-Internal-Sync", "true")
                .POST(java.net.http.HttpRequest.BodyPublishers.ofString(body))
                .build();
            httpClient.sendAsync(req, java.net.http.HttpResponse.BodyHandlers.ofString())
                .whenComplete((r, ex) -> {
                    if (ex != null) {
                        System.err.println("[CRM sync] Failed: " + ex.getMessage());
                    } else {
                        System.out.println("[CRM sync] " + u.getUsername() + " → " + r.statusCode());
                    }
                });
        } catch (Exception e) {
            System.err.println("[CRM sync] Error building request: " + e.getMessage());
        }
    }

    private static String escJson(String s) {
        return s == null ? "" : s.replace("\\", "\\\\").replace("\"", "\\\"");
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
            if (body.containsKey("assignedProducts")) {
                user.setAssignedProducts((String) body.get("assignedProducts"));
            }

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

            // Sync new users to CRM so they can SSO in immediately.
            // Business users → SALES_USER; SYSTEM_ADMIN → SYSTEM_ADMIN.
            if (isNew && tempPassword != null) {
                syncUserToCrm(saved, tempPassword);
            }

            // Automatically email the new user their login credentials — previously this
            // only happened if an admin opened the manual Share dialog afterwards.
            if (isNew && tempPassword != null && saved.getEmail() != null && !saved.getEmail().isBlank()) {
                try {
                    emailService.sendEmail(saved.getEmail(), null,
                        "Your OPAC Account is Ready", userWelcomeEmail(saved, tempPassword));
                } catch (Exception ignored) { /* email best-effort; credentials are still returned in the response */ }
            }

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
    public ResponseEntity<?> deactivateUser(@PathVariable UUID userId,
            @RequestHeader(value = "x-user", defaultValue = "system-admin") String actor,
            @RequestHeader(value = "x-tenant-uuid", required = false) String tenantHeader) {
        try {
            UserMaster target = userMasterRepository.findById(userId).orElse(null);
            if (target == null) return ResponseEntity.notFound().build();
            ResponseEntity<?> ownerErr = tenantOwnershipError(target.getTenantUuid(), tenantHeader);
            if (ownerErr != null) return ownerErr;
            userService.deactivateUser(userId, actor);
            return ResponseEntity.ok(Map.of("success", true));
        } catch (Exception e) {
            return ResponseEntity.status(500).body(Map.of("error", e.getMessage()));
        }
    }

    @PostMapping("/users/activate/{userId}")
    public ResponseEntity<?> activateUser(@PathVariable UUID userId,
            @RequestHeader(value = "x-user", defaultValue = "system-admin") String actor,
            @RequestHeader(value = "x-tenant-uuid", required = false) String tenantHeader) {
        try {
            UserMaster target = userMasterRepository.findById(userId).orElse(null);
            if (target == null) return ResponseEntity.notFound().build();
            ResponseEntity<?> ownerErr = tenantOwnershipError(target.getTenantUuid(), tenantHeader);
            if (ownerErr != null) return ownerErr;
            userService.activateUser(userId, actor);
            return ResponseEntity.ok(Map.of("success", true));
        } catch (Exception e) {
            return ResponseEntity.status(500).body(Map.of("error", e.getMessage()));
        }
    }

    // =========================================================================
    // PRODUCT ASSIGNMENT APIs
    // GET  /api/users/{uuid}/products  — return user's assigned products
    // PUT  /api/users/{uuid}/products  — update user's assigned products
    // =========================================================================

    @GetMapping("/users/{userId}/products")
    public ResponseEntity<?> getUserProducts(@PathVariable UUID userId) {
        try {
            UserMaster user = userMasterRepository.findById(userId).orElse(null);
            if (user == null) return ResponseEntity.notFound().build();
            List<String> products = new ArrayList<>();
            if (user.getAssignedProducts() != null && !user.getAssignedProducts().isBlank()) {
                for (String p : user.getAssignedProducts().split(",")) {
                    String t = p.trim();
                    if (!t.isEmpty()) products.add(t);
                }
            }
            return ResponseEntity.ok(Map.of("userId", userId, "assignedProducts", products));
        } catch (Exception e) {
            return ResponseEntity.status(500).body(Map.of("error", e.getMessage()));
        }
    }

    @PutMapping("/users/{userId}/products")
    public ResponseEntity<?> updateUserProducts(@PathVariable UUID userId,
            @RequestBody Map<String, Object> body,
            @RequestHeader(value = "x-user", defaultValue = "system-admin") String actor,
            @RequestHeader(value = "x-tenant-uuid", required = false) String tenantHeader) {
        try {
            UserMaster user = userMasterRepository.findById(userId).orElse(null);
            if (user == null) return ResponseEntity.notFound().build();
            ResponseEntity<?> ownerErr = tenantOwnershipError(user.getTenantUuid(), tenantHeader);
            if (ownerErr != null) return ownerErr;

            List<String> products = body.get("assignedProducts") instanceof List
                    ? (List<String>) body.get("assignedProducts") : new ArrayList<>();
            user.setAssignedProducts(String.join(",", products));
            user.setUpdatedTimestamp(java.time.LocalDateTime.now());
            userMasterRepository.save(user);

            auditService.logAuditEvent("UPDATE_USER_PRODUCTS", "User", actor,
                    "user_master", userId, "localhost");
            return ResponseEntity.ok(Map.of("success", true, "assignedProducts", products));
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
    public ResponseEntity<?> terminateSessionById(@PathVariable UUID sessionId,
            @RequestHeader(value = "x-user", defaultValue = "system-admin") String user,
            @RequestHeader(value = "x-tenant-uuid", required = false) String tenantHeader) {
        try {
            SessionMaster s = sessionMasterRepository.findById(sessionId).orElseThrow();
            ResponseEntity<?> ownerErr = tenantOwnershipError(s.getTenantUuid(), tenantHeader);
            if (ownerErr != null) return ownerErr;
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
    public ResponseEntity<?> getTenantRequesters(@PathVariable UUID tenantUuid,
            @RequestHeader(value = "x-tenant-uuid", required = false) String tenantHeader) {
        // Tenant-scoped callers may only request their own tenant's requesters.
        ResponseEntity<?> ownerErr = tenantOwnershipError(tenantUuid, tenantHeader);
        if (ownerErr != null) return ownerErr;
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

    /**
     * Internal API used by CRM to check whether a tenant has an active CRM product
     * in their master license. Called with the org code (= OPAC tenant name, uppercase).
     * No auth required — called service-to-service on the internal network only.
     */
    @GetMapping("/internal/crm-license/{orgCode}")
    @SuppressWarnings("unchecked")
    public ResponseEntity<?> getCrmLicenseForOrg(@PathVariable String orgCode) {
        try {
            TenantMaster tenant = tenantMasterRepository
                    .findByTenantName(orgCode)
                    .or(() -> tenantMasterRepository.findAll().stream()
                            .filter(t -> t.getTenantName().equalsIgnoreCase(orgCode))
                            .findFirst())
                    .orElse(null);
            if (tenant == null) {
                return ResponseEntity.ok(Map.of("active", false, "reason", "Tenant not found"));
            }
            TenantConfiguration cfg = tenantConfigurationRepository.findByTenantUuid(tenant.getUuid()).orElse(null);
            if (cfg == null || cfg.getSettingsJson() == null || cfg.getSettingsJson().isBlank()) {
                return ResponseEntity.ok(Map.of("active", false, "reason", "No license configuration"));
            }
            Map<String, Object> settings = objectMapper.readValue(cfg.getSettingsJson(), new TypeReference<>() {});
            Object lp = settings.get(KEY_LICENSED_PRODUCTS);
            if (!(lp instanceof Map)) {
                return ResponseEntity.ok(Map.of("active", false, "reason", "No licensed products"));
            }
            Map<String, Object> products = (Map<String, Object>) lp;
            Object crmProd = products.get("crm");
            if (crmProd == null) crmProd = products.get("CRM");
            if (!(crmProd instanceof Map)) {
                return ResponseEntity.ok(Map.of("active", false, "reason", "CRM not in license"));
            }
            Map<String, Object> crm = (Map<String, Object>) crmProd;
            boolean enabled = Boolean.TRUE.equals(crm.get("enabled"));
            String expiry = crm.getOrDefault("expiry", "").toString();
            Object featuresObj = crm.get("features");
            java.util.List<String> features = featuresObj instanceof java.util.List
                    ? (java.util.List<String>) featuresObj : new java.util.ArrayList<>();
            Object userLimit = crm.getOrDefault("userLimit", 0);
            Object concurrentLimit = crm.getOrDefault("concurrentLimit", 0);
            Object gracePeriod = crm.getOrDefault("gracePeriod", 30);

            // Check expiry
            boolean active = enabled;
            boolean inGrace = false;
            int graceRemaining = 0;
            int daysRemaining = 0;
            if (!expiry.isBlank()) {
                try {
                    java.time.LocalDate expiryDate = java.time.LocalDate.parse(expiry);
                    java.time.LocalDate today = java.time.LocalDate.now();
                    int grace = gracePeriod instanceof Number ? ((Number) gracePeriod).intValue() : 30;
                    daysRemaining = (int) java.time.temporal.ChronoUnit.DAYS.between(today, expiryDate);
                    if (today.isAfter(expiryDate)) {
                        long daysOver = java.time.temporal.ChronoUnit.DAYS.between(expiryDate, today);
                        if (daysOver <= grace) {
                            inGrace = true;
                            graceRemaining = (int)(grace - daysOver);
                        } else {
                            active = false;
                        }
                    }
                } catch (Exception ignored) { }
            }

            return ResponseEntity.ok(Map.of(
                    "active", active,
                    "inGrace", inGrace,
                    "expiry", expiry,
                    "daysRemaining", daysRemaining,
                    "graceRemaining", graceRemaining,
                    "features", features,
                    "userLimit", userLimit,
                    "concurrentLimit", concurrentLimit,
                    "gracePeriod", gracePeriod
            ));
        } catch (Exception e) {
            return ResponseEntity.status(500).body(Map.of("error", e.getMessage()));
        }
    }
}
