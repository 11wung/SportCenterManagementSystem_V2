package com.swp391.sportcentermanagementsystem_v2.service;

import lombok.RequiredArgsConstructor;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;

import java.util.List;

// Đọc system_settings (value là jsonb vô hướng). Thiếu key thì dùng giá trị mặc định.
@Service
@RequiredArgsConstructor
public class SystemSettingService {

    private final JdbcTemplate jdbcTemplate;

    public int getInt(String key, int defaultValue) {
        List<String> rows = jdbcTemplate.queryForList(
                "SELECT value #>> '{}' FROM system_settings WHERE key = ?", String.class, key);
        if (rows.isEmpty() || rows.get(0) == null) {
            return defaultValue;
        }
        return Integer.parseInt(rows.get(0).trim());
    }
}
