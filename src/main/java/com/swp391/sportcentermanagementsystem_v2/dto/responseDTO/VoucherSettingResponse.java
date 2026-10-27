package com.swp391.sportcentermanagementsystem_v2.dto.responseDTO;

import com.fasterxml.jackson.databind.JsonNode;

import java.time.OffsetDateTime;

// value là object {kind, value, applies_to, valid_days} hoặc một số ngày, tùy key
public record VoucherSettingResponse(String key, JsonNode value, String description, OffsetDateTime updatedAt) {
}
