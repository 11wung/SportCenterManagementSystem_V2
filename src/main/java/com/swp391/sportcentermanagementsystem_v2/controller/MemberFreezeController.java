package com.swp391.sportcentermanagementsystem_v2.controller;

import com.swp391.sportcentermanagementsystem_v2.dto.requestDTO.CreateFreezeRequest;
import com.swp391.sportcentermanagementsystem_v2.dto.responseDTO.SubscriptionFreezeResponse;
import com.swp391.sportcentermanagementsystem_v2.entity.User;
import com.swp391.sportcentermanagementsystem_v2.repository.UserRepository;
import com.swp391.sportcentermanagementsystem_v2.service.SubscriptionFreezeService;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.server.ResponseStatusException;

import java.util.List;

@RestController
@RequestMapping("/api/member/subscriptions/{subscriptionId}/freezes")
@RequiredArgsConstructor
public class MemberFreezeController {

    private final SubscriptionFreezeService freezeService;
    private final UserRepository userRepository;

    // Đặt lịch bảo lưu gói
    @PostMapping
    public ResponseEntity<SubscriptionFreezeResponse> create(
            Authentication authentication,
            @PathVariable Long subscriptionId,
            @Valid @RequestBody CreateFreezeRequest request) {

        return ResponseEntity.status(HttpStatus.CREATED)
                .body(freezeService.createFreeze(memberId(authentication), subscriptionId, request));
    }

    @GetMapping
    public ResponseEntity<List<SubscriptionFreezeResponse>> list(
            Authentication authentication,
            @PathVariable Long subscriptionId) {

        return ResponseEntity.ok(freezeService.listFreezes(memberId(authentication), subscriptionId));
    }

    // Hủy đợt chưa bắt đầu
    @PatchMapping("/{freezeId}/cancel")
    public ResponseEntity<SubscriptionFreezeResponse> cancel(
            Authentication authentication,
            @PathVariable Long subscriptionId,
            @PathVariable Long freezeId) {

        return ResponseEntity.ok(freezeService.cancelFreeze(memberId(authentication), subscriptionId, freezeId));
    }

    // Kết thúc sớm đợt đang chạy
    @PatchMapping("/{freezeId}/end")
    public ResponseEntity<SubscriptionFreezeResponse> end(
            Authentication authentication,
            @PathVariable Long subscriptionId,
            @PathVariable Long freezeId) {

        return ResponseEntity.ok(freezeService.endFreezeEarly(memberId(authentication), subscriptionId, freezeId));
    }

    private Long memberId(Authentication authentication) {
        User user = userRepository.findByEmail(authentication.getName())
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "User not found!"));
        return user.getId();
    }
}
