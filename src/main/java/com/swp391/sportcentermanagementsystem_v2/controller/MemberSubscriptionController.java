package com.swp391.sportcentermanagementsystem_v2.controller;

import com.swp391.sportcentermanagementsystem_v2.dto.requestDTO.CreateSubscriptionRequest;
import com.swp391.sportcentermanagementsystem_v2.dto.responseDTO.MemberSubscriptionResponse;
import com.swp391.sportcentermanagementsystem_v2.dto.responseDTO.OnlinePaymentLinkResponse;
import com.swp391.sportcentermanagementsystem_v2.dto.responseDTO.PaymentResponse;
import com.swp391.sportcentermanagementsystem_v2.entity.User;
import com.swp391.sportcentermanagementsystem_v2.repository.UserRepository;
import com.swp391.sportcentermanagementsystem_v2.service.MemberSubscriptionService;
import com.swp391.sportcentermanagementsystem_v2.service.OnlinePaymentService;
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
    private final OnlinePaymentService onlinePaymentService;
    private final UserRepository userRepository;

    // API 1: POST /api/member/subscriptions - Học viên đăng ký gói tập
    @PostMapping
    public ResponseEntity<MemberSubscriptionResponse> registerSubscription(
            Authentication authentication,
            @Valid @RequestBody CreateSubscriptionRequest request) {

        User currentMember = getCurrentMember(authentication);

        MemberSubscriptionResponse response = subscriptionService.createSubscriptionOrder(
                currentMember.getId(),
                request,
                null,
                "NEW");

        return ResponseEntity.status(HttpStatus.CREATED).body(response);
    }

    // API 2: POST /api/member/subscriptions/renew - Học viên gia hạn gói tập
    @PostMapping("/renew")
    public ResponseEntity<MemberSubscriptionResponse> renewSubscription(
            Authentication authentication,
            @Valid @RequestBody CreateSubscriptionRequest request) {

        User currentMember = getCurrentMember(authentication);

        MemberSubscriptionResponse response = subscriptionService.createSubscriptionOrder(
                currentMember.getId(),
                request,
                null,
                "RENEWAL");

        return ResponseEntity.status(HttpStatus.CREATED).body(response);
    }

    // API 3: POST /api/member/subscriptions/upgrade - Nâng cấp gói
    @PostMapping("/upgrade")
    public ResponseEntity<MemberSubscriptionResponse> upgradeSubscription(
            Authentication authentication,
            @Valid @RequestBody CreateSubscriptionRequest request) {

        User currentMember = getCurrentMember(authentication);

        MemberSubscriptionResponse response = subscriptionService.createSubscriptionOrder(
                currentMember.getId(),
                request,
                null,
                "UPGRADE");

        return ResponseEntity.status(HttpStatus.CREATED).body(response);
    }

    // API 4: GET /api/member/subscriptions/me - Học viên xem lịch sử các gói đã mua
    @GetMapping("/me")
    public ResponseEntity<List<MemberSubscriptionResponse>> getMySubscriptions(Authentication authentication) {
        String email = authentication.getName();
        List<MemberSubscriptionResponse> responses = subscriptionService.getMySubscriptions(email);

        return ResponseEntity.status(HttpStatus.OK).body(responses);
    }

    // API 5: GET /api/member/subscriptions/me/status - Học viên kiểm tra trạng thái
    // gói hiện tại
    @GetMapping("/me/status")
    public ResponseEntity<MemberSubscriptionResponse> getMySubscriptionStatus(Authentication authentication) {
        String email = authentication.getName();
        MemberSubscriptionResponse response = subscriptionService.getMyActiveSubscription(email);

        return ResponseEntity.status(HttpStatus.OK).body(response);
    }

    // API Cancel Order theo Id
    @PatchMapping("/{id}/cancel")
    public ResponseEntity<Void> cancelPendingOrder(
            Authentication authentication,
            @PathVariable Long id) {

        User currentMember = getCurrentMember(authentication);

        subscriptionService.cancelPendingOrder(
                currentMember.getId(), id);

        return ResponseEntity.noContent().build();
    }

    // API Online Payment: tạo hoặc lấy lại QR PayOS cho đơn PENDING
    @PostMapping("/{id}/online-payment")
    public ResponseEntity<OnlinePaymentLinkResponse> startOnlinePayment(
            Authentication authentication,
            @PathVariable Long id) {

        OnlinePaymentLinkResponse response = onlinePaymentService.startOrReusePayment(
                id, authentication.getName());

        return ResponseEntity.ok(response);
    }

    // API Check Online Payment: hỏi PayOS ngay, đã trả thì kích hoạt gói
    @PostMapping("/{id}/online-payment/check")
    public ResponseEntity<PaymentResponse> checkOnlinePayment(
            Authentication authentication,
            @PathVariable Long id) {

        PaymentResponse response = onlinePaymentService.checkPayment(
                id, authentication.getName());

        return ResponseEntity.ok(response);
    }

    // Hàm phụ trợ: Lấy đối tượng User từ Token đăng nhập (JWT)
    private User getCurrentMember(Authentication authentication) {
        String email = authentication.getName();
        return userRepository.findByEmail(email)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "User not found!"));
    }

}