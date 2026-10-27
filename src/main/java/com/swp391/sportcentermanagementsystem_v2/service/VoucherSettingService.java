package com.swp391.sportcentermanagementsystem_v2.service;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.swp391.sportcentermanagementsystem_v2.dto.responseDTO.VoucherSettingResponse;
import com.swp391.sportcentermanagementsystem_v2.entity.User;
import com.swp391.sportcentermanagementsystem_v2.exception.BusinessRuleException;
import com.swp391.sportcentermanagementsystem_v2.repository.UserRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;

import java.math.BigDecimal;
import java.time.OffsetDateTime;
import java.util.List;
import java.util.Map;
import java.util.Set;

// BR-VCH-05: manager xem và chỉnh mức giảm, hạn dùng của các loại voucher tự động (lưu trong system_settings)
@Service
@RequiredArgsConstructor
public class VoucherSettingService {

    // Cấu hình dạng {kind, value, applies_to, valid_days}
    private static final List<String> RULE_KEYS = List.of(
            "voucher.welcome", "voucher.completion", "voucher.compensation", "voucher.early_renewal", "voucher.winback");
    // Cấu hình là một số ngày
    private static final List<String> DAY_KEYS = List.of(
            "voucher.birthday_valid_days", "voucher.early_renewal_days", "voucher.winback_after_days");
    private static final Set<String> KINDS = Set.of("PERCENT", "AMOUNT", "FREE_ONE_ON_ONE");
    private static final Set<String> APPLIES_TO = Set.of("CLASS", "ONE_ON_ONE", "PACKAGE");
    private static final int MAX_DAYS = 365;

    private final JdbcTemplate jdbcTemplate;
    private final UserRepository userRepository;
    private final AuditService auditService;
    private final ObjectMapper objectMapper;

    @Transactional(readOnly = true)
    public List<VoucherSettingResponse> list() {
        List<String> keys = allKeys();
        return jdbcTemplate.query("""
                SELECT key, value::text AS value, description, updated_at FROM system_settings
                WHERE key = ANY (?) ORDER BY key
                """, ps -> ps.setArray(1, ps.getConnection().createArrayOf("varchar", keys.toArray())),
                (rs, i) -> new VoucherSettingResponse(rs.getString("key"), parse(rs.getString("value")),
                        rs.getString("description"), rs.getObject("updated_at", OffsetDateTime.class)));
    }

    @Transactional
    public VoucherSettingResponse update(String managerEmail, String key, JsonNode value) {
        if (!allKeys().contains(key)) {
            throw new ResponseStatusException(HttpStatus.NOT_FOUND, "Unknown voucher setting: " + key);
        }
        User manager = userRepository.findByEmail(managerEmail)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "User not found"));
        validate(key, value);

        int updated = jdbcTemplate.update("""
                UPDATE system_settings SET value = ?::jsonb, updated_by = ?, updated_at = now() WHERE key = ?
                """, value.toString(), manager.getId(), key);
        if (updated == 0) {
            throw new BusinessRuleException("Setting " + key + " is missing in the database; run the latest migration");
        }
        auditService.log(manager, "VOUCHER_SETTING_UPDATED", "system_setting", null,
                Map.of("key", key, "value", value.toString()));
        return list().stream().filter(s -> s.key().equals(key)).findFirst().orElseThrow();
    }

    private static List<String> allKeys() {
        return java.util.stream.Stream.concat(RULE_KEYS.stream(), DAY_KEYS.stream()).toList();
    }

    private static void validate(String key, JsonNode v) {
        if (DAY_KEYS.contains(key)) {
            requireDays(v, key);
            return;
        }
        if (!v.isObject()) {
            throw new BusinessRuleException(key + " must be an object with kind, value, applies_to, valid_days");
        }
        String kind = v.path("kind").asText("");
        String appliesTo = v.path("applies_to").asText("");
        if (!KINDS.contains(kind)) {
            throw new BusinessRuleException("kind must be PERCENT, AMOUNT or FREE_ONE_ON_ONE");
        }
        if (!APPLIES_TO.contains(appliesTo)) {
            throw new BusinessRuleException("applies_to must be CLASS, ONE_ON_ONE or PACKAGE");
        }
        if ("FREE_ONE_ON_ONE".equals(kind) && !"ONE_ON_ONE".equals(appliesTo)) {
            throw new BusinessRuleException("FREE_ONE_ON_ONE can only apply to ONE_ON_ONE");
        }
        JsonNode amount = v.get("value");
        if (amount == null || !amount.isNumber() || amount.decimalValue().signum() < 0
                || ("PERCENT".equals(kind) && amount.decimalValue().compareTo(BigDecimal.valueOf(100)) > 0)) {
            throw new BusinessRuleException("value must be a number from 0 (PERCENT: up to 100)");
        }
        requireDays(v.get("valid_days"), "valid_days");
    }

    private static void requireDays(JsonNode n, String name) {
        if (n == null || !n.isInt() || n.intValue() < 1 || n.intValue() > MAX_DAYS) {
            throw new BusinessRuleException(name + " must be a whole number from 1 to " + MAX_DAYS);
        }
    }

    private JsonNode parse(String json) {
        try {
            return objectMapper.readTree(json);
        } catch (JsonProcessingException ex) {
            throw new IllegalStateException("Invalid JSON in system_settings", ex);
        }
    }
}
