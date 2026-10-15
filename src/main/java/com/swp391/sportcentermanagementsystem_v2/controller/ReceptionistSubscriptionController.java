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
@RequestMapping("/api/receptionist/members/{memberId}/subscriptions")
@RequiredArgsConstructor
public class ReceptionistSubscriptionController {

    private final MemberSubscriptionService subscriptionService;
    private final UserRepository userRepository;

    // API 5: GET /api/receptionist/members/{memberId}/subscriptions - Lễ tân xem
    // lịch sử gói của học viên
    @GetMapping
    public ResponseEntity<List<MemberSubscriptionResponse>> getMemberSubscriptions(
            @PathVariable Long memberId) {

        List<MemberSubscriptionResponse> responses = subscriptionService.getSubscriptionsByMemberId(memberId);
        return ResponseEntity.status(HttpStatus.OK).body(responses);
    }

    // API 6: POST /api/receptionist/members/{memberId}/subscriptions - Lễ tân đăng
    // ký gói hộ tại quầy
    @PostMapping
    public ResponseEntity<MemberSubscriptionResponse> registerForMember(
            Authentication authentication,
            @PathVariable Long memberId,
            @Valid @RequestBody CreateSubscriptionRequest request) {

        User receptionist = getCurrentStaff(authentication);
        MemberSubscriptionResponse response = subscriptionService.registerOrRenewSubscription(
                memberId, request, receptionist.getId());

        return ResponseEntity.status(HttpStatus.CREATED).body(response);
    }

    // API 7: POST /api/receptionist/members/{memberId}/subscriptions/renew - Lễ tân
    // gia hạn hộ tại quầy
    @PostMapping("/renew")
    public ResponseEntity<MemberSubscriptionResponse> renewForMember(
            Authentication authentication,
            @PathVariable Long memberId,
            @Valid @RequestBody CreateSubscriptionRequest request) {

        User receptionist = getCurrentStaff(authentication);
        MemberSubscriptionResponse response = subscriptionService.registerOrRenewSubscription(
                memberId, request, receptionist.getId());

        return ResponseEntity.status(HttpStatus.CREATED).body(response);
    }

    // Hàm phụ trợ: Lấy thông tin Lễ tân đang đăng nhập để ghi nhận vào cột
    // created_by
    private User getCurrentStaff(Authentication authentication) {
        String email = authentication.getName();
        return userRepository.findByEmail(email)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "Staff not found!"));
    }
}