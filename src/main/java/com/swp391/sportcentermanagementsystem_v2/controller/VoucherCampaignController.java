package com.swp391.sportcentermanagementsystem_v2.controller;

import com.swp391.sportcentermanagementsystem_v2.dto.requestDTO.CreateVoucherCampaignRequest;
import com.swp391.sportcentermanagementsystem_v2.dto.requestDTO.RedeemVoucherRequest;
import com.swp391.sportcentermanagementsystem_v2.dto.responseDTO.VoucherCampaignResponse;
import com.swp391.sportcentermanagementsystem_v2.dto.responseDTO.VoucherResponse;
import com.swp391.sportcentermanagementsystem_v2.service.VoucherCampaignService;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

// Voucher chiến dịch (BR-VCH-02): manager tạo và kết thúc, member nhập mã
@RestController
@RequestMapping("/api")
@RequiredArgsConstructor
public class VoucherCampaignController {

    private final VoucherCampaignService campaignService;

    // POST /api/manager/voucher-campaigns - Tạo chiến dịch
    @PostMapping("/manager/voucher-campaigns")
    public ResponseEntity<VoucherCampaignResponse> create(Authentication authentication,
            @Valid @RequestBody CreateVoucherCampaignRequest request) {
        return ResponseEntity.status(HttpStatus.CREATED).body(campaignService.create(authentication.getName(), request));
    }

    // GET /api/manager/voucher-campaigns - Danh sách chiến dịch kèm số lượt đã nhập
    @GetMapping("/manager/voucher-campaigns")
    public List<VoucherCampaignResponse> list() {
        return campaignService.list();
    }

    // PATCH /api/manager/voucher-campaigns/{id}/end - Kết thúc chiến dịch ngay
    @PatchMapping("/manager/voucher-campaigns/{id}/end")
    public VoucherCampaignResponse end(Authentication authentication, @PathVariable Long id) {
        return campaignService.end(authentication.getName(), id);
    }

    // POST /api/member/vouchers/redeem - Nhập mã để nhận voucher
    @PostMapping("/member/vouchers/redeem")
    public ResponseEntity<VoucherResponse> redeem(Authentication authentication,
            @Valid @RequestBody RedeemVoucherRequest request) {
        return ResponseEntity.status(HttpStatus.CREATED)
                .body(campaignService.redeem(authentication.getName(), request.getCode()));
    }
}
