package com.orque.opac.controller;

import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;
import java.util.*;

@RestController
@CrossOrigin(origins = "*")
@RequestMapping("/api")
public class WorkflowActionsController {

    private static final String LABEL  = "label";
    private static final String ACTION = "action";

    @PostMapping("/workflow-actions")
    public ResponseEntity<?> getWorkflowActions(@RequestBody Map<String, Object> payload) {
        try {
            String type   = (String) payload.get("type");
            String status = (String) payload.get("status");

            List<Map<String, String>> actions = new ArrayList<>();

            if ("tenant".equalsIgnoreCase(type)) {
                if ("Draft".equalsIgnoreCase(status) || "Returned".equalsIgnoreCase(status)) {
                    actions.add(Map.of(LABEL, "Send for Approval", ACTION, "submit"));
                } else if ("In Progress".equalsIgnoreCase(status)) {
                    actions.add(Map.of(LABEL, "Approve",             ACTION, "approve"));
                    actions.add(Map.of(LABEL, "Reject",              ACTION, "reject"));
                    actions.add(Map.of(LABEL, "Return for Revision", ACTION, "return"));
                } else if ("Active".equalsIgnoreCase(status)) {
                    actions.add(Map.of(LABEL, "Share", ACTION, "share"));
                }
            } else if ("license".equalsIgnoreCase(type)) {
                if ("Draft".equalsIgnoreCase(status) || "Returned".equalsIgnoreCase(status)) {
                    actions.add(Map.of(LABEL, "Send for Approval", ACTION, "submit"));
                } else if ("In Progress".equalsIgnoreCase(status)) {
                    actions.add(Map.of(LABEL, "Approve",             ACTION, "approve"));
                    actions.add(Map.of(LABEL, "Reject",              ACTION, "reject"));
                    actions.add(Map.of(LABEL, "Return for Revision", ACTION, "return"));
                } else if ("Active".equalsIgnoreCase(status)) {
                    actions.add(Map.of(LABEL, "Renew License",   ACTION, "renew"));
                    actions.add(Map.of(LABEL, "Upgrade License", ACTION, "upgrade"));
                    actions.add(Map.of(LABEL, "Share",           ACTION, "share"));
                }
            }

            return ResponseEntity.ok(actions);
        } catch (Exception e) {
            return ResponseEntity.status(500).body(Map.of("error", e.getMessage()));
        }
    }
}
