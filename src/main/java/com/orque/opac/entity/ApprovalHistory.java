package com.orque.opac.entity;

import jakarta.persistence.*;
import java.time.LocalDateTime;
import java.time.LocalDate;
import java.util.UUID;

@Entity
@Table(name = "approval_history")
public class ApprovalHistory {
    @Id
    @GeneratedValue(strategy = GenerationType.AUTO)
    private UUID uuid;

    @Column(name = "approval_request_uuid", nullable = false)
    private UUID approvalRequestUuid;

    @Column(nullable = false)
    private String action;

    @Column(name = "actor_username", nullable = false)
    private String actorUsername;

    private String notes;

    @Column(name = "created_timestamp")
    private LocalDateTime createdTimestamp = LocalDateTime.now();

    public UUID getUuid() { return uuid; }
    public void setUuid(UUID uuid) { this.uuid = uuid; }

    public UUID getApprovalRequestUuid() { return approvalRequestUuid; }
    public void setApprovalRequestUuid(UUID approvalRequestUuid) { this.approvalRequestUuid = approvalRequestUuid; }

    public String getAction() { return action; }
    public void setAction(String action) { this.action = action; }

    public String getActorUsername() { return actorUsername; }
    public void setActorUsername(String actorUsername) { this.actorUsername = actorUsername; }

    public String getNotes() { return notes; }
    public void setNotes(String notes) { this.notes = notes; }

    public LocalDateTime getCreatedTimestamp() { return createdTimestamp; }
    public void setCreatedTimestamp(LocalDateTime createdTimestamp) { this.createdTimestamp = createdTimestamp; }
}
