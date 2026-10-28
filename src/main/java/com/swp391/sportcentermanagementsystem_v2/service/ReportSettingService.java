package com.swp391.sportcentermanagementsystem_v2.service;

import com.fasterxml.jackson.databind.JsonNode;
import com.swp391.sportcentermanagementsystem_v2.entity.SystemSetting;
import com.swp391.sportcentermanagementsystem_v2.repository.SystemSettingRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
@RequiredArgsConstructor
public class ReportSettingService {

    private final SystemSettingRepository systemSettingRepository;

    /** Reads a numeric field from an object setting, with a safe default when not seeded. */
    @Transactional(readOnly = true)
    public int getIntField(String key, String field, int defaultValue) {
        return systemSettingRepository.findById(key)
                .map(SystemSetting::getValue)
                .map(value -> value.path(field).asInt(defaultValue))
                .orElse(defaultValue);
    }

    /** Reads a scalar JSON number setting, with a safe default when not seeded. */
    @Transactional(readOnly = true)
    public int getInt(String key, int defaultValue) {
        return systemSettingRepository.findById(key)
                .map(SystemSetting::getValue)
                .map(JsonNode::asInt)
                .orElse(defaultValue);
    }
}
