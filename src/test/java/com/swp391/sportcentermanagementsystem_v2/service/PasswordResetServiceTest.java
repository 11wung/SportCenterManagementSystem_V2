package com.swp391.sportcentermanagementsystem_v2.service;

import com.swp391.sportcentermanagementsystem_v2.entity.User;
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
import org.springframework.http.HttpStatus;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.web.server.ResponseStatusException;

import java.time.OffsetDateTime;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
class PasswordResetServiceTest {

    private static final String EMAIL = "member@test.com";

    @Mock
    UserRepository userRepository;
    @Mock
    SystemSettingService settings;
    @Mock
    CodeMailer mailer;
    @Mock
    PasswordEncoder passwordEncoder;

    @InjectMocks
    PasswordResetService service;

    private User user;

    @BeforeEach
    void setUp() {
        when(settings.getInt(anyString(), anyInt())).thenAnswer(inv -> inv.getArgument(1));
        when(passwordEncoder.encode("NewPass#123")).thenReturn("ENCODED");

        user = new User();
        user.setEmail(EMAIL);
        user.setFullName("Member");
        user.setIsActive(true);
        user.setPasswordHash("OLD");
        when(userRepository.findByEmailIgnoreCase(EMAIL)).thenReturn(Optional.of(user));
    }

    // Xin mã rồi đọc lại 6 số mà mailer đã nhận.
    private String requestAndReadCode() {
        service.requestReset(EMAIL);
        ArgumentCaptor<String> code = ArgumentCaptor.forClass(String.class);
        verify(mailer).send(eq(EMAIL), eq("Member"), code.capture(), eq(15), eq(CodeMailer.Kind.RESET_PASSWORD));
        return code.getValue();
    }

    @Test
    void correctCodeSetsTheNewPasswordAndClearsTheToken() {
        String code = requestAndReadCode();

        service.resetPassword(EMAIL, code, "NewPass#123");

        assertEquals("ENCODED", user.getPasswordHash());
        assertNull(user.getResetTokenHash());
        assertNull(user.getResetTokenExpiresAt());
    }

    @Test
    void theCodeWorksOnlyOnce() {
        String code = requestAndReadCode();
        service.resetPassword(EMAIL, code, "NewPass#123");

        assertThrows(ResponseStatusException.class, () -> service.resetPassword(EMAIL, code, "NewPass#123"));
    }

    @Test
    void resettingProvesTheMailboxSoTheEmailBecomesVerified() {
        String code = requestAndReadCode();
        assertNull(user.getEmailVerifiedAt());

        service.resetPassword(EMAIL, code, "NewPass#123");

        assertNotNull(user.getEmailVerifiedAt());
    }

    @Test
    void wrongCodeKeepsTheOldPassword() {
        String code = requestAndReadCode();

        ResponseStatusException ex = assertThrows(ResponseStatusException.class,
                () -> service.resetPassword(EMAIL, code.equals("000000") ? "111111" : "000000", "NewPass#123"));

        assertEquals(HttpStatus.BAD_REQUEST, ex.getStatusCode());
        assertEquals("OLD", user.getPasswordHash());
    }

    @Test
    void expiredCodeIsRejected() {
        String code = requestAndReadCode();
        user.setResetTokenExpiresAt(OffsetDateTime.now().minusMinutes(1));

        assertThrows(ResponseStatusException.class, () -> service.resetPassword(EMAIL, code, "NewPass#123"));
        assertEquals("OLD", user.getPasswordHash());
    }

    @Test
    void noCodeWasEverRequested() {
        assertThrows(ResponseStatusException.class, () -> service.resetPassword(EMAIL, "123456", "NewPass#123"));
    }

    @Test
    void unknownEmailGetsTheSameErrorAsAWrongCode() {
        when(userRepository.findByEmailIgnoreCase("nobody@test.com")).thenReturn(Optional.empty());

        ResponseStatusException ex = assertThrows(ResponseStatusException.class,
                () -> service.resetPassword("nobody@test.com", "123456", "NewPass#123"));

        assertEquals("Invalid or expired code", ex.getReason());
    }

    @Test
    void fiveWrongCodesDestroyTheRealCodeAndDoNotOpenANewRequestAtOnce() {
        String code = requestAndReadCode();
        String wrong = code.equals("123456") ? "654321" : "123456";

        for (int i = 0; i < 5; i++) {
            assertThrows(ResponseStatusException.class, () -> service.resetPassword(EMAIL, wrong, "NewPass#123"));
        }

        assertNull(user.getResetTokenHash());
        assertThrows(ResponseStatusException.class, () -> service.resetPassword(EMAIL, code, "NewPass#123"));
        // Đoán mã rồi xin mã mới liên tục không được: vẫn phải chờ hết thời gian chờ.
        service.requestReset(EMAIL);
        verify(mailer, times(1)).send(anyString(), anyString(), anyString(), anyInt(), any());
    }

    @Test
    void requestingTwiceInARowSendsOnlyOneMail() {
        service.requestReset(EMAIL);
        service.requestReset(EMAIL);

        verify(mailer, times(1)).send(anyString(), anyString(), anyString(), anyInt(), any());
    }

    @Test
    void unknownEmailSendsNothingAndDoesNotFail() {
        when(userRepository.findByEmailIgnoreCase("nobody@test.com")).thenReturn(Optional.empty());

        service.requestReset("nobody@test.com");

        verify(mailer, never()).send(anyString(), anyString(), anyString(), anyInt(), any());
    }

    @Test
    void deactivatedAccountGetsNoMail() {
        user.setIsActive(false);

        service.requestReset(EMAIL);

        verify(mailer, never()).send(anyString(), anyString(), anyString(), anyInt(), any());
    }
}
