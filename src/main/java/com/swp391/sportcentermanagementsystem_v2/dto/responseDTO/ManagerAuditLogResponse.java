package com.swp391.sportcentermanagementsystem_v2.dto.responseDTO;

import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

import java.time.OffsetDateTime;
import java.util.Map;

@Getter
@Setter
@NoArgsConstructor
public class ManagerAuditLogResponse {
    private Long id;
    private Long actorId;
    private String actorName;
    private String actorEmail;
    private String action;
    private String resource;
    private String resourceId;
    private Map<String, Object> payloadDiff;
    private OffsetDateTime createdAt;
}
