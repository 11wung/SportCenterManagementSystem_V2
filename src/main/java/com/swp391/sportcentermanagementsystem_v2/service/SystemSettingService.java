package com.swp391.sportcentermanagementsystem_v2.service;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.RequiredArgsConstructor;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

// Đọc cấu hình manager chỉnh được trong bảng system_settings (SRS mục 7)
@Service
@RequiredArgsConstructor
public class SystemSettingService {

    // Chỉ các key này được công khai cho trang chủ, không lộ cấu hình nội bộ khác
    private static final List<String> PUBLIC_KEYS = List.of(
            "pricing.duration_discounts", "one_on_one.hourly_price",
            "center.open_minute", "center.close_minute",
            "one_on_one.refund_before_hours", "one_on_one.slot_minutes", "payment.hold_minutes",
            "one_on_one.max_sessions_per_request", "one_on_one.request_min_lead_hours", "one_on_one.request_pay_hours",
            "one_on_one.reschedule_before_hours");

    private final JdbcTemplate jdbcTemplate;
    private final ObjectMapper objectMapper;

    // Cấu hình hiển thị cho khách ở trang chủ; key chưa seed thì bỏ qua
    @Transactional(readOnly = true)
    public Map<String, JsonNode> getPublicSettings() {
        Map<String, JsonNode> result = new LinkedHashMap<>();
        jdbcTemplate.query("SELECT key, value::text FROM system_settings WHERE key = ANY (?)",
                rs -> {
                    try {
                        result.put(rs.getString(1), objectMapper.readTree(rs.getString(2)));
                    } catch (JsonProcessingException e) {
                        throw new IllegalStateException("Invalid JSON in system setting: " + rs.getString(1), e);
                    }
                },
                (Object) PUBLIC_KEYS.toArray(new String[0]));
        return result;
    }

    // Cấu hình thiếu là lỗi triển khai (chưa seed), nên ném lỗi thay vì tự đoán giá trị mặc định
    @Transactional(readOnly = true)
    public int getInt(String key) {
        String value = jdbcTemplate
                .queryForList("select value::text from system_settings where key = ?", String.class, key)
                .stream()
                .findFirst()
                .orElseThrow(() -> new IllegalStateException("Missing system setting: " + key));
        return Integer.parseInt(value.trim());
    }

    // Database hiện dùng exclusion constraint cố định 15 phút; không cho cấu hình runtime lệch khỏi DB.
    @Transactional(readOnly = true)
    public int getScheduleBufferMinutes() {
        int configured = getInt("schedule.buffer_minutes");
        if (configured != 15) {
            throw new IllegalStateException(
                    "schedule.buffer_minutes phải bằng 15 vì constraint trong PostgreSQL hiện cố định 15 phút. "
                            + "Không tự đổi cấu hình nếu chưa cập nhật đồng bộ constraint/trigger trong database.");
        }
        return 15;
    }

    // Bản có giá trị mặc định cho cấu hình tùy chọn (value là jsonb vô hướng)
    @Transactional(readOnly = true)
    public int getInt(String key, int defaultValue) {
        List<String> rows = jdbcTemplate.queryForList(
                "SELECT value #>> '{}' FROM system_settings WHERE key = ?", String.class, key);
        if (rows.isEmpty() || rows.get(0) == null) {
            return defaultValue;
        }
        return Integer.parseInt(rows.get(0).trim());
    }
}
