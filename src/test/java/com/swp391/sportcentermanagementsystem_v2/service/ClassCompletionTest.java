package com.swp391.sportcentermanagementsystem_v2.service;

import com.swp391.sportcentermanagementsystem_v2.entity.ClassRegistration;
import com.swp391.sportcentermanagementsystem_v2.entity.SportClass;
import com.swp391.sportcentermanagementsystem_v2.entity.User;
import com.swp391.sportcentermanagementsystem_v2.repository.ClassRegistrationRepository;
import com.swp391.sportcentermanagementsystem_v2.repository.SportClassRepository;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.mockito.junit.jupiter.MockitoSettings;
import org.mockito.quality.Strictness;

import java.time.LocalDate;
import java.util.List;
import java.util.Optional;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

// Lớp kết thúc: học viên còn học được voucher và thông báo ngay, coach được nhắc chấm, không cần chờ coach chấm
@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
class ClassCompletionTest {

    @Mock
    SportClassRepository sportClassRepository;
    @Mock
    ClassRegistrationRepository classRegistrationRepository;
    @Mock
    NotificationService notificationService;
    @Mock
    VoucherService voucherService;

    @InjectMocks
    SportClassService service;

    private static User user(long id) {
        User u = new User();
        u.setId(id);
        return u;
    }

    @Test
    void completedClassIssuesVoucherToEachStudentAndRemindsCoach() {
        LocalDate today = LocalDate.of(2026, 11, 20);
        SportClass cls = new SportClass();
        cls.setName("Yoga");
        cls.setCoach(user(7));
        ClassRegistration r1 = new ClassRegistration();
        r1.setUser(user(1));
        ClassRegistration r2 = new ClassRegistration();
        r2.setUser(user(2));
        when(sportClassRepository.findFinishedClassIds(today)).thenReturn(List.of(5L));
        when(sportClassRepository.findWithDetailsById(5L)).thenReturn(Optional.of(cls));
        when(classRegistrationRepository.findBySportClass_IdAndStatusInOrderById(eq(5L), any())).thenReturn(List.of(r1, r2));

        service.advanceLifecycle(today);

        verify(voucherService).issueCompletion(1L);
        verify(voucherService).issueCompletion(2L);
        verify(notificationService).send(eq(7L), anyString(), anyString(), anyString());
    }

    @Test
    void classWithoutStudentsDoesNotNotifyCoach() {
        LocalDate today = LocalDate.of(2026, 11, 20);
        SportClass cls = new SportClass();
        cls.setCoach(user(7));
        when(sportClassRepository.findFinishedClassIds(today)).thenReturn(List.of(5L));
        when(sportClassRepository.findWithDetailsById(5L)).thenReturn(Optional.of(cls));
        when(classRegistrationRepository.findBySportClass_IdAndStatusInOrderById(eq(5L), any())).thenReturn(List.of());

        service.advanceLifecycle(today);

        verify(voucherService, never()).issueCompletion(any());
        verify(notificationService, never()).send(any(), anyString(), anyString(), anyString());
    }
}
