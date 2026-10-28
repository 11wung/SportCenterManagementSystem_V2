package com.swp391.sportcentermanagementsystem_v2.controller;

import com.fasterxml.jackson.databind.JsonNode;
import com.swp391.sportcentermanagementsystem_v2.dto.responseDTO.VoucherSettingResponse;
import com.swp391.sportcentermanagementsystem_v2.service.VoucherSettingService;
import lombok.RequiredArgsConstructor;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

// Manager chỉnh cấu hình voucher tự động (BR-VCH-05)
@RestController
@RequestMapping("/api/manager/voucher-settings")
@RequiredArgsConstructor
public class VoucherSettingController {

    private final VoucherSettingService settingService;

    @GetMapping
    public List<VoucherSettingResponse> list() {
        return settingService.list();
    }

    // Body là giá trị mới: object {kind, value, applies_to, valid_days} hoặc một số ngày, tùy key
    @PutMapping("/{key}")
    public VoucherSettingResponse update(Authentication authentication, @PathVariable String key,
                                         @RequestBody JsonNode value) {
        return settingService.update(authentication.getName(), key, value);
    }
}
