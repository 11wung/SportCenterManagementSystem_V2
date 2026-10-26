package com.swp391.sportcentermanagementsystem_v2.controller;


import com.swp391.sportcentermanagementsystem_v2.dto.requestDTO.LoginRequest;
import com.swp391.sportcentermanagementsystem_v2.dto.requestDTO.RegisterRequest;
import com.swp391.sportcentermanagementsystem_v2.dto.requestDTO.ResendVerificationRequest;
import com.swp391.sportcentermanagementsystem_v2.dto.requestDTO.VerifyEmailRequest;
import com.swp391.sportcentermanagementsystem_v2.dto.responseDTO.LoginResponse;
import com.swp391.sportcentermanagementsystem_v2.dto.responseDTO.RegisterResponse;
import com.swp391.sportcentermanagementsystem_v2.service.AuthService;
import com.swp391.sportcentermanagementsystem_v2.service.EmailVerificationService;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping("/api/auth")
@RequiredArgsConstructor
public class AuthController {
    private final AuthService authService;
    private final EmailVerificationService emailVerificationService;

    @PostMapping("/register")
    public ResponseEntity<RegisterResponse> register(@Valid @RequestBody RegisterRequest registerRequest) {
        return ResponseEntity.status(HttpStatus.CREATED).body(authService.register(registerRequest));
    }

    // Nhập mã 6 số đã gửi qua email
    @PostMapping("/verify-email")
    public ResponseEntity<Void> verifyEmail(@Valid @RequestBody VerifyEmailRequest request) {
        emailVerificationService.verify(request.getEmail(), request.getCode());
        return ResponseEntity.noContent().build();
    }

    // Gửi lại mã (luôn trả 204 để không lộ email có tồn tại hay không)
    @PostMapping("/resend-verification")
    public ResponseEntity<Void> resendVerification(@Valid @RequestBody ResendVerificationRequest request) {
        emailVerificationService.resend(request.getEmail());
        return ResponseEntity.noContent().build();
    }

    @PostMapping("/login")
    public ResponseEntity<LoginResponse> login(@Valid @RequestBody LoginRequest loginRequest) {
        return ResponseEntity.status(HttpStatus.OK).body(authService.login(loginRequest));
    }

}
