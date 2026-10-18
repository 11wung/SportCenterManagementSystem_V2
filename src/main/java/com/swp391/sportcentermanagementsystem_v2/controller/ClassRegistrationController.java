package com.swp391.sportcentermanagementsystem_v2.controller;

import com.swp391.sportcentermanagementsystem_v2.dto.requestDTO.ClassRegistrationRequest;
import com.swp391.sportcentermanagementsystem_v2.dto.responseDTO.ClassRegistrationResponse;
import com.swp391.sportcentermanagementsystem_v2.service.ClassRegistrationService;
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

    // ====================== MEMBER ======================

    // API 1: POST /api/member/class-registrations/{classId} - Hội viên tự đăng ký lớp
    @PostMapping("/member/class-registrations/{classId}")
    public ResponseEntity<ClassRegistrationResponse> registerMyself(
            Authentication authentication,
            @PathVariable Long classId) {

        ClassRegistrationResponse response = classRegistrationService
                .registerMyself(authentication.getName(), classId);
        return ResponseEntity.status(HttpStatus.CREATED).body(response);
    }

    // API 2: PATCH /api/member/class-registrations/{registrationId}/cancel - Hội viên hủy đăng ký
    @PatchMapping("/member/class-registrations/{registrationId}/cancel")
    public ResponseEntity<ClassRegistrationResponse> cancelMyRegistration(
            Authentication authentication,
            @PathVariable Long registrationId) {

        return ResponseEntity.ok(
                classRegistrationService.cancelMyRegistration(authentication.getName(), registrationId));
    }

    // API 3: GET /api/member/class-registrations/me - Hội viên xem các lớp đã đăng ký
    @GetMapping("/member/class-registrations/me")
    public ResponseEntity<List<ClassRegistrationResponse>> getMyRegistrations(Authentication authentication) {
        return ResponseEntity.ok(classRegistrationService.getMyRegistrations(authentication.getName()));
    }

    // ====================== RECEPTIONIST ======================

    // API 4: POST /api/receptionist/class-registrations - Lễ tân đăng ký lớp hộ hội viên
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

        return ResponseEntity.ok(classRegistrationService.cancelRegistrationByReceptionist(registrationId));
    }

    // API 6: GET /api/receptionist/members/{memberId}/class-registrations - Lễ tân xem lịch sử đăng ký lớp
    @GetMapping("/receptionist/members/{memberId}/class-registrations")
    public ResponseEntity<List<ClassRegistrationResponse>> getRegistrationsByMemberId(
            @PathVariable Long memberId) {

        return ResponseEntity.ok(classRegistrationService.getRegistrationsByMemberId(memberId));
    }
}