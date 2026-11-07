package com.swp391.sportcentermanagementsystem_v2.service;

import com.swp391.sportcentermanagementsystem_v2.dto.responseDTO.UserSummaryResponse;
import com.swp391.sportcentermanagementsystem_v2.entity.Role;
import com.swp391.sportcentermanagementsystem_v2.entity.User;
import com.swp391.sportcentermanagementsystem_v2.exception.BusinessRuleException;
import com.swp391.sportcentermanagementsystem_v2.repository.UserRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.mockito.junit.jupiter.MockitoSettings;
import org.mockito.quality.Strictness;
import org.springframework.http.HttpStatus;
import org.springframework.web.server.ResponseStatusException;

import java.time.OffsetDateTime;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyMap;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
class ReceptionistMemberServiceTest {

    @Mock
    UserRepository userRepository;
    @Mock
    UserService userService;
    @Mock
    AuthService authService;
    @Mock
    EmailVerificationService emailVerificationService;
    @Mock
    AuditService auditService;

    @InjectMocks
    ReceptionistMemberService service;

    private User staff;
    private User member;

    private static User user(Long id, String email, String roleCode) {
        Role role = new Role();
        role.setCode(roleCode);
        User u = new User();
        u.setId(id);
        u.setEmail(email);
        u.setFullName("Name " + id);
        u.setIsActive(true);
        u.setRole(role);
        return u;
    }

    @BeforeEach
    void setUp() {
        staff = user(1L, "lan@center.com", "RECEPTIONIST");
        member = user(7L, "an@gmail.com", "MEMBER");
        when(userRepository.findByEmail("lan@center.com")).thenReturn(Optional.of(staff));
        when(userRepository.findById(7L)).thenReturn(Optional.of(member));
        when(userRepository.findById(1L)).thenReturn(Optional.of(staff));
    }

    @Test
    void theRightCodeVerifiesTheMemberAndTheStaffActionIsLogged() {
        doAnswer(inv -> {
            member.setEmailVerifiedAt(OffsetDateTime.now());
            return null;
        }).when(emailVerificationService).verify("an@gmail.com", "123456");

        UserSummaryResponse result = service.verifyMemberEmail(7L, "123456", "lan@center.com");

        assertTrue(result.isEmailVerified());
        verify(auditService).log(eq(staff), eq("MEMBER_EMAIL_VERIFIED_BY_STAFF"), eq("user"), eq(7L), anyMap());
    }

    @Test
    void aWrongCodeGivesAVietnameseMessageAndNothingIsLogged() {
        doThrow(new ResponseStatusException(HttpStatus.BAD_REQUEST, "Invalid or expired code"))
                .when(emailVerificationService).verify("an@gmail.com", "000000");

        ResponseStatusException ex = assertThrows(ResponseStatusException.class,
                () -> service.verifyMemberEmail(7L, "000000", "lan@center.com"));

        assertEquals(HttpStatus.BAD_REQUEST, ex.getStatusCode());
        assertEquals("Mã xác thực sai hoặc đã hết hạn", ex.getReason());
        verify(auditService, never()).log(any(), any(), any(), any(), any());
    }

    @Test
    void onlyAMemberCanBeVerifiedThisWay() {
        User coach = user(8L, "coach@center.com", "COACH");
        when(userRepository.findById(8L)).thenReturn(Optional.of(coach));

        ResponseStatusException ex = assertThrows(ResponseStatusException.class,
                () -> service.verifyMemberEmail(8L, "123456", "lan@center.com"));

        assertEquals(HttpStatus.NOT_FOUND, ex.getStatusCode());
        verify(emailVerificationService, never()).verify(any(), any());
    }

    @Test
    void resendingSendsANewCode() {
        when(emailVerificationService.secondsUntilNextCode(member)).thenReturn(0L);

        service.resendMemberCode(7L);

        verify(emailVerificationService).sendCode(member);
    }

    @Test
    void resendingTooSoonTellsHowManySecondsToWait() {
        when(emailVerificationService.secondsUntilNextCode(member)).thenReturn(42L);

        ResponseStatusException ex = assertThrows(ResponseStatusException.class,
                () -> service.resendMemberCode(7L));

        assertEquals(HttpStatus.TOO_MANY_REQUESTS, ex.getStatusCode());
        assertEquals("Vui lòng chờ 42 giây rồi gửi lại mã", ex.getReason());
        verify(emailVerificationService, never()).sendCode(any(User.class));
    }

    @Test
    void anAlreadyVerifiedMemberDoesNotGetAnotherCode() {
        member.setEmailVerifiedAt(OffsetDateTime.now());

        BusinessRuleException ex = assertThrows(BusinessRuleException.class,
                () -> service.resendMemberCode(7L));

        assertEquals("Email của hội viên này đã được xác thực", ex.getMessage());
        verify(emailVerificationService, never()).sendCode(any(User.class));
    }
}
