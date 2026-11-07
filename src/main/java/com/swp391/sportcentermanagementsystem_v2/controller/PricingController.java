package com.swp391.sportcentermanagementsystem_v2.controller;

import com.swp391.sportcentermanagementsystem_v2.dto.responseDTO.PricingPreviewResponse;
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

// Xem giá trước khi thanh toán (BR-BEN-01). kind: CLASS (targetId là đăng ký lớp)
// hoặc ONE_ON_ONE (targetId là suất 1-1). Lễ tân xem hộ member qua cùng logic
@RestController
@RequestMapping("/api")
@RequiredArgsConstructor
public class PricingController {

    private final PricingService pricingService;
    private final UserRepository userRepository;

    // GET /api/member/pricing/preview?kind=CLASS&targetId=12 - Giá sau ưu đãi
    @GetMapping("/member/pricing/preview")
    public PricingPreviewResponse myPreview(Authentication authentication,
            @RequestParam String kind,
            @RequestParam Long targetId) {
        return pricingService.preview(memberIdOf(authentication), kind, targetId);
    }

    // GET /api/receptionist/members/{memberId}/pricing/preview - Lễ tân xem giá cho member
    @GetMapping("/receptionist/members/{memberId}/pricing/preview")
    public PricingPreviewResponse memberPreview(@PathVariable Long memberId,
            @RequestParam String kind,
            @RequestParam Long targetId) {
        return pricingService.preview(memberId, kind, targetId);
    }

    private Long memberIdOf(Authentication authentication) {
        return userRepository.findByEmail(authentication.getName())
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "Không tìm thấy người dùng"))
                .getId();
    }
}
