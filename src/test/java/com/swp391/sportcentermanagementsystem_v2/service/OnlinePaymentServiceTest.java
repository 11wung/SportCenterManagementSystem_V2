package com.swp391.sportcentermanagementsystem_v2.service;

import com.swp391.sportcentermanagementsystem_v2.dto.responseDTO.OnlinePaymentLinkResponse;
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

import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.any;
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
                paymentId, 3L, paymentId, AMOUNT, expiresAt, "CREATING", null, isNew);
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
}
