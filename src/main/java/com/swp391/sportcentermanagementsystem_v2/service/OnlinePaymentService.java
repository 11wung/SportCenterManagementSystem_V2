
package com.swp391.sportcentermanagementsystem_v2.service;

import com.swp391.sportcentermanagementsystem_v2.dto.responseDTO.OnlinePaymentLinkResponse;
import com.swp391.sportcentermanagementsystem_v2.dto.responseDTO.PaymentResponse;
import com.swp391.sportcentermanagementsystem_v2.entity.Payment;
import com.swp391.sportcentermanagementsystem_v2.exception.BusinessRuleException;
import com.swp391.sportcentermanagementsystem_v2.repository.PaymentRepository;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.time.OffsetDateTime;
import java.time.ZoneId;
import java.util.List;

@Slf4j
@Service
@RequiredArgsConstructor
public class OnlinePaymentService {

        private final PaymentService paymentService;
        private final PayOSGatewayClient gatewayClient;
    private final PaymentRepository paymentRepository;

        private static final ZoneId VIETNAM_ZONE = ZoneId.of("Asia/Ho_Chi_Minh");

        // Thu tiền mặt: đóng QR PayOS còn mở trước, rồi mới ghi nhận tiền mặt.
        // Không dùng @Transactional vì có gọi PayOS.
        public PaymentResponse payCash(Long subscriptionId, String cashierEmail) {

                paymentRepository
                                .findFirstByOrder_Subscription_IdAndPaymentMethodAndStatusInOrderByIdDesc(
                                                subscriptionId,
                                                "QR_GATEWAY",
                                                List.of("CREATING", "PENDING"))
                                .ifPresent(open -> {
                                        try {
                                                reconcileExpiredPayment(open.getId());
                                        } catch (RuntimeException ex) {
                                                log.warn("Cannot close QR payment {}: {}",
                                                                open.getId(), ex.toString());
                                                throw new BusinessRuleException(
                                                                "Cannot confirm online payment state. "
                                                                                + "Try again shortly!");
                                        }
                                });

                return paymentService.payCash(subscriptionId, cashierEmail);
        }

        // Không dùng @Transactional ở đây.
        // HTTP request tới PayOS không nên nằm trong DB transaction.
        public OnlinePaymentLinkResponse startOrReusePayment(
                        Long subscriptionId,
                        String memberEmail) {

                PaymentService.OnlineReservation reservation = paymentService.reserveOnlinePayment(
                                subscriptionId,
                                memberEmail);

                // 1. Payment mới: yêu cầu PayOS tạo Payment Link.
                if (reservation.newReservation()) {
                        return createLink(reservation);
                }

                OffsetDateTime now = OffsetDateTime.now(VIETNAM_ZONE);

                // 2. Payment PENDING có checkoutUrl hợp lệ
                // và chưa hết hạn: trả lại QR cũ.
                if ("PENDING".equals(reservation.status())
                                && isValidCheckoutUrl(reservation.checkoutUrl())
                                && reservation.expiresAt() != null
                                && now.isBefore(reservation.expiresAt())) {

                        return response(reservation);
                }

                // 3. Payment cũ không rõ kết quả.
                // Luôn tra cứu orderCode đã tạo.
                PayOSGatewayClient.LinkInfo current;

                try {
                        current = gatewayClient.lookupLink(
                                        reservation.orderCode());
                } catch (RuntimeException ex) {
                        log.warn("Cannot look up PayOS orderCode={}: {}",
                                        reservation.orderCode(), ex.toString());
                        throw new BusinessRuleException(
                                        "Cannot verify previous PayOS transaction. "
                                                        + "Please retry later!");
                }

                if (current == null || current.status() == null) {
                        throw new BusinessRuleException(
                                        "Cannot verify previous PayOS payment!");
                }

                // 4. PayOS xác nhận đã nhận tiền.
                if ("PAID".equals(current.status())) {

                        if (current.amount() == null
                                        || current.paidAt() == null) {

                                throw new BusinessRuleException(
                                                "PayOS payment details are incomplete. "
                                                                + "Reconciliation required!");
                        }

                        paymentService.confirmVerifiedOnlinePayment(
                                        reservation.paymentId(),
                                        reservation.orderCode(),
                                        current.amount(),
                                        current.paidAt());

                        // Không trả QR của giao dịch đã thanh toán.
                        throw new BusinessRuleException(
                                        "Payment has been confirmed. "
                                                        + "Please check your membership!");
                }

                // 5. PayOS xác nhận giao dịch vẫn PENDING.
                if ("PENDING".equals(current.status())) {

                        if (current.amount() == null
                                        || current.amount().compareTo(
                                                        reservation.amount()) != 0) {

                                throw new BusinessRuleException(
                                                "PayOS amount mismatch. "
                                                                + "Reconciliation required!");
                        }

                        OffsetDateTime expiresAt = reservation.expiresAt();

                        // 5.1. Giao dịch chưa hết hạn.
                        if (expiresAt != null
                                        && OffsetDateTime.now(VIETNAM_ZONE)
                                                        .isBefore(expiresAt)) {

                                // API lookup PayOS không trả checkoutUrl.
                                // Chỉ dùng URL đã lưu trong database.
                                if (isValidCheckoutUrl(
                                                reservation.checkoutUrl())) {

                                        return paymentService.confirmPaymentLinkCreated(
                                                        reservation.paymentId(),
                                                        reservation.orderCode(),
                                                        reservation.checkoutUrl());
                                }

                                // Trường hợp PayOS tạo Link thành công
                                // nhưng backend chưa kịp lưu checkoutUrl.
                                // Không tạo orderCode mới.
                                throw new BusinessRuleException(
                                                "PayOS transaction exists but checkout URL "
                                                                + "was not saved. Reconciliation required!");
                        }

                        // 5.2. Giao dịch đã hết hạn tại backend.
                        // Cần hủy trên PayOS trước khi tạo lại.
                        PayOSGatewayClient.LinkInfo canceled;

                        try {
                                canceled = gatewayClient.cancelLink(
                                                reservation.orderCode());
                        } catch (RuntimeException ex) {
                                throw new BusinessRuleException(
                                                "Cannot confirm PayOS cancellation. "
                                                                + "Reconciliation required!");
                        }

                        if (canceled == null
                                        || !"CANCELED".equals(canceled.status())) {

                                throw new BusinessRuleException(
                                                "Cannot confirm PayOS cancellation. "
                                                                + "Reconciliation required!");
                        }

                        paymentService.markLinkCanceled(
                                        reservation.paymentId(),
                                        reservation.orderCode());

                        return startNewAfterConfirmedCancellation(
                                        subscriptionId,
                                        memberEmail);
                }

                // 6. PayOS xác nhận giao dịch đã hủy/hết hạn.
                if ("CANCELED".equals(current.status())
                                || "EXPIRED".equals(current.status())) {

                        paymentService.markLinkCanceled(
                                        reservation.paymentId(),
                                        reservation.orderCode());

                        return startNewAfterConfirmedCancellation(
                                        subscriptionId,
                                        memberEmail);
                }

                // 7. PayOS chắc chắn không có giao dịch này (link chưa từng
                // được tạo). Chỉ gỡ kẹt khi QR đã quá hạn, để không tạo hai
                // link cho một đơn nếu một yêu cầu khác còn đang tạo dở.
                if ("NOT_FOUND".equals(current.status())
                                && reservation.expiresAt() != null
                                && !OffsetDateTime.now(VIETNAM_ZONE)
                                                .isBefore(reservation.expiresAt())) {

                        paymentService.markLinkRejected(
                                        reservation.paymentId(),
                                        reservation.orderCode());

                        return startNewAfterConfirmedCancellation(
                                        subscriptionId,
                                        memberEmail);
                }

                // 8. Trạng thái khác, hoặc NOT_FOUND nhưng chưa quá hạn.
                // Không đủ thông tin để tạo giao dịch mới.
                throw new BusinessRuleException(
                                "Previous PayOS transaction is unresolved. "
                                                + "Please retry later!");
        }

        // ========================================================
        // CONFIRM PAYMENT FROM WEBHOOK
        // ========================================================

        // Webhook chỉ là tín hiệu: số tiền và giờ trả luôn được lấy lại từ PayOS.
        public void confirmFromWebhook(Long orderCode) {

                // orderCode lạ (vd. webhook thử của PayOS): bỏ qua.
                Payment payment = paymentRepository
                                .findByGatewayOrderCode(orderCode)
                                .orElse(null);

                if (payment != null) {
                        syncWithPayOS(payment);
                }
        }

        // ========================================================
        // RECONCILE EXPIRED PAYMENTS (SCHEDULER)
        // ========================================================

        // Đóng các payment QR đã quá hạn mà vẫn CREATING/PENDING (member bỏ dở QR,
        // webhook bị rớt, lỗi lúc tạo link). Hỏi PayOS ngoài transaction DB.
        //
        // shortcut: chỉ xét payment quá hạn trong 7 ngày gần nhất để một payment
        // hỏng không bị thử lại mãi; quá hạn đó thì xử lý tay.
        public void reconcileExpiredPayments() {

                OffsetDateTime now = OffsetDateTime.now(VIETNAM_ZONE);

                // Chừa 1 phút sau hạn QR cho các yêu cầu đang chạy dở.
                List<Long> paymentIds = paymentRepository.findExpiredOnlineIds(
                                now.minusDays(7),
                                now.minusMinutes(1));

                for (Long paymentId : paymentIds) {

                        try {
                                reconcileExpiredPayment(paymentId);
                        } catch (RuntimeException ex) {
                                // Lỗi mạng/PayOS: 5 phút sau thử lại.
                                log.warn("Cannot reconcile payment {}: {}",
                                                paymentId, ex.toString());
                        }
                }
        }

        private void reconcileExpiredPayment(Long paymentId) {

                Payment payment = paymentRepository.findById(paymentId).orElse(null);

                if (payment == null || payment.getGatewayOrderCode() == null) {
                        return;
                }

                Long orderCode = payment.getGatewayOrderCode();

                PayOSGatewayClient.LinkInfo current = gatewayClient.lookupLink(orderCode);
                String status = current.status();

                if ("PAID".equals(status)) {

                        paymentService.confirmVerifiedOnlinePayment(
                                        paymentId,
                                        orderCode,
                                        current.amount(),
                                        current.paidAt());

                } else if ("CANCELED".equals(status) || "EXPIRED".equals(status)) {

                        paymentService.markLinkCanceled(paymentId, orderCode);

                } else if ("NOT_FOUND".equals(status)) {

                        // Link chưa từng được tạo: CREATING thì FAILED.
                        if ("CREATING".equals(payment.getStatus())) {
                                paymentService.markLinkRejected(paymentId, orderCode);
                        } else {
                                paymentService.markLinkCanceled(paymentId, orderCode);
                        }

                } else if ("PENDING".equals(status)) {

                        // Quá hạn mà PayOS còn PENDING: hủy rồi chỉ đóng khi PayOS xác nhận.
                        PayOSGatewayClient.LinkInfo canceled = gatewayClient.cancelLink(orderCode);

                        if ("CANCELED".equals(canceled.status())) {
                                paymentService.markLinkCanceled(paymentId, orderCode);
                        }
                }
        }

        // ========================================================
        // CHECK PAYMENT (MEMBER)
        // ========================================================

        // Member bấm kiểm tra (vd. ở trang return): hỏi PayOS ngay,
        // đã trả thì kích hoạt gói, không cần chờ webhook hay QR hết hạn.
        public PaymentResponse checkPayment(Long subscriptionId, String memberEmail) {

                Payment payment = paymentRepository
                                .findFirstByOrder_Subscription_IdAndPaymentMethodAndStatusInOrderByIdDesc(
                                                subscriptionId,
                                                "QR_GATEWAY",
                                                List.of("CREATING", "PENDING", "SUCCESS"))
                                .orElseThrow(() -> new BusinessRuleException(
                                                "No online payment found for this subscription!"));

                if (!memberEmail.equals(payment.getPayer().getEmail())) {
                        throw new BusinessRuleException(
                                        "Subscription does not belong to this Member!");
                }

                if (!"SUCCESS".equals(payment.getStatus())) {
                        syncWithPayOS(payment);
                }

                return paymentService.getPaymentResponse(payment.getId());
        }

        // Hỏi PayOS, nếu đã trả thì ghi nhận và kích hoạt gói.
        private void syncWithPayOS(Payment payment) {

                Long orderCode = payment.getGatewayOrderCode();

                PayOSGatewayClient.LinkInfo current;

                try {
                        current = gatewayClient.lookupLink(orderCode);
                } catch (RuntimeException ex) {
                        log.warn("Cannot look up PayOS orderCode={}: {}", orderCode, ex.toString());
                        throw new BusinessRuleException(
                                        "Cannot verify the payment with PayOS. "
                                                        + "Please retry later!");
                }

                if ("PAID".equals(current.status())) {
                        paymentService.confirmVerifiedOnlinePayment(
                                        payment.getId(),
                                        orderCode,
                                        current.amount(),
                                        current.paidAt());
                }
        }

        // ========================================================
        // CREATE NEW PAYMENT AFTER CONFIRMED CANCELLATION
        // ========================================================

        private OnlinePaymentLinkResponse startNewAfterConfirmedCancellation(
                        Long subscriptionId,
                        String memberEmail) {

                PaymentService.OnlineReservation next = paymentService.reserveOnlinePayment(
                                subscriptionId,
                                memberEmail);

                if (next.newReservation()) {
                        return createLink(next);
                }

                throw new BusinessRuleException(
                                "Another payment request is being processed. "
                                                + "Please retry shortly!");
        }

        // ========================================================
        // CREATE PAYMENT LINK
        // ========================================================

        private OnlinePaymentLinkResponse createLink(
                        PaymentService.OnlineReservation reservation) {

                PayOSGatewayClient.LinkInfo link;

                try {

                        link = gatewayClient.createLink(
                                        reservation.orderCode(),
                                        reservation.amount(),
                                        reservation.expiresAt());

                } catch (RuntimeException ex) {

                        // Timeout/network error chưa chứng minh
                        // PayOS không tạo Payment Link.
                        // Giữ CREATING; quá hạn QR thì member bấm lại để gỡ kẹt.
                        log.warn("PayOS createLink failed for orderCode={}: {}",
                                        reservation.orderCode(), ex.toString());
                        throw new BusinessRuleException(
                                        "PayOS response is uncertain. "
                                                        + "Reconciliation required!");
                }

                if (link == null
                                || !"PENDING".equals(link.status())
                                || link.amount() == null
                                || link.amount().compareTo(
                                                reservation.amount()) != 0
                                || !isValidCheckoutUrl(link.checkoutUrl())) {

                        throw new BusinessRuleException(
                                        "Unexpected PayOS response. "
                                                        + "Reconciliation required!");
                }

                return paymentService.confirmPaymentLinkCreated(
                                reservation.paymentId(),
                                reservation.orderCode(),
                                link.checkoutUrl());
        }

        // ========================================================
        // CHECK URL
        // ========================================================

        private boolean isValidCheckoutUrl(String checkoutUrl) {

                return checkoutUrl != null
                                && !checkoutUrl.isBlank()
                                && checkoutUrl.startsWith("https://");
        }

        // ========================================================
        // MAP RESPONSE
        // ========================================================

        private OnlinePaymentLinkResponse response(
                        PaymentService.OnlineReservation reservation) {

                OnlinePaymentLinkResponse response = new OnlinePaymentLinkResponse();

                response.setPaymentId(reservation.paymentId());
                response.setSubscriptionId(reservation.subscriptionId());
                response.setOrderCode(reservation.orderCode());
                response.setCheckoutUrl(reservation.checkoutUrl());
                response.setExpiresAt(reservation.expiresAt());
                response.setPaymentStatus(reservation.status());

                return response;
        }
}
