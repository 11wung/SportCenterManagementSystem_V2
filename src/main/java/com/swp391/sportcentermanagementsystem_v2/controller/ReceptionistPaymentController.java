package com.swp391.sportcentermanagementsystem_v2.controller;

import com.swp391.sportcentermanagementsystem_v2.dto.requestDTO.CashPaymentRequest;
import com.swp391.sportcentermanagementsystem_v2.dto.responseDTO.PaymentResponse;
import com.swp391.sportcentermanagementsystem_v2.service.PaymentService;

import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;

import org.springframework.http.ResponseEntity;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping("/api/receptionist/payments")
@RequiredArgsConstructor
public class ReceptionistPaymentController {

    private final PaymentService paymentService;

    @PostMapping("/cash")
    public ResponseEntity<PaymentResponse> payCash(
            @Valid @RequestBody CashPaymentRequest request,
            Authentication authentication
    ) {

        String receptionistEmail = authentication.getName();

        PaymentResponse response =
                paymentService.payCash(request, receptionistEmail);

        return ResponseEntity.ok(response);
    }
}

