package com.swp391.sportcentermanagementsystem_v2.controller;

import com.swp391.sportcentermanagementsystem_v2.dto.requestDTO.CashPaymentRequest;
import com.swp391.sportcentermanagementsystem_v2.dto.responseDTO.PaymentResponse;
import com.swp391.sportcentermanagementsystem_v2.service.PaymentService;

import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;

import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping("/api/receptionist/payments")
@RequiredArgsConstructor
public class ReceptionistPaymentController {

    private final PaymentService paymentService;

    // API 1: POST /api/receptionist/payments/cash
    // Receptionist xác nhận đã thu đủ tiền mặt.
    // Hệ thống ghi Payment SUCCESS và cấp quyền Membership.
    @PostMapping("/cash")
    public ResponseEntity<PaymentResponse> payCash(
            Authentication authentication,
            @Valid @RequestBody CashPaymentRequest request) {

        PaymentResponse response = paymentService.payCash(
                request.getSubscriptionId(),
                authentication.getName());

        return ResponseEntity.status(HttpStatus.CREATED)
                .body(response);
    }
}
