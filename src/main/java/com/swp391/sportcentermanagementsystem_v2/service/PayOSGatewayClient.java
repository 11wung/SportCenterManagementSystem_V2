
package com.swp391.sportcentermanagementsystem_v2.service;

import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import vn.payos.PayOS;
import vn.payos.exception.APIException;
import vn.payos.model.v2.paymentRequests.CreatePaymentLinkRequest;
import vn.payos.model.v2.paymentRequests.CreatePaymentLinkResponse;
import vn.payos.model.v2.paymentRequests.PaymentLink;
import vn.payos.model.v2.paymentRequests.Transaction;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.time.OffsetDateTime;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.time.format.DateTimeParseException;
import java.util.List;

@Slf4j
@Component
public class PayOSGatewayClient {

    private static final ZoneId VIETNAM_ZONE = ZoneId.of("Asia/Ho_Chi_Minh");

    private static final String PAYOS_ORDER_NOT_FOUND = "101";

    private static final DateTimeFormatter PAYOS_DATE_FORMAT = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss");

    // Tạo khi cần để app vẫn khởi động được khi chưa có khóa PayOS.
    private PayOS payOS;
    private final String returnUrl;
    private final String cancelUrl;

    public PayOSGatewayClient(
            @Value("${payos.return-url}") String returnUrl,
            @Value("${payos.cancel-url}") String cancelUrl) {

        this.returnUrl = returnUrl;
        this.cancelUrl = cancelUrl;
    }

    private synchronized PayOS payOS() {

        if (payOS == null) {
            try {
                payOS = PayOS.fromEnv();
            } catch (RuntimeException ex) {
                throw new IllegalStateException(
                        "PayOS is not configured: set PAYOS_CLIENT_ID, "
                                + "PAYOS_API_KEY and PAYOS_CHECKSUM_KEY",
                        ex);
            }
        }

        return payOS;
    }

    // ========================================================
    // PAYOS PAYMENT LINK INFORMATION
    // ========================================================

    public record LinkInfo(
            String status,
            String checkoutUrl,
            BigDecimal amount,
            OffsetDateTime paidAt) {
    }

    // ========================================================
    // 1. CREATE PAYMENT LINK
    // ========================================================

    public LinkInfo createLink(
            Long orderCode,
            BigDecimal amount,
            OffsetDateTime expiresAt) {

        if (orderCode == null
                || amount == null
                || expiresAt == null) {
            throw new IllegalArgumentException(
                    "Missing PayOS payment information!");
        }

        long paymentAmount = amount.longValueExact();

        if (paymentAmount <= 0) {
            throw new IllegalArgumentException(
                    "Payment amount must be greater than zero!");
        }

        if (!expiresAt.isAfter(OffsetDateTime.now())) {
            throw new IllegalArgumentException(
                    "Payment expiration time is invalid!");
        }

        CreatePaymentLinkRequest request = CreatePaymentLinkRequest.builder()
                .orderCode(orderCode)
                .amount(paymentAmount)
                .description("SCMS " + orderCode)
                .returnUrl(returnUrl)
                .cancelUrl(cancelUrl)
                .expiredAt(expiresAt.toEpochSecond())
                .build();

        CreatePaymentLinkResponse result = payOS().paymentRequests().create(request);

        if (result == null
                || !orderCode.equals(result.getOrderCode())
                || result.getAmount() == null
                || result.getAmount() != paymentAmount
                || result.getStatus() == null
                || !"PENDING".equals(result.getStatus().name())) {

            throw new IllegalStateException(
                    "Unexpected PayOS payment link information!");
        }

        return new LinkInfo(
                "PENDING",
                result.getCheckoutUrl(),
                BigDecimal.valueOf(result.getAmount()),
                null);
    }

    // ========================================================
    // 1.1. VERIFY WEBHOOK SIGNATURE
    // ========================================================

    // Trả orderCode nếu chữ ký hợp lệ, ném WebhookException nếu sai.
    public Long verifyWebhook(Object webhookBody) {
        return payOS().webhooks().verify(webhookBody).getOrderCode();
    }

    // ========================================================
    // 2. LOOKUP PAYMENT LINK
    // ========================================================

    public LinkInfo lookupLink(Long orderCode) {

        if (orderCode == null) {
            throw new IllegalArgumentException(
                    "PayOS orderCode is required!");
        }

        PaymentLink link;

        try {
            link = payOS().paymentRequests().get(orderCode);
        } catch (APIException ex) {
            // Thực tế PayOS trả HTTP 200 + errorCode 101 ("Mã thanh toán không
            // tồn tại") khi link chưa từng được tạo.
            if (PAYOS_ORDER_NOT_FOUND.equals(ex.getErrorCode().orElse(null))) {
                return new LinkInfo("NOT_FOUND", null, null, null);
            }
            throw ex;
        }

        return mapPaymentLink(link, orderCode);
    }

    // ========================================================
    // 3. CANCEL PAYMENT LINK
    // ========================================================

    public LinkInfo cancelLink(Long orderCode) {

        if (orderCode == null) {
            throw new IllegalArgumentException(
                    "PayOS orderCode is required!");
        }

        PaymentLink link = payOS().paymentRequests().cancel(
                orderCode,
                "Payment expired or canceled");

        // Không tự giả định rằng PayOS đã hủy thành công.
        return mapPaymentLink(link, orderCode);
    }

    // ========================================================
    // 4. MAP PAYOS PAYMENT LINK
    // ========================================================

    private LinkInfo mapPaymentLink(
            PaymentLink link,
            Long expectedOrderCode) {

        if (link == null
                || !expectedOrderCode.equals(link.getOrderCode())
                || link.getStatus() == null
                || link.getAmount() == null) {

            throw new IllegalStateException(
                    "Invalid PayOS payment information!");
        }

        String status = link.getStatus().name();

        // PayOS: CANCELLED
        // Hệ thống nội bộ: CANCELED
        if ("CANCELLED".equals(status)) {
            status = "CANCELED";
        }

        BigDecimal amount = BigDecimal.valueOf(link.getAmount());

        if ("PAID".equals(status)) {

            // Chỉ chấp nhận đúng số tiền cần thanh toán.
            if (link.getAmountPaid() == null
                    || link.getAmountPaid().longValue() != link.getAmount().longValue()) {

                return new LinkInfo(
                        "PAID",
                        null,
                        amount,
                        null);
            }

            OffsetDateTime paidAt = getVerifiedPaidAt(link.getTransactions(),
                    link.getAmountPaid());

            return new LinkInfo(
                    "PAID",
                    null,
                    amount,
                    paidAt);
        }

        // API tra cứu PaymentLink không cung cấp checkoutUrl.
        // Không tự dựng hoặc đoán đường dẫn.
        return new LinkInfo(
                status,
                null,
                amount,
                null);
    }

    // ========================================================
    // 5. GET PAYMENT TIME
    // ========================================================

    private OffsetDateTime getVerifiedPaidAt(
            List<Transaction> transactions,
            Long amountPaid) {

        if (transactions == null
                || transactions.size() != 1
                || amountPaid == null) {
            log.warn("PayOS paid time not verifiable: transactions={}, amountPaid={}",
                    transactions == null ? null : transactions.size(), amountPaid);
            return null;
        }

        Transaction transaction = transactions.get(0);

        if (transaction.getAmount() == null
                || !transaction.getAmount().equals(amountPaid)
                || transaction.getTransactionDateTime() == null
                || transaction.getTransactionDateTime().isBlank()) {

            log.warn("PayOS paid time not verifiable: transactionAmount={}, amountPaid={}, dateTime={}",
                    transaction.getAmount(), amountPaid, transaction.getTransactionDateTime());
            return null;
        }

        String raw = transaction.getTransactionDateTime();

        // PayOS có thể trả giờ dạng ISO có múi giờ hoặc "yyyy-MM-dd HH:mm:ss".
        try {
            return OffsetDateTime.parse(raw);
        } catch (DateTimeParseException ignored) {
            // thử dạng còn lại
        }

        try {

            return LocalDateTime.parse(raw, PAYOS_DATE_FORMAT)
                    .atZone(VIETNAM_ZONE)
                    .toOffsetDateTime();

        } catch (DateTimeParseException ex) {

            // Không tự gán thời gian hiện tại.
            // Để PaymentService yêu cầu đối soát.
            log.warn("Unrecognized PayOS transactionDateTime format: {}", raw);
            return null;
        }
    }
}
