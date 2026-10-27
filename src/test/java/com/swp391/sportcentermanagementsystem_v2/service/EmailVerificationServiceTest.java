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
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
class EmailVerificationServiceTest {

    private static final String EMAIL = "member@test.com";

    @Mock
    UserRepository userRepository;
    @Mock
    SystemSettingService settings;
    @Mock
    CodeMailer mailer;
    @Mock
    VoucherService voucherService;

    @InjectMocks
    EmailVerificationService service;

    private User user;

    @BeforeEach
    void setUp() {
        // Mọi cấu hình lấy giá trị mặc định truyền vào.
        when(settings.getInt(anyString(), anyInt())).thenAnswer(inv -> inv.getArgument(1));

        user = new User();
        user.setEmail(EMAIL);
        user.setFullName("Member");
        when(userRepository.findByEmailIgnoreCase(EMAIL)).thenReturn(Optional.of(user));
    }

    // Gửi mã rồi đọc lại 6 số mà mailer đã nhận.
    private String sendAndReadCode() {
        service.sendCode(user);
        ArgumentCaptor<String> code = ArgumentCaptor.forClass(String.class);
        verify(mailer).send(eq(EMAIL), eq("Member"), code.capture(), eq(15), eq(CodeMailer.Kind.VERIFY_EMAIL));
        return code.getValue();
    }

    @Test
    void correctCodeVerifiesTheEmailAndClearsTheHash() {
        String code = sendAndReadCode();

        service.verify(EMAIL, code);

        assertNotNull(user.getEmailVerifiedAt());
        assertNull(user.getVerifyTokenHash());
    }

    @Test
    void wrongCodeIsRejectedAndEmailStaysUnverified() {
        String code = sendAndReadCode();

        ResponseStatusException ex = assertThrows(ResponseStatusException.class,
                () -> service.verify(EMAIL, code.equals("000000") ? "111111" : "000000"));

        assertEquals(HttpStatus.BAD_REQUEST, ex.getStatusCode());
        assertNull(user.getEmailVerifiedAt());
    }

    @Test
    void expiredCodeIsRejected() {
        String code = sendAndReadCode();
        user.setVerifyTokenExpiresAt(OffsetDateTime.now().minusMinutes(1));

        assertThrows(ResponseStatusException.class, () -> service.verify(EMAIL, code));
        assertNull(user.getEmailVerifiedAt());
    }

    @Test
    void fiveWrongCodesInvalidateTheRealOne() {
        String code = sendAndReadCode();
        String wrong = code.equals("123456") ? "654321" : "123456";

        for (int i = 0; i < 5; i++) {
            assertThrows(ResponseStatusException.class, () -> service.verify(EMAIL, wrong));
        }

        assertNull(user.getVerifyTokenHash());
        assertThrows(ResponseStatusException.class, () -> service.verify(EMAIL, code));
        // Hủy mã không được mở đường gửi lại ngay (đoán mã rồi xin mã mới liên tục).
        assertEquals(HttpStatus.TOO_MANY_REQUESTS,
                assertThrows(ResponseStatusException.class, () -> service.resend(EMAIL)).getStatusCode());
    }

    @Test
    void resendWithinCooldownIsRejected() {
        sendAndReadCode();

        ResponseStatusException ex = assertThrows(ResponseStatusException.class, () -> service.resend(EMAIL));

        assertEquals(HttpStatus.TOO_MANY_REQUESTS, ex.getStatusCode());
    }

    @Test
    void resendForUnknownOrVerifiedEmailDoesNothing() {
        when(userRepository.findByEmailIgnoreCase("nobody@test.com")).thenReturn(Optional.empty());
        service.resend("nobody@test.com");

        user.setEmailVerifiedAt(OffsetDateTime.now());
        service.resend(EMAIL);

        verify(mailer, never()).send(anyString(), anyString(), anyString(), anyInt(), any());
    }
}
