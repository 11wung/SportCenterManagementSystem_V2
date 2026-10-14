package com.swp391.sportcentermanagementsystem_v2.controller;


import com.swp391.sportcentermanagementsystem_v2.dto.requestDTO.LoginRequest;
import com.swp391.sportcentermanagementsystem_v2.dto.requestDTO.RegisterRequest;
import com.swp391.sportcentermanagementsystem_v2.dto.responseDTO.LoginResponse;
import com.swp391.sportcentermanagementsystem_v2.dto.responseDTO.RegisterResponse;
import com.swp391.sportcentermanagementsystem_v2.service.AuthService;
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

    @PostMapping("/register")
    public ResponseEntity<RegisterResponse> register(@Valid @RequestBody RegisterRequest registerRequest) {
        return ResponseEntity.status(HttpStatus.CREATED).body(authService.register(registerRequest));
    }

    @PostMapping("/login")
    public ResponseEntity<LoginResponse> login(@Valid @RequestBody LoginRequest loginRequest) {
        return ResponseEntity.status(HttpStatus.OK).body(authService.login(loginRequest));
    }

}
