package com.swp391.sportcentermanagementsystem_v2.service;

import lombok.RequiredArgsConstructor;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

// Đọc cấu hình manager chỉnh được trong bảng system_settings (SRS mục 7)
@Service
@RequiredArgsConstructor
public class SystemSettingService {

    private final JdbcTemplate jdbcTemplate;

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
}
