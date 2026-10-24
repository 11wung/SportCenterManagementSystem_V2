package com.swp391.sportcentermanagementsystem_v2.controller;

import com.swp391.sportcentermanagementsystem_v2.dto.requestDTO.ClassRegistrationRequest;
import com.swp391.sportcentermanagementsystem_v2.dto.responseDTO.ClassRegistrationResponse;
import com.swp391.sportcentermanagementsystem_v2.dto.responseDTO.OnlinePaymentLinkResponse;
import com.swp391.sportcentermanagementsystem_v2.dto.responseDTO.PaymentResponse;
import com.swp391.sportcentermanagementsystem_v2.service.ClassRegistrationService;
import com.swp391.sportcentermanagementsystem_v2.service.OnlinePaymentService;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.*;

import java.util.List;

@RestController
@RequestMapping("/api")
@RequiredArgsConstructor
public class ClassRegistrationController {

    private final ClassRegistrationService classRegistrationService;
    private final OnlinePaymentService onlinePaymentService;

    // ====================== MEMBER ======================

    // API 1: POST /api/member/class-registrations/{classId}?acknowledgeAdvancedWarning=false - Hội viên tự ghi danh
    // Tạo đăng ký PENDING_PAYMENT giữ chỗ 15 phút; khóa nâng cao phải gửi acknowledgeAdvancedWarning=true
    @PostMapping("/member/class-registrations/{classId}")
    public ResponseEntity<ClassRegistrationResponse> registerMyself(
            Authentication authentication,
            @PathVariable Long classId,
            @RequestParam(defaultValue = "false") boolean acknowledgeAdvancedWarning) {

        ClassRegistrationResponse response = classRegistrationService
                .registerMyself(authentication.getName(), classId, acknowledgeAdvancedWarning);
        return ResponseEntity.status(HttpStatus.CREATED).body(response);
    }

    // API 1b: POST /api/member/class-registrations/{classId}/waitlist - Vào danh sách chờ khi class hết chỗ (BR-WL-01)
    // Rời danh sách chờ dùng lại API hủy bên dưới
    @PostMapping("/member/class-registrations/{classId}/waitlist")
    public ResponseEntity<ClassRegistrationResponse> joinWaitlist(
            Authentication authentication,
            @PathVariable Long classId,
            @RequestParam(defaultValue = "false") boolean acknowledgeAdvancedWarning) {

        ClassRegistrationResponse response = classRegistrationService
                .joinWaitlist(authentication.getName(), classId, acknowledgeAdvancedWarning);
        return ResponseEntity.status(HttpStatus.CREATED).body(response);
    }

    // API 1c: GET /api/sport-classes/{classId}/waitlist - Manager, lễ tân xem danh sách chờ theo thứ tự được mời
    @GetMapping("/sport-classes/{classId}/waitlist")
    public ResponseEntity<List<ClassRegistrationResponse>> getWaitlist(@PathVariable Long classId) {
        return ResponseEntity.ok(classRegistrationService.getWaitlist(classId));
    }

    // API 2: PATCH /api/member/class-registrations/{registrationId}/cancel - Hội viên hủy đăng ký
    // Chưa trả tiền: hủy. Đã trả: trước ngày khai giảng hoàn đủ, từ ngày khai giảng rời lớp không hoàn (BR-CAN-01)
    @PatchMapping("/member/class-registrations/{registrationId}/cancel")
    public ResponseEntity<ClassRegistrationResponse> cancelMyRegistration(
            Authentication authentication,
            @PathVariable Long registrationId) {

        return ResponseEntity.ok(onlinePaymentService
                .cancelMyRegistration(authentication.getName(), registrationId));
    }

    // API 2b: POST /api/member/class-registrations/{registrationId}/leave - Rời lớp khi hơn 30% buổi bị trung tâm hủy (BR-MKP-07)
    // Hoàn theo số buổi chưa học
    @PostMapping("/member/class-registrations/{registrationId}/leave")
    public ResponseEntity<ClassRegistrationResponse> leaveAfterCancellations(
            Authentication authentication,
            @PathVariable Long registrationId) {

        return ResponseEntity.ok(classRegistrationService
                .leaveAfterCancellations(authentication.getName(), registrationId));
    }

    // API 3: GET /api/member/class-registrations/me - Hội viên xem các lớp đã đăng ký
    @GetMapping("/member/class-registrations/me")
    public ResponseEntity<List<ClassRegistrationResponse>> getMyRegistrations(Authentication authentication) {
        return ResponseEntity.ok(classRegistrationService.getMyRegistrations(authentication.getName()));
    }

    // API 3b: POST /api/member/class-registrations/{registrationId}/online-payment - Tạo (hoặc lấy lại) QR thanh toán
    @PostMapping("/member/class-registrations/{registrationId}/online-payment")
    public ResponseEntity<OnlinePaymentLinkResponse> startOnlinePayment(
            Authentication authentication,
            @PathVariable Long registrationId) {

        return ResponseEntity.ok(onlinePaymentService
                .startOrReuseRegistrationPayment(registrationId, authentication.getName()));
    }

    // API 3c: POST /api/member/class-registrations/{registrationId}/online-payment/check - Hỏi PayOS ngay, đã trả thì kích hoạt
    @PostMapping("/member/class-registrations/{registrationId}/online-payment/check")
    public ResponseEntity<PaymentResponse> checkOnlinePayment(
            Authentication authentication,
            @PathVariable Long registrationId) {

        return ResponseEntity.ok(onlinePaymentService
                .checkRegistrationPayment(registrationId, authentication.getName()));
    }

    // ====================== RECEPTIONIST ======================

    // API 4: POST /api/receptionist/class-registrations - Lễ tân ghi danh hộ hội viên
    @PostMapping("/receptionist/class-registrations")
    public ResponseEntity<ClassRegistrationResponse> registerMemberByReceptionist(
            Authentication authentication,
            @Valid @RequestBody ClassRegistrationRequest request) {

        ClassRegistrationResponse response = classRegistrationService
                .registerMemberByReceptionist(authentication.getName(), request);
        return ResponseEntity.status(HttpStatus.CREATED).body(response);
    }

    // API 5: PATCH /api/receptionist/class-registrations/{registrationId}/cancel - Lễ tân hủy hộ
    @PatchMapping("/receptionist/class-registrations/{registrationId}/cancel")
    public ResponseEntity<ClassRegistrationResponse> cancelRegistrationByReceptionist(
            @PathVariable Long registrationId) {

        return ResponseEntity.ok(onlinePaymentService
                .cancelRegistrationByReceptionist(registrationId));
    }

    // API 5b: POST /api/receptionist/class-registrations/{registrationId}/cash-payment - Lễ tân / Manager thu tiền mặt
    @PostMapping("/receptionist/class-registrations/{registrationId}/cash-payment")
    public ResponseEntity<PaymentResponse> payCash(
            Authentication authentication,
            @PathVariable Long registrationId) {

        return ResponseEntity.ok(onlinePaymentService
                .payCashForRegistration(registrationId, authentication.getName()));
    }

    // API 6: GET /api/receptionist/members/{memberId}/class-registrations - Lễ tân xem lịch sử đăng ký lớp
    @GetMapping("/receptionist/members/{memberId}/class-registrations")
    public ResponseEntity<List<ClassRegistrationResponse>> getRegistrationsByMemberId(
            @PathVariable Long memberId) {

        return ResponseEntity.ok(classRegistrationService.getRegistrationsByMemberId(memberId));
    }
}