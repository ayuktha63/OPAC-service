package com.orque.opac.controller;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.orque.opac.entity.*;
import com.orque.opac.repository.*;
import com.orque.opac.service.Services;
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

    private final Services.SequenceService sequenceService;
    private final Services.AuditService auditService;
    private final Services.LicenseCryptService licenseCryptService;
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
                           Services.SequenceService sequenceService,
                           Services.AuditService auditService,
                           Services.LicenseCryptService licenseCryptService,
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
        this.sequenceService = sequenceService;
        this.auditService = auditService;
        this.licenseCryptService = licenseCryptService;
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
            String companyName = (String) body.get("companyName");
            String tenantName = (String) body.get("tenantName");
            String adminUsername = (String) body.get("adminUsername");
            String adminEmail = (String) body.get("adminEmail");
            String contactNumber = (String) body.get("contactNumber");
            String country = (String) body.get("country");
            String timezone = (String) body.get("timezone");

            // Duplicate checks
            if (uuidStr == null && tenantMasterRepository.findByTenantName(tenantName).isPresent()) {
                return ResponseEntity.badRequest().body(Map.of("error", "Tenant Alias already exists and is active."));
            }

            TenantRequest request;
            if (uuidStr != null && !uuidStr.isEmpty()) {
                request = tenantRequestRepository.findById(UUID.fromString(uuidStr)).orElseThrow();
                if (!"Draft".equals(request.getStatus()) && !"Returned".equals(request.getStatus())) {
                    return ResponseEntity.badRequest().body(Map.of("error", "Only Draft or Returned requests are editable."));
                }
                request.setCompanyName(companyName);
                request.setTenantName(tenantName);
                request.setAdminUsername(adminUsername);
                request.setAdminEmail(adminEmail);
                request.setContactNumber(contactNumber);
                request.setCountry(country);
                request.setTimezone(timezone);
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
                request.setStatus("Draft");
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
            request.setStatus("In Progress");
            tenantRequestRepository.save(request);

            ApprovalRequest approval = new ApprovalRequest();
            approval.setReferenceUuid(uuid);
            approval.setTriggerEvent("tenantRegistration");
            approval.setTenantId(request.getTenantName());
            approval.setStatus("Pending");
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
                request.setStatus("Active");
                tenantRequestRepository.save(request);

                approval.setStatus("Approved");
                approval.setUpdatedTimestamp(LocalDateTime.now());
                approvalRequestRepository.save(approval);

                ApprovalHistory history = new ApprovalHistory();
                history.setApprovalRequestUuid(approval.getUuid());
                history.setAction("Approve");
                history.setActorUsername(user);
                history.setNotes(notes);
                approvalHistoryRepository.save(history);

                // Create Master
                TenantMaster master = new TenantMaster();
                master.setTenantName(request.getTenantName());
                master.setCompanyName(request.getCompanyName());
                master.setStatus("Active");
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

                // Default roles
                RoleMaster reqRole = new RoleMaster();
                reqRole.setTenantUuid(savedMaster.getUuid());
                reqRole.setRoleName("Requester");
                reqRole.setIsSystemDefault(true);
                RoleMaster savedReqRole = roleMasterRepository.save(reqRole);

                RoleMaster appRole = new RoleMaster();
                appRole.setTenantUuid(savedMaster.getUuid());
                appRole.setRoleName("Approver");
                appRole.setIsSystemDefault(true);
                RoleMaster savedAppRole = roleMasterRepository.save(appRole);

                RolePermission p1 = new RolePermission();
                p1.setRoleUuid(savedReqRole.getUuid());
                p1.setAccessPolicyKey("tenant:read");
                rolePermissionRepository.save(p1);

                RolePermission p2 = new RolePermission();
                p2.setRoleUuid(savedAppRole.getUuid());
                p2.setAccessPolicyKey("tenant:approve");
                rolePermissionRepository.save(p2);

                // Default admin user
                UserMaster admin = new UserMaster();
                admin.setTenantUuid(savedMaster.getUuid());
                admin.setUsername(request.getAdminUsername());
                admin.setEmail(request.getAdminEmail());
                admin.setStatus("Active");
                userMasterRepository.save(admin);

                // Email queue
                String tempPass = UUID.randomUUID().toString().substring(0, 8);
                String emailBody = "Welcome to Orque Platform Administration Center (OPAC)!\n\n" +
                                   "Your tenant \"" + request.getTenantName() + "\" is ready.\n" +
                                   "Username: " + request.getAdminUsername() + "\n" +
                                   "Temporary Password: " + tempPass + "\n" +
                                   "OPAC URL: http://localhost:8083\n\n" +
                                   "An initial license request (Draft) has been created. Please configure and activate the license to enable product access.";

                EmailQueue email = new EmailQueue();
                email.setToEmail(request.getAdminEmail());
                email.setSubject("Your Orque OPAC Credentials");
                email.setBody(emailBody);
                email.setStatus("Sent");
                emailQueueRepository.save(email);

                // Initial License Draft Setup
                LicenseRequest licReq = new LicenseRequest();
                licReq.setRequestId(sequenceService.generateNextId("LIC"));
                licReq.setTenantUuid(savedMaster.getUuid());
                licReq.setCompanyName(request.getCompanyName());
                licReq.setEmail(request.getAdminEmail());
                licReq.setRequestedBy("System Onboarding");
                licReq.setStatus("Draft");
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
                request.setStatus("Inactive");
                tenantRequestRepository.save(request);

                approval.setStatus("Rejected");
                approvalRequestRepository.save(approval);

                ApprovalHistory history = new ApprovalHistory();
                history.setApprovalRequestUuid(approval.getUuid());
                history.setAction("Reject");
                history.setActorUsername(user);
                history.setNotes(notes);
                approvalHistoryRepository.save(history);

                auditService.logAuditEvent("REJECT", "Tenant", user, "tenant_request", uuid, "localhost");
            } else if ("return".equals(action)) {
                request.setStatus("Returned");
                tenantRequestRepository.save(request);

                approval.setStatus("Returned");
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
            map.put("companyName", req.getCompanyName());
            map.put("email", req.getEmail());
            map.put("requestedBy", req.getRequestedBy());
            map.put("status", req.getStatus());
            map.put("requestType", req.getRequestType());
            map.put("createdTimestamp", req.getCreatedTimestamp());

            List<LicenseProduct> products = licenseProductRepository.findAllByLicenseRequestUuid(req.getUuid());
            List<Map<String, Object>> productsMapped = new ArrayList<>();
            for (LicenseProduct p : products) {
                Map<String, Object> pMap = new HashMap<>();
                pMap.put("productName", p.getProductName());
                pMap.put("startDate", p.getStartDate());
                pMap.put("endDate", p.getEndDate());
                pMap.put("userLimit", p.getUserLimit());
                pMap.put("concurrentLimit", p.getConcurrentLimit());

                List<LicenseFeature> features = licenseFeatureRepository.findAllByLicenseProductUuid(p.getUuid());
                pMap.put("features", features.stream().map(LicenseFeature::getFeatureName).collect(Collectors.toList()));
                productsMapped.add(pMap);
            }
            map.put("licenseDetails", productsMapped);
            result.add(map);
        }
        return result;
    }

    @PostMapping("/licenses")
    public ResponseEntity<?> saveLicense(@RequestBody Map<String, Object> body, @RequestHeader(value = "x-user", defaultValue = "system-admin") String user) {
        try {
            String uuidStr = (String) body.get("uuid");
            String tenantUuidStr = (String) body.get("tenantUuid");
            String companyName = (String) body.get("companyName");
            String email = (String) body.get("email");
            String requestedBy = (String) body.get("requestedBy");
            List<Map<String, Object>> details = (List<Map<String, Object>>) body.get("licenseDetails");

            LicenseRequest req;
            UUID requestUuid;
            if (uuidStr != null && !uuidStr.isEmpty()) {
                requestUuid = UUID.fromString(uuidStr);
                req = licenseRequestRepository.findById(requestUuid).orElseThrow();
                req.setCompanyName(companyName);
                req.setEmail(email);
                req.setRequestedBy(requestedBy);
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
                req.setStatus("Draft");
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
                    product.setStartDate(LocalDate.parse((String) pMap.get("startDate")));
                    product.setEndDate(LocalDate.parse((String) pMap.get("endDate")));
                    product.setUserLimit((Integer) pMap.get("userLimit"));
                    product.setConcurrentLimit((Integer) pMap.get("concurrentLimit"));
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
            req.setStatus("In Progress");
            licenseRequestRepository.save(req);

            ApprovalRequest approval = new ApprovalRequest();
            approval.setReferenceUuid(uuid);
            approval.setTriggerEvent("licenseApproval");
            approval.setTenantId(req.getCompanyName());
            approval.setStatus("Pending");
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

    @PostMapping("/licenses/{action}/{uuid}")
    public ResponseEntity<?> handleLicenseApproval(@PathVariable String action, @PathVariable UUID uuid, @RequestBody Map<String, String> body, @RequestHeader(value = "x-user", defaultValue = "system-admin") String user) {
        try {
            LicenseRequest req = licenseRequestRepository.findById(uuid).orElseThrow();
            ApprovalRequest approval = approvalRequestRepository.findByReferenceUuid(uuid).orElseThrow();
            String notes = body.get("notes");

            if ("approve".equals(action)) {
                req.setStatus("Active");
                licenseRequestRepository.save(req);

                approval.setStatus("Approved");
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
                LocalDate maxEnd = LocalDate.now();

                for (LicenseProduct p : prods) {
                    Map<String, Object> pMap = new HashMap<>();
                    pMap.put("productName", p.getProductName());
                    pMap.put("startDate", p.getStartDate().toString());
                    pMap.put("endDate", p.getEndDate().toString());
                    pMap.put("userLimit", p.getUserLimit());
                    pMap.put("concurrentLimit", p.getConcurrentLimit());

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
                licensePayload.put("tenant", Map.of("tenantName", req.getCompanyName().toLowerCase().replaceAll("[^a-z0-9]", "-"), "companyName", req.getCompanyName()));
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
                master.setStatus("Active");
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

                // Send email
                EmailQueue email = new EmailQueue();
                email.setToEmail(req.getEmail());
                email.setSubject("Your Orque Product License Key");
                email.setBody("----- BEGIN ORQUE LICENSE KEY -----\n" + encryptedKey + "\n----- END ORQUE LICENSE KEY -----");
                email.setStatus("Sent");
                emailQueueRepository.save(email);

                auditService.logAuditEvent("APPROVE", "License", user, "license_request", uuid, "localhost");
            } else if ("reject".equals(action)) {
                req.setStatus("Cancelled");
                licenseRequestRepository.save(req);

                approval.setStatus("Rejected");
                approvalRequestRepository.save(approval);

                auditService.logAuditEvent("REJECT", "License", user, "license_request", uuid, "localhost");
            } else if ("return".equals(action)) {
                req.setStatus("Returned");
                licenseRequestRepository.save(req);

                approval.setStatus("Returned");
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
            master.setStatus("Active");
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
    // SESSION MANAGEMENT APIs
    // =========================================================================
    @GetMapping("/sessions")
    public List<SessionMaster> getSessions() {
        return sessionMasterRepository.findAllByOrderByLoginTimestampDesc();
    }

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
}
