package com.swp391.sportcentermanagementsystem_v2.controller;

import com.swp391.sportcentermanagementsystem_v2.dto.responseDTO.PricingPreviewResponse;
import com.swp391.sportcentermanagementsystem_v2.dto.responseDTO.VoucherListResponse;
import com.swp391.sportcentermanagementsystem_v2.repository.UserRepository;
import com.swp391.sportcentermanagementsystem_v2.service.PricingService;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.server.ResponseStatusException;

// Chọn voucher và xem giá trước khi thanh toán (BR-BEN-01, BR-VCH). kind: CLASS (targetId là đăng ký lớp)
// hoặc ONE_ON_ONE (targetId là suất 1-1). Lễ tân xem hộ member qua cùng logic
@RestController
@RequestMapping("/api")
@RequiredArgsConstructor
public class PricingController {

    private final PricingService pricingService;
    private final UserRepository userRepository;

    // GET /api/member/vouchers?kind=CLASS&targetId=12 - Voucher dùng được, nhiều tiền giảm nhất trước
    @GetMapping("/member/vouchers")
    public VoucherListResponse myVouchers(Authentication authentication,
            @RequestParam(required = false) String kind,
            @RequestParam(required = false) Long targetId) {
        return pricingService.vouchers(memberIdOf(authentication), kind, targetId);
    }

    // GET /api/member/pricing/preview?kind=CLASS&targetId=12&voucherId=3 - Giá sau ưu đãi
    @GetMapping("/member/pricing/preview")
    public PricingPreviewResponse myPreview(Authentication authentication,
            @RequestParam String kind,
            @RequestParam Long targetId,
            @RequestParam(required = false) Long voucherId) {
        return pricingService.preview(memberIdOf(authentication), kind, targetId, voucherId);
    }

    // GET /api/receptionist/members/{memberId}/vouchers - Lễ tân xem voucher của member
    @GetMapping("/receptionist/members/{memberId}/vouchers")
    public VoucherListResponse memberVouchers(@PathVariable Long memberId,
            @RequestParam(required = false) String kind,
            @RequestParam(required = false) Long targetId) {
        return pricingService.vouchers(memberId, kind, targetId);
    }

    // GET /api/receptionist/members/{memberId}/pricing/preview - Lễ tân xem giá cho member
    @GetMapping("/receptionist/members/{memberId}/pricing/preview")
    public PricingPreviewResponse memberPreview(@PathVariable Long memberId,
            @RequestParam String kind,
            @RequestParam Long targetId,
            @RequestParam(required = false) Long voucherId) {
        return pricingService.preview(memberId, kind, targetId, voucherId);
    }

    private Long memberIdOf(Authentication authentication) {
        return userRepository.findByEmail(authentication.getName())
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "User not found!"))
                .getId();
    }
}
