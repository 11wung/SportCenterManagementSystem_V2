package com.swp391.sportcentermanagementsystem_v2.service;

import com.swp391.sportcentermanagementsystem_v2.dto.responseDTO.OnlinePaymentLinkResponse;
import com.swp391.sportcentermanagementsystem_v2.dto.responseDTO.PaymentResponse;
import com.swp391.sportcentermanagementsystem_v2.entity.Payment;
import com.swp391.sportcentermanagementsystem_v2.entity.User;
import com.swp391.sportcentermanagementsystem_v2.exception.BusinessRuleException;
import com.swp391.sportcentermanagementsystem_v2.repository.PaymentRepository;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.math.BigDecimal;
import java.time.OffsetDateTime;
import java.time.ZoneId;
import java.util.List;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class OnlinePaymentServiceTest {

    private static final ZoneId VN = ZoneId.of("Asia/Ho_Chi_Minh");
    private static final String EMAIL = "member@test.com";
    private static final BigDecimal AMOUNT = new BigDecimal("2000");

    @Mock
    PaymentService paymentService;
    @Mock
    PayOSGatewayClient gatewayClient;
    @Mock
    PaymentRepository paymentRepository;

    @InjectMocks
    OnlinePaymentService onlinePaymentService;

    private PaymentService.OnlineReservation creating(
            long paymentId, OffsetDateTime expiresAt, boolean isNew) {

        return new PaymentService.OnlineReservation(
                paymentId, 3L, null, paymentId, AMOUNT, expiresAt, "CREATING", null, isNew);
    }

    @Test
    void expiredCreatingPaymentThatPayOSDoesNotKnowIsFailedAndRetried() {
        // Lần trước PayOS lỗi lúc tạo link: payment kẹt CREATING, quá hạn QR.
        OffsetDateTime now = OffsetDateTime.now(VN);
        when(paymentService.reserveOnlinePayment(3L, EMAIL)).thenReturn(
                creating(4L, now.minusMinutes(1), false),
                creating(5L, now.plusMinutes(15), true));
        when(gatewayClient.lookupLink(4L))
                .thenReturn(new PayOSGatewayClient.LinkInfo("NOT_FOUND", null, null, null));
        when(gatewayClient.createLink(eq(5L), any(), any()))
                .thenReturn(new PayOSGatewayClient.LinkInfo("PENDING", "https://pay.payos.vn/x", AMOUNT, null));

        OnlinePaymentLinkResponse expected = new OnlinePaymentLinkResponse();
        when(paymentService.confirmPaymentLinkCreated(5L, 5L, "https://pay.payos.vn/x"))
                .thenReturn(expected);

        OnlinePaymentLinkResponse result = onlinePaymentService.startOrReusePayment(3L, EMAIL);

        assertSame(expected, result);
        verify(paymentService).markLinkRejected(4L, 4L);
    }

    @Test
    void creatingPaymentNotYetExpiredIsNotTouched() {
        // Chưa quá hạn: có thể một yêu cầu khác còn đang tạo link dở.
        OffsetDateTime now = OffsetDateTime.now(VN);
        when(paymentService.reserveOnlinePayment(3L, EMAIL))
                .thenReturn(creating(4L, now.plusMinutes(5), false));
        when(gatewayClient.lookupLink(4L))
                .thenReturn(new PayOSGatewayClient.LinkInfo("NOT_FOUND", null, null, null));

        assertThrows(BusinessRuleException.class,
                () -> onlinePaymentService.startOrReusePayment(3L, EMAIL));

        verify(paymentService, never()).markLinkRejected(anyLong(), anyLong());
    }

    // ---------- Member checks a payment (checkPayment) ----------

    private Payment paymentOf(String payerEmail, String status) {
        User payer = new User();
        payer.setEmail(payerEmail);

        Payment payment = new Payment();
        payment.setId(7L);
        payment.setPayer(payer);
        payment.setStatus(status);
        payment.setGatewayOrderCode(7L);

        when(paymentRepository.findFirstByOrder_Subscription_IdAndPaymentMethodAndStatusInOrderByIdDesc(
                eq(5L), eq("QR_GATEWAY"), anyList())).thenReturn(Optional.of(payment));
        return payment;
    }

    @Test
    void checkPaymentOfAnotherMemberIsRejected() {
        paymentOf("someone-else@test.com", "PENDING");

        assertThrows(BusinessRuleException.class,
                () -> onlinePaymentService.checkPayment(5L, EMAIL));

        verify(gatewayClient, never()).lookupLink(anyLong());
    }

    @Test
    void checkPaymentWithoutAnOnlinePaymentIsRejected() {
        when(paymentRepository.findFirstByOrder_Subscription_IdAndPaymentMethodAndStatusInOrderByIdDesc(
                eq(5L), eq("QR_GATEWAY"), anyList())).thenReturn(Optional.empty());

        assertThrows(BusinessRuleException.class,
                () -> onlinePaymentService.checkPayment(5L, EMAIL));
    }

    @Test
    void checkPaymentOfAlreadySuccessfulPaymentDoesNotAskPayOS() {
        paymentOf(EMAIL, "SUCCESS");
        PaymentResponse expected = new PaymentResponse();
        when(paymentService.getPaymentResponse(7L)).thenReturn(expected);

        PaymentResponse result = onlinePaymentService.checkPayment(5L, EMAIL);

        assertSame(expected, result);
        verify(gatewayClient, never()).lookupLink(anyLong());
    }

    @Test
    void checkPaymentConfirmsAPaymentPayOSSaysIsPaid() {
        paymentOf(EMAIL, "PENDING");
        OffsetDateTime paidAt = OffsetDateTime.now(VN);
        when(gatewayClient.lookupLink(7L))
                .thenReturn(new PayOSGatewayClient.LinkInfo("PAID", null, AMOUNT, paidAt));
        PaymentResponse expected = new PaymentResponse();
        when(paymentService.getPaymentResponse(7L)).thenReturn(expected);

        PaymentResponse result = onlinePaymentService.checkPayment(5L, EMAIL);

        assertSame(expected, result);
        verify(paymentService).confirmVerifiedOnlinePayment(7L, 7L, AMOUNT, paidAt);
    }

    // ---------- Reconcile expired payments (scheduler) ----------

    private void stubExpiredPayment(long id, String status) {
        Payment payment = new Payment();
        payment.setId(id);
        payment.setStatus(status);
        payment.setGatewayOrderCode(id);

        when(paymentRepository.findExpiredOnlineIds(any(), any())).thenReturn(List.of(id));
        when(paymentRepository.findById(id)).thenReturn(Optional.of(payment));
    }

    @Test
    void expiredPaymentPaidAtPayOSIsConfirmed() {
        // Webhook bị rớt: PayOS đã nhận tiền nhưng app chưa biết.
        OffsetDateTime paidAt = OffsetDateTime.now(VN).minusMinutes(20);
        stubExpiredPayment(4L, "PENDING");
        when(gatewayClient.lookupLink(4L))
                .thenReturn(new PayOSGatewayClient.LinkInfo("PAID", null, AMOUNT, paidAt));

        onlinePaymentService.reconcileExpiredPayments();

        verify(paymentService).confirmVerifiedOnlinePayment(4L, 4L, AMOUNT, paidAt);
    }

    @Test
    void expiredPaymentCanceledAtPayOSIsClosed() {
        // Member bỏ dở QR: đóng payment để đơn hết bị chặn hủy/thu tiền mặt.
        stubExpiredPayment(4L, "PENDING");
        when(gatewayClient.lookupLink(4L))
                .thenReturn(new PayOSGatewayClient.LinkInfo("EXPIRED", null, AMOUNT, null));

        onlinePaymentService.reconcileExpiredPayments();

        verify(paymentService).markLinkCanceled(4L, 4L);
    }

    @Test
    void creatingPaymentUnknownToPayOSIsFailed() {
        stubExpiredPayment(4L, "CREATING");
        when(gatewayClient.lookupLink(4L))
                .thenReturn(new PayOSGatewayClient.LinkInfo("NOT_FOUND", null, null, null));

        onlinePaymentService.reconcileExpiredPayments();

        verify(paymentService).markLinkRejected(4L, 4L);
        verify(paymentService, never()).markLinkCanceled(anyLong(), anyLong());
    }

    @Test
    void payOSErrorLeavesPaymentUntouched() {
        // Lỗi mạng không chứng minh gì: không đóng nhầm, lần sau thử lại.
        stubExpiredPayment(4L, "PENDING");
        when(gatewayClient.lookupLink(4L)).thenThrow(new IllegalStateException("network down"));

        assertDoesNotThrow(() -> onlinePaymentService.reconcileExpiredPayments());

        verify(paymentService, never()).markLinkCanceled(anyLong(), anyLong());
        verify(paymentService, never()).markLinkRejected(anyLong(), anyLong());
        verify(paymentService, never()).confirmVerifiedOnlinePayment(anyLong(), anyLong(), any(), any());
    }
}
