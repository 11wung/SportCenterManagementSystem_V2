package com.swp391.sportcentermanagementsystem_v2.service;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.swp391.sportcentermanagementsystem_v2.entity.User;
import lombok.RequiredArgsConstructor;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.Map;

// Ghi nhật ký thao tác quan trọng (BR-AUD-02). Bảng audit_logs chỉ thêm, DB chặn sửa và xóa (BR-AUD-01)
@Service
@RequiredArgsConstructor
public class AuditService {

    private final JdbcTemplate jdbcTemplate;
    private final ObjectMapper objectMapper;

    // Ghi cùng transaction với thao tác được ghi: thao tác rollback thì dòng nhật ký cũng rollback
    @Transactional
    public void log(User actor, String action, String resource, Long resourceId, Map<String, Object> payload) {
        String json;
        try {
            json = objectMapper.writeValueAsString(payload);
        } catch (JsonProcessingException ex) {
            throw new IllegalStateException("Cannot serialize audit payload", ex);
        }
        jdbcTemplate.update(
                "insert into audit_logs (user_id, action, resource, resource_id, payload_diff)"
                        + " values (?, ?, ?, ?, cast(? as jsonb))",
                actor == null ? null : actor.getId(), action, resource,
                resourceId == null ? null : String.valueOf(resourceId), json);
    }
}
