package com.swp391.sportcentermanagementsystem_v2.controller;

import com.fasterxml.jackson.databind.JsonNode;
import com.swp391.sportcentermanagementsystem_v2.service.SystemSettingService;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.Map;

// Công khai (không cần đăng nhập): các mức giá và ưu đãi hiển thị ở trang chủ
@RestController
@RequestMapping("/api/public")
@RequiredArgsConstructor
public class PublicSettingController {

    private final SystemSettingService settingService;

    @GetMapping("/landing-settings")
    public Map<String, JsonNode> landingSettings() {
        return settingService.getPublicSettings();
    }
}
