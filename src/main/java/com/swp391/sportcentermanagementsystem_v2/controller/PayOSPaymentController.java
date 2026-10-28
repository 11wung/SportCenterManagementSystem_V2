
package com.swp391.sportcentermanagementsystem_v2.controller;

import com.swp391.sportcentermanagementsystem_v2.service.OnlinePaymentService;
import com.swp391.sportcentermanagementsystem_v2.service.PayOSGatewayClient;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.net.URI;

import vn.payos.exception.WebhookException;
import vn.payos.model.webhooks.Webhook;

@Slf4j
@RestController
@RequestMapping("/api/payments/payos")
@RequiredArgsConstructor
public class PayOSPaymentController {

    private final PayOSGatewayClient gatewayClient;
    private final OnlinePaymentService onlinePaymentService;

    @Value("${app.frontend-url}")
    private String frontendUrl;

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

    // Người dùng quay về sau khi thanh toán trên PayOS: chuyển về trang member của FE.
    // Không tự động xác nhận Payment SUCCESS, FE hỏi lại trạng thái.
    @GetMapping("/return")
    public ResponseEntity<Void> paymentReturn(@RequestParam(required = false) Long orderCode) {

        return redirectToMember("return", orderCode);
    }

    // Người dùng quay về sau khi hủy trên PayOS. Query string không được tin:
    // service hỏi lại PayOS rồi mới đóng payment và nhả chỗ, sau đó chuyển về trang member.
    @GetMapping("/cancel")
    public ResponseEntity<Void> paymentCancel(@RequestParam(required = false) Long orderCode) {

        onlinePaymentService.handleCancelReturn(orderCode);

        return redirectToMember("cancel", orderCode);
    }

    private ResponseEntity<Void> redirectToMember(String result, Long orderCode) {
        String url = frontendUrl + "/member?payment=" + result + (orderCode == null ? "" : "&orderCode=" + orderCode);
        return ResponseEntity.status(HttpStatus.FOUND).location(URI.create(url)).build();
    }
}
