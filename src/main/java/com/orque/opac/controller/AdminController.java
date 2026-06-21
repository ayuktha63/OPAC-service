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
    // TENANT MODULE CONTROLLER
    // =========================================================================
    @GetMapping("/tenants")
    public List<TenantRequest> getTenants(@RequestParam(required = false) String status) {
        if (status != null && !status.isEmpty()) {
            return tenantRequestRepository.findAllByStatusOrderByCreatedTimestampDesc(status);
        }
        return tenantRequestRepository.findAllByOrderByCreatedTimestampDesc();
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
            ApprovalRequest approval = approvalRequestRepository.findByReferenceUuid(uuid).orElseThrow();
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

                // Default admin user (check if already exists)
                if (userMasterRepository.findByUsernameAndTenantUuid(request.getAdminUsername(), savedMaster.getUuid()).isEmpty()) {
                    UserMaster admin = new UserMaster();
                    admin.setTenantUuid(savedMaster.getUuid());
                    admin.setUsername(request.getAdminUsername());
                    admin.setEmail(request.getAdminEmail());
                    admin.setStatus(STATUS_ACTIVE);
                    userMasterRepository.save(admin);
                }

                // Send welcome email via template
                String tempPass = UUID.randomUUID().toString().substring(0, 8);
                emailService.sendFromTemplate("tenant_approved", request.getAdminEmail(), null,
                    Map.of(
                        "tenantName",  request.getTenantName(),
                        FIELD_COMPANY, request.getCompanyName(),
                        "username",    request.getAdminUsername(),
                        "tempPassword", tempPass,
                        "opacUrl",     "http://localhost:8083",
                        "subject",     "Your Orque OPAC Credentials",
                        "body",        "Welcome to OPAC! Your tenant \"" + request.getTenantName() + "\" is now active."
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
        Optional<ApprovalRequest> req = approvalRequestRepository.findByReferenceUuid(uuid);
        if (req.isPresent()) {
            return approvalHistoryRepository.findAllByApprovalRequestUuidOrderByCreatedTimestampAsc(req.get().getUuid());
        }
        return Collections.emptyList();
    }

    // =========================================================================
    // LICENSING MODULE CONTROLLER
    // =========================================================================
    @GetMapping("/licenses")
    public List<Map<String, Object>> getLicenses(@RequestParam(required = false) String status) {
        List<LicenseRequest> requests;
        if (status != null && !status.isEmpty()) {
            requests = licenseRequestRepository.findAllByStatusOrderByCreatedTimestampDesc(status);
        } else {
            requests = licenseRequestRepository.findAllByOrderByCreatedTimestampDesc();
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
    public ResponseEntity<?> getLicenseById(@PathVariable UUID uuid) {
        try {
            LicenseRequest req = licenseRequestRepository.findById(uuid).orElseThrow();
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
            ApprovalRequest approval = approvalRequestRepository.findByReferenceUuid(uuid).orElse(new ApprovalRequest());
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
            ApprovalRequest approval = approvalRequestRepository.findByReferenceUuid(uuid).orElse(new ApprovalRequest());
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
    public ResponseEntity<?> handleLicenseApproval(@PathVariable String action, @PathVariable UUID uuid, @RequestBody Map<String, String> body, @RequestHeader(value = "x-user", defaultValue = "system-admin") String user) {
        try {
            LicenseRequest req = licenseRequestRepository.findById(uuid).orElseThrow();
            ApprovalRequest approval = approvalRequestRepository.findByReferenceUuid(uuid).orElseThrow();
            String notes = body.get("notes");

            if ("approve".equals(action)) {
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

                // Fetch details
                List<LicenseProduct> prods = licenseProductRepository.findAllByLicenseRequestUuid(uuid);
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

                // Update settings json in configuration
                Map<String, Object> settingsMap = new HashMap<>();
                Map<String, Object> licensedProds = new HashMap<>();
                for (Map<String, Object> p : productsList) {
                    licensedProds.put((String) p.get("productName"), Map.of(
                            "enabled", true,
                            "expiry", p.get("endDate"),
                            "features", p.get("features")
                    ));
                }
                settingsMap.put("licensedProducts", licensedProds);

                TenantConfiguration config = tenantConfigurationRepository.findByTenantUuid(tenantUuid).orElseThrow();
                config.setSettingsJson(objectMapper.writeValueAsString(settingsMap));
                tenantConfigurationRepository.save(config);

                // Send license activation email via template
                emailService.sendFromTemplate("license_approved", req.getEmail(), null,
                    Map.of(
                        FIELD_COMPANY, req.getCompanyName(),
                        "licenseKey",  encryptedKey,
                        "subject",     "Your Orque Product License Key",
                        "body",        "Your license for " + req.getCompanyName() + " is now active."
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

    @PostMapping("/licenses/apply")
    public ResponseEntity<?> applyLicense(@RequestBody Map<String, String> body, @RequestHeader(value = "x-user", defaultValue = "system-admin") String user) {
        try {
            String licenseKey = body.get("licenseKey").trim();
            UUID tenantUuid = UUID.fromString(body.get("tenantUuid"));

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

            // Update Configuration settings json
            Map<String, Object> settingsMap = new HashMap<>();
            Map<String, Object> licensedProds = new HashMap<>();
            for (Map<String, Object> p : products) {
                licensedProds.put((String) p.get("productName"), Map.of(
                        "enabled", true,
                        "expiry", p.get("endDate"),
                        "features", p.get("features")
                ));
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
    public List<Map<String, Object>> getAuditLogs() {
        List<AuditLog> logs = auditLogRepository.findAllByOrderByCreatedTimestampDesc();
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

    @GetMapping("/tenants-master")
    public List<Map<String, Object>> getTenantsMaster() {
        List<TenantMaster> masters = tenantMasterRepository.findAll();
        List<Map<String, Object>> result = new ArrayList<>();
        for (TenantMaster m : masters) {
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

    @GetMapping("/notifications")
    public List<NotificationMaster> getNotifications() {
        return notificationMasterRepository.findAllByOrderByCreatedTimestampDesc();
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
            String text    = (String) body.get("body");
            if (to == null || to.isBlank()) {
                return ResponseEntity.badRequest().body(Map.of("error", "Recipient email is required."));
            }
            emailService.sendEmail(to, cc, subject, text);
            return ResponseEntity.ok(Map.of("success", true));
        } catch (Exception e) {
            return ResponseEntity.status(500).body(Map.of("error", e.getMessage()));
        }
    }

    // =========================================================================
    // USERS MANAGEMENT APIs
    // =========================================================================
    @GetMapping("/users")
    public List<Map<String, Object>> getUsers() {
        List<UserMaster> users = userService.getAllUsers();
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
            map.put("createdTimestamp", u.getCreatedTimestamp());
            result.add(map);
        }
        return result;
    }

    @PostMapping("/users")
    public ResponseEntity<?> saveUser(@RequestBody Map<String, Object> body, @RequestHeader(value = "x-user", defaultValue = "system-admin") String actor) {
        try {
            String userIdStr = (String) body.get("userId");
            if (userIdStr == null) {
                userIdStr = (String) body.get("uuid");
            }
            UserMaster user = new UserMaster();
            if (userIdStr != null && !userIdStr.isEmpty()) {
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

            UserMaster saved = userService.saveUser(user, actor);
            return ResponseEntity.ok(Map.of("userId", saved.getUuid(), "success", true));
        } catch (IllegalArgumentException e) {
            return ResponseEntity.badRequest().body(Map.of("error", e.getMessage()));
        } catch (Exception e) {
            return ResponseEntity.status(500).body(Map.of("error", e.getMessage()));
        }
    }

    @PostMapping("/users/{userId}")
    public ResponseEntity<?> updateUser(@PathVariable UUID userId, @RequestBody Map<String, Object> body, @RequestHeader(value = "x-user", defaultValue = "system-admin") String actor) {
        body.put("userId", userId.toString());
        return saveUser(body, actor);
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
    public List<Map<String, Object>> getRoles() {
        List<RoleMaster> roles = roleService.getAllRoles();
        List<Map<String, Object>> result = new ArrayList<>();
        for (RoleMaster r : roles) {
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
    public ResponseEntity<?> saveRole(@RequestBody Map<String, Object> body, @RequestHeader(value = "x-user", defaultValue = "system-admin") String actor) {
        try {
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
    public ResponseEntity<?> updateRole(@PathVariable UUID roleId, @RequestBody Map<String, Object> body, @RequestHeader(value = "x-user", defaultValue = "system-admin") String actor) {
        body.put("roleId", roleId.toString());
        return saveRole(body, actor);
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
    public List<Map<String, Object>> getAudits() {
        List<AuditLog> logs = auditLogRepository.findAllByOrderByCreatedTimestampDesc();
        List<Map<String, Object>> result = new ArrayList<>();
        for (AuditLog l : logs) {
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
    public List<Map<String, Object>> getSessions() {
        List<SessionMaster> sessions = sessionMasterRepository.findAllByOrderByLoginTimestampDesc();
        List<Map<String, Object>> result = new ArrayList<>();
        for (SessionMaster s : sessions) {
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
    public ResponseEntity<?> getActiveTenants() {
        try {
            List<TenantMaster> tenants = tenantMasterRepository.findByStatus(STATUS_ACTIVE);
            List<Map<String, Object>> result = new ArrayList<>();
            for (TenantMaster t : tenants) {
                Map<String, Object> map = new HashMap<>();
                map.put("uuid", t.getUuid());
                map.put("label", t.getTenantName());
                map.put("value", t.getUuid());
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
            List<UserMaster> users = userMasterRepository.findByTenantUuidAndStatus(tenantUuid, STATUS_ACTIVE);
            List<Map<String, Object>> result = new ArrayList<>();
            for (UserMaster u : users) {
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
