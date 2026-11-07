package com.swp391.sportcentermanagementsystem_v2.service;

import com.swp391.sportcentermanagementsystem_v2.entity.Order;
import com.swp391.sportcentermanagementsystem_v2.entity.Payment;
import com.swp391.sportcentermanagementsystem_v2.entity.Refund;
import com.swp391.sportcentermanagementsystem_v2.entity.Role;
import com.swp391.sportcentermanagementsystem_v2.entity.User;
import com.swp391.sportcentermanagementsystem_v2.exception.BusinessRuleException;
import com.swp391.sportcentermanagementsystem_v2.repository.PaymentRepository;
import com.swp391.sportcentermanagementsystem_v2.repository.RefundRepository;
import com.swp391.sportcentermanagementsystem_v2.repository.UserRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.mockito.junit.jupiter.MockitoSettings;
import org.mockito.quality.Strictness;

import java.math.BigDecimal;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
class RefundClaimTest {

    @Mock RefundRepository refundRepository;
    @Mock PaymentRepository paymentRepository;
    @Mock UserRepository userRepository;
    @Mock SystemSettingService systemSettingService;
    @Mock AuditService auditService;
    @Mock NotificationService notificationService;

    RefundClaimCodes codes = new RefundClaimCodes("test-secret");
    RefundService service;
    Refund refund;

    @BeforeEach
    void setUp() {
        service = new RefundService(refundRepository, paymentRepository, userRepository, systemSettingService,
                auditService, notificationService, codes);

        Role receptionistRole = new Role();
        receptionistRole.setCode("RECEPTIONIST");
        User staff = new User();
        staff.setId(2L);
        staff.setFullName("Le tan");
        staff.setIsActive(true);
        staff.setRole(receptionistRole);
        when(userRepository.findByEmail("letan@demo.local")).thenReturn(Optional.of(staff));

        User member = new User();
        member.setId(9L);
        Payment payment = new Payment();
        payment.setId(5L);
        payment.setPayer(member);
        payment.setStatus("PENDING_REFUND");
        payment.setOrder(new Order());
        refund = new Refund();
        refund.setId(7L);
        refund.setPayment(payment);
        refund.setAmount(new BigDecimal("900000"));
        refund.setMethod("CASH");
        when(refundRepository.findByIdForUpdate(7L)).thenReturn(Optional.of(refund));
        when(paymentRepository.findByIdForUpdate(5L)).thenReturn(Optional.of(payment));
    }

    @Test
    void codeIsSixDigitsAndStablePerRefund() {
        String a = codes.of(7L);
        assertEquals(6, a.length());
        assertEquals(a, codes.of(7L));
        assertNotEquals(a, codes.of(8L));
        assertTrue(codes.matches(7L, " " + a + " "));
    }

    @Test
    void wrongCodeIsRefusedAndCounted() {
        assertThrows(BusinessRuleException.class, () -> service.complete(7L, "letan@demo.local", "000000".equals(codes.of(7L)) ? "111111" : "000000"));
        assertEquals("PENDING", refund.getStatus());
        assertEquals((short) 1, refund.getClaimFailedAttempts().shortValue());
    }

    @Test
    void fifthWrongCodeLocksTheRefundEvenForTheRightCode() {
        String wrong = "000000".equals(codes.of(7L)) ? "111111" : "000000";
        for (int i = 0; i < 5; i++) {
            assertThrows(BusinessRuleException.class, () -> service.complete(7L, "letan@demo.local", wrong));
        }
        assertNotNull(refund.getClaimLockedUntil());
        assertThrows(BusinessRuleException.class, () -> service.complete(7L, "letan@demo.local", codes.of(7L)));
        assertEquals("PENDING", refund.getStatus());
    }

    @Test
    void rightCodeCompletesAndTellsTheMember() {
        service.complete(7L, "letan@demo.local", codes.of(7L));

        assertEquals("DONE", refund.getStatus());
        verify(notificationService).sendWithEmail(eq(9L), eq("PAYMENT"), eq("Đã hoàn tiền"), any(), any(), any());
    }

    @Test
    void sendingTheCodeEmailsTheMemberAndCannotBeRepeatedAtOnce() {
        service.sendClaimCode(7L, "letan@demo.local");

        verify(notificationService).sendWithEmail(eq(9L), eq("PAYMENT"), eq("Mã nhận tiền hoàn"),
                org.mockito.ArgumentMatchers.contains(codes.of(7L)), any(), any());
        assertThrows(BusinessRuleException.class, () -> service.sendClaimCode(7L, "letan@demo.local"));
    }
}
