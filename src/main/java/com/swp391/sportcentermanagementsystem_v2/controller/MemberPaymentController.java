package com.swp391.sportcentermanagementsystem_v2.controller;

import com.swp391.sportcentermanagementsystem_v2.dto.responseDTO.InvoiceResponse;
import com.swp391.sportcentermanagementsystem_v2.dto.responseDTO.PaymentResponse;
import com.swp391.sportcentermanagementsystem_v2.service.PaymentService;

import lombok.RequiredArgsConstructor;

import org.springframework.http.ResponseEntity;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.*;

import java.util.List;

@RestController
@RequestMapping("/api/member/payments")
@RequiredArgsConstructor
public class MemberPaymentController {

    private final PaymentService paymentService;

    // API 1: GET /api/member/payments/me
    // Member xem các khoản đã thanh toán của chính mình.
    @GetMapping("/me")
    public ResponseEntity<List<PaymentResponse>> getMyPayments(
            Authentication authentication) {

        return ResponseEntity.ok(
                paymentService.getMyPayments(authentication.getName()));
    }

    // API 2: GET /api/member/payments/{id}/invoice
    // Member xem hóa đơn của chính mình (của người khác trả 404).
    @GetMapping("/{id}/invoice")
    public ResponseEntity<InvoiceResponse> getMyInvoice(
            Authentication authentication,
            @PathVariable Long id) {

        return ResponseEntity.ok(
                paymentService.getMyInvoice(id, authentication.getName()));
    }
}
