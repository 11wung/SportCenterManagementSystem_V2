package com.swp391.sportcentermanagementsystem_v2.controller;

import com.swp391.sportcentermanagementsystem_v2.dto.requestDTO.RegisterRequest;
import com.swp391.sportcentermanagementsystem_v2.dto.requestDTO.VerifyMemberEmailRequest;
import com.swp391.sportcentermanagementsystem_v2.dto.responseDTO.RegisterResponse;
import com.swp391.sportcentermanagementsystem_v2.dto.responseDTO.UserProfileResponse;
import com.swp391.sportcentermanagementsystem_v2.dto.responseDTO.UserSummaryResponse;
import com.swp391.sportcentermanagementsystem_v2.service.ReceptionistMemberService;

import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.*;

import java.util.List;

@RestController
@RequestMapping("/api/receptionist/members")
@RequiredArgsConstructor
public class ReceptionistMemberController {

    private final ReceptionistMemberService receptionistMemberService;

    @GetMapping
    public ResponseEntity<List<UserSummaryResponse>> searchMembers(
            @RequestParam(defaultValue = "") String name) {

        List<UserSummaryResponse> responses =
                receptionistMemberService.searchMembers(name.trim());

        return ResponseEntity.ok(responses);
    }

    @GetMapping("/{id}")
    public ResponseEntity<UserProfileResponse> getMemberById(
            @PathVariable Long id) {

        UserProfileResponse response =
                receptionistMemberService.getMemberById(id);

        return ResponseEntity.ok(response);
    }

    @PostMapping
    public ResponseEntity<RegisterResponse> createMemberAtCounter(
            @Valid @RequestBody RegisterRequest request) {

        RegisterResponse response =
                receptionistMemberService.createMemberAtCounter(request);

        return ResponseEntity.status(HttpStatus.CREATED).body(response);
    }

    // Lễ tân nhập mã 6 số mà hội viên đọc từ hộp thư ngay tại quầy
    @PostMapping("/{id}/verify-email")
    public ResponseEntity<UserSummaryResponse> verifyMemberEmail(
            Authentication authentication,
            @PathVariable Long id,
            @Valid @RequestBody VerifyMemberEmailRequest request) {

        return ResponseEntity.ok(
                receptionistMemberService.verifyMemberEmail(id, request.getCode(), authentication.getName()));
    }

    // Gửi lại mã khi hội viên chưa nhận được mail
    @PostMapping("/{id}/resend-code")
    public ResponseEntity<Void> resendMemberCode(@PathVariable Long id) {

        receptionistMemberService.resendMemberCode(id);

        return ResponseEntity.noContent().build();
    }
}