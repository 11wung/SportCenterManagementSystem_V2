package com.swp391.sportcentermanagementsystem_v2.service;

import com.swp391.sportcentermanagementsystem_v2.dto.responseDTO.ManagerAuditLogResponse;
import com.swp391.sportcentermanagementsystem_v2.entity.AuditLog;
import com.swp391.sportcentermanagementsystem_v2.repository.AuditLogRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;

@Service
@RequiredArgsConstructor
public class ManagerAuditLogService {

    private final AuditLogRepository auditLogRepository;

    @Transactional(readOnly = true)
    public List<ManagerAuditLogResponse> getRecentLogs() {
        return auditLogRepository.findTop100ByOrderByCreatedAtDesc().stream()
                .map(this::toResponse)
                .toList();
    }

    private ManagerAuditLogResponse toResponse(AuditLog log) {
        ManagerAuditLogResponse response = new ManagerAuditLogResponse();
        response.setId(log.getId());
        if (log.getUser() != null) {
            response.setActorId(log.getUser().getId());
            response.setActorName(log.getUser().getFullName());
            response.setActorEmail(log.getUser().getEmail());
        }
        response.setAction(log.getAction());
        response.setResource(log.getResource());
        response.setResourceId(log.getResourceId());
        response.setPayloadDiff(log.getPayloadDiff());
        response.setCreatedAt(log.getCreatedAt());
        return response;
    }
}
