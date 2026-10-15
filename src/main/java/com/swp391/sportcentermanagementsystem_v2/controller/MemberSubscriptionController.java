package com.swp391.sportcentermanagementsystem_v2.controller;

import com.swp391.sportcentermanagementsystem_v2.dto.requestDTO.CreateSubscriptionRequest;
import com.swp391.sportcentermanagementsystem_v2.dto.responseDTO.MemberSubscriptionResponse;
import com.swp391.sportcentermanagementsystem_v2.entity.User;
import com.swp391.sportcentermanagementsystem_v2.repository.UserRepository;
import com.swp391.sportcentermanagementsystem_v2.service.MemberSubscriptionService;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.server.ResponseStatusException;

import java.util.List;

@RestController
@RequestMapping("/api/member/subscriptions")
@RequiredArgsConstructor
public class MemberSubscriptionController {

    private final MemberSubscriptionService subscriptionService;
    private final UserRepository userRepository;

    // API 1: POST /api/member/subscriptions - Học viên đăng ký gói tập
    @PostMapping
    public ResponseEntity<MemberSubscriptionResponse> registerSubscription(
            Authentication authentication,
            @Valid @RequestBody CreateSubscriptionRequest request) {

        User currentMember = getCurrentMember(authentication);
        MemberSubscriptionResponse response = subscriptionService.registerOrRenewSubscription(
                currentMember.getId(), request, null);

        return ResponseEntity.status(HttpStatus.CREATED).body(response);
    }

    // API 2: POST /api/member/subscriptions/renew - Học viên gia hạn gói tập
    @PostMapping("/renew")
    public ResponseEntity<MemberSubscriptionResponse> renewSubscription(
            Authentication authentication,
            @Valid @RequestBody CreateSubscriptionRequest request) {

        User currentMember = getCurrentMember(authentication);
        MemberSubscriptionResponse response = subscriptionService.registerOrRenewSubscription(
                currentMember.getId(), request, null);

        return ResponseEntity.status(HttpStatus.CREATED).body(response);
    }

    // API 3: GET /api/member/subscriptions/me - Học viên xem lịch sử các gói đã mua
    @GetMapping("/me")
    public ResponseEntity<List<MemberSubscriptionResponse>> getMySubscriptions(Authentication authentication) {
        String email = authentication.getName();
        List<MemberSubscriptionResponse> responses = subscriptionService.getMySubscriptions(email);

        return ResponseEntity.status(HttpStatus.OK).body(responses);
    }

    // API 4: GET /api/member/subscriptions/me/status - Học viên kiểm tra trạng thái
    // gói hiện tại
    @GetMapping("/me/status")
    public ResponseEntity<MemberSubscriptionResponse> getMySubscriptionStatus(Authentication authentication) {
        String email = authentication.getName();
        MemberSubscriptionResponse response = subscriptionService.getMyActiveSubscription(email);

        return ResponseEntity.status(HttpStatus.OK).body(response);
    }

    // Hàm phụ trợ: Lấy đối tượng User từ Token đăng nhập (JWT)
    private User getCurrentMember(Authentication authentication) {
        String email = authentication.getName();
        return userRepository.findByEmail(email)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "User not found!"));
    }
}