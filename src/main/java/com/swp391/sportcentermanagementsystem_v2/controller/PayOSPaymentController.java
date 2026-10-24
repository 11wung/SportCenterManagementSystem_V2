
package com.swp391.sportcentermanagementsystem_v2.controller;

import com.swp391.sportcentermanagementsystem_v2.service.OnlinePaymentService;
import com.swp391.sportcentermanagementsystem_v2.service.PayOSGatewayClient;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import vn.payos.exception.WebhookException;
import vn.payos.model.webhooks.Webhook;

@Slf4j
@RestController
@RequestMapping("/api/payments/payos")
@RequiredArgsConstructor
public class PayOSPaymentController {

    private final PayOSGatewayClient gatewayClient;
    private final OnlinePaymentService onlinePaymentService;

    // PayOS gọi khi có giao dịch. Route công khai nên bảo mật nằm ở chữ ký.
    // Chữ ký sai: 400. Lỗi xử lý: để ném lỗi (500) cho PayOS gọi lại.
    @PostMapping("/webhook")
    public ResponseEntity<Void> webhook(@RequestBody Webhook body) {

        Long orderCode;

        try {
            orderCode = gatewayClient.verifyWebhook(body);
        } catch (WebhookException ex) {
            log.warn("Rejected PayOS webhook: invalid signature");
            return ResponseEntity.badRequest().build();
        }

        onlinePaymentService.confirmFromWebhook(orderCode);

        return ResponseEntity.ok().build();
    }

    // Người dùng quay về sau khi thanh toán trên PayOS.
    // Không tự động xác nhận Payment SUCCESS.
    @GetMapping("/return")
    public ResponseEntity<String> paymentReturn() {

        return ResponseEntity.ok(
                "Payment return received. Please check your membership status."
        );
    }

    // Người dùng quay về sau khi hủy trên PayOS. Query string không được tin:
    // service hỏi lại PayOS rồi mới đóng payment và nhả chỗ.
    @GetMapping("/cancel")
    public ResponseEntity<String> paymentCancel(@RequestParam(required = false) Long orderCode) {

        onlinePaymentService.handleCancelReturn(orderCode);

        return ResponseEntity.ok(
                "Payment cancellation requested. Please check payment status."
        );
    }
}
