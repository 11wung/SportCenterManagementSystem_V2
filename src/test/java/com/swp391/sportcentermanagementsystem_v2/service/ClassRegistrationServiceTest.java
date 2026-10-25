package com.swp391.sportcentermanagementsystem_v2.service;

import com.swp391.sportcentermanagementsystem_v2.dto.responseDTO.ClassRegistrationResponse;
import com.swp391.sportcentermanagementsystem_v2.entity.ClassRegistration;
import com.swp391.sportcentermanagementsystem_v2.entity.Course;
import com.swp391.sportcentermanagementsystem_v2.entity.Payment;
import com.swp391.sportcentermanagementsystem_v2.entity.Refund;
import com.swp391.sportcentermanagementsystem_v2.entity.Role;
import com.swp391.sportcentermanagementsystem_v2.entity.SportClass;
import com.swp391.sportcentermanagementsystem_v2.entity.User;
import com.swp391.sportcentermanagementsystem_v2.exception.BusinessRuleException;
import com.swp391.sportcentermanagementsystem_v2.repository.ClassRegistrationRepository;
import com.swp391.sportcentermanagementsystem_v2.repository.ClassSessionRepository;
import com.swp391.sportcentermanagementsystem_v2.repository.CoachSlotRepository;
import com.swp391.sportcentermanagementsystem_v2.repository.PaymentRepository;
import com.swp391.sportcentermanagementsystem_v2.repository.SportClassRepository;
import com.swp391.sportcentermanagementsystem_v2.repository.UserRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.mockito.junit.jupiter.MockitoSettings;
import org.mockito.quality.Strictness;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

// Quyền lợi tier trong đăng ký lớp: đăng ký sớm (BR-BEN-04), hạng chờ (BR-WL-02), hoàn đủ khi rời ở buổi đầu (BR-CAN-01)
@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
class ClassRegistrationServiceTest {

    private static final LocalDate TODAY = LocalDate.now(ZoneId.of("Asia/Ho_Chi_Minh"));
    private static final String EMAIL = "member@example.com";
    private static final BigDecimal PAID = new BigDecimal("900000");

    @Mock
    ClassRegistrationRepository classRegistrationRepository;
    @Mock
    SportClassRepository sportClassRepository;
    @Mock
    UserRepository userRepository;
    @Mock
    SystemSettingService systemSettingService;
    @Mock
    PaymentRepository paymentRepository;
    @Mock
    CoachSlotRepository coachSlotRepository;
    @Mock
    RefundService refundService;
    @Mock
    WaitlistService waitlistService;
    @Mock
    ClassSessionRepository classSessionRepository;
    @Mock
    BenefitService benefitService;

    @InjectMocks
    ClassRegistrationService service;

    private User member;
    private SportClass sportClass;

    @BeforeEach
    void setUp() {
        Role role = new Role();
        role.setCode("MEMBER");
        member = new User();
        member.setId(5L);
        member.setEmail(EMAIL);
        member.setRole(role);
        member.setIsActive(true);

        Course course = new Course();
        course.setLevel("BASIC");
        sportClass = new SportClass();
        sportClass.setId(20L);
        sportClass.setName("Boxing basic");
        sportClass.setCourse(course);
        sportClass.setStatus("OPEN");
        sportClass.setMaxCapacity(10);
        sportClass.setTuition(new BigDecimal("1000000"));
        sportClass.setStartDate(TODAY.plusDays(10));
        sportClass.setRegistrationOpensOn(TODAY.plusDays(2));

        when(userRepository.findByEmail(EMAIL)).thenReturn(Optional.of(member));
        when(sportClassRepository.findByIdForUpdate(20L)).thenReturn(Optional.of(sportClass));
        when(classRegistrationRepository.existsBySportClass_IdAndUser_IdAndStatusIn(anyLong(), anyLong(), any()))
                .thenReturn(false);
        when(classRegistrationRepository.countBySportClass_IdAndStatusIn(anyLong(), any())).thenReturn(0L);
        when(classRegistrationRepository.findConflictingClassName(anyLong(), anyLong(), any(Integer.class)))
                .thenReturn(Optional.empty());
        when(coachSlotRepository.findMemberSlotConflictForClass(anyLong(), anyLong(), any(Integer.class)))
                .thenReturn(Optional.empty());
        when(systemSettingService.getInt(anyString())).thenReturn(15);
        when(classRegistrationRepository.save(any(ClassRegistration.class))).thenAnswer(inv -> inv.getArgument(0));
        when(refundService.createRefund(any(), any(), anyString())).thenAnswer(inv -> {
            Refund refund = new Refund();
            refund.setAmount(inv.getArgument(1));
            return refund;
        });
    }

    private void benefit(String key, int value) {
        when(benefitService.intBenefit(eq(5L), eq(key), any())).thenReturn(value);
    }

    // ---- A2: đăng ký sớm ----

    @Test
    void registeringBeforeOpenDateIsRejectedWithoutBenefit() {
        BusinessRuleException ex = assertThrows(BusinessRuleException.class,
                () -> service.registerMyself(EMAIL, 20L, false));

        assertTrue(ex.getMessage().contains("Registration opens on " + TODAY.plusDays(2)));
    }

    @Test
    void earlyRegisterDaysOpenRegistrationEarlier() {
        benefit("early_register_days", 3);

        ClassRegistrationResponse response = service.registerMyself(EMAIL, 20L, false);

        assertEquals("PENDING_PAYMENT", response.getStatus());
    }

    @Test
    void earlyRegisterDaysThatAreNotEnoughStillReject() {
        benefit("early_register_days", 1);

        BusinessRuleException ex = assertThrows(BusinessRuleException.class,
                () -> service.registerMyself(EMAIL, 20L, false));

        assertTrue(ex.getMessage().contains("Registration opens on " + TODAY.plusDays(1)));
    }

    // ---- A6: hạng danh sách chờ lấy từ quyền lợi ----

    @Test
    void waitlistRankComesFromPackageBenefit() {
        sportClass.setRegistrationOpensOn(TODAY);
        when(classRegistrationRepository.countBySportClass_IdAndStatusIn(anyLong(), any())).thenReturn(10L);
        benefit("waitlist_priority", 2);

        service.joinWaitlist(EMAIL, 20L, false);

        ArgumentCaptor<ClassRegistration> saved = ArgumentCaptor.forClass(ClassRegistration.class);
        verify(classRegistrationRepository).save(saved.capture());
        assertEquals("WAITLISTED", saved.getValue().getStatus());
        assertEquals((short) 2, saved.getValue().getWaitlistRank());
    }

    // ---- A3: rời lớp ----

    private ClassRegistration registered(LocalDate classStart) {
        sportClass.setStartDate(classStart);
        ClassRegistration registration = new ClassRegistration();
        registration.setId(100L);
        registration.setSportClass(sportClass);
        registration.setUser(member);
        registration.setStatus("REGISTERED");

        Payment payment = new Payment();
        payment.setAmount(PAID);
        when(classRegistrationRepository.findClassIdById(100L)).thenReturn(Optional.of(20L));
        when(classRegistrationRepository.findByIdForUpdate(100L)).thenReturn(Optional.of(registration));
        when(paymentRepository.findFirstByOrder_Registration_IdAndStatusOrderByIdDesc(100L, "SUCCESS"))
                .thenReturn(Optional.of(payment));
        return registration;
    }

    @Test
    void leavingWithinFirstSessionsOfBenefitRefundsWhatWasPaid() {
        ClassRegistration registration = registered(TODAY.minusDays(3));
        benefit("early_leave_refund_sessions", 1);
        when(classSessionRepository.countStudied(eq(20L), any())).thenReturn(1L);

        ClassRegistrationResponse response = service.cancelMyRegistration(EMAIL, 100L);

        assertEquals("LEFT", registration.getStatus());
        assertEquals(PAID, response.getRefundAmount());
        verify(refundService).createRefund(any(Payment.class), eq(PAID), anyString());
    }

    @Test
    void leavingAfterMoreSessionsThanBenefitGivesNoRefund() {
        ClassRegistration registration = registered(TODAY.minusDays(3));
        benefit("early_leave_refund_sessions", 1);
        when(classSessionRepository.countStudied(eq(20L), any())).thenReturn(2L);

        ClassRegistrationResponse response = service.cancelMyRegistration(EMAIL, 100L);

        assertEquals("LEFT", registration.getStatus());
        assertNull(response.getRefundAmount());
        verify(refundService, never()).createRefund(any(), any(), anyString());
    }

    @Test
    void leavingWithoutBenefitGivesNoRefundEvenBeforeAnySessionWasStudied() {
        registered(TODAY.minusDays(1));
        when(classSessionRepository.countStudied(eq(20L), any())).thenReturn(0L);

        ClassRegistrationResponse response = service.cancelMyRegistration(EMAIL, 100L);

        assertNull(response.getRefundAmount());
        verify(refundService, never()).createRefund(any(), any(), anyString());
    }

    @Test
    void cancelingBeforeClassStartsStillRefundsInFull() {
        ClassRegistration registration = registered(TODAY.plusDays(3));

        ClassRegistrationResponse response = service.cancelMyRegistration(EMAIL, 100L);

        assertEquals("CANCELED", registration.getStatus());
        assertEquals(PAID, response.getRefundAmount());
    }
}
