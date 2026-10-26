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
import jakarta.mail.Multipart;
import jakarta.mail.Part;
import jakarta.mail.Session;
import jakarta.mail.internet.MimeMessage;
import org.springframework.mail.javamail.JavaMailSender;
import org.springframework.test.util.ReflectionTestUtils;
import org.springframework.web.server.ResponseStatusException;

import java.time.OffsetDateTime;
import java.util.Optional;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyString;
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
    JavaMailSender mailSender;

    @InjectMocks
    EmailVerificationService service;

    private User user;

    @BeforeEach
    void setUp() {
        ReflectionTestUtils.setField(service, "mailFrom", "sender@gmail.com");
        // Mọi cấu hình lấy giá trị mặc định truyền vào.
        when(settings.getInt(anyString(), anyInt())).thenAnswer(inv -> inv.getArgument(1));

        user = new User();
        user.setEmail(EMAIL);
        user.setFullName("Member");
        when(userRepository.findByEmailIgnoreCase(EMAIL)).thenReturn(Optional.of(user));
        when(mailSender.createMimeMessage()).thenAnswer(inv -> new MimeMessage((Session) null));
    }

    // Gửi mã rồi đọc lại 6 số trong bản văn bản thường của mail.
    private String sendAndReadCode() throws Exception {
        MimeMessage mail = sendAndCapture();
        Matcher m = Pattern.compile("bạn: (\\d{6})").matcher(partText(mail, "text/plain"));
        assertEquals(true, m.find());
        return m.group(1);
    }

    private MimeMessage sendAndCapture() throws Exception {
        service.sendCode(user);
        ArgumentCaptor<MimeMessage> mail = ArgumentCaptor.forClass(MimeMessage.class);
        verify(mailSender).send(mail.capture());
        // Chốt các header (Content-Type) như lúc mail được gửi thật.
        mail.getValue().saveChanges();
        return mail.getValue();
    }

    // Gom nội dung các phần mail có kiểu mime cho trước (mail gồm nhiều phần lồng nhau).
    private static String partText(Part part, String mime) throws Exception {
        if (part.isMimeType("multipart/*")) {
            Multipart multipart = (Multipart) part.getContent();
            StringBuilder all = new StringBuilder();
            for (int i = 0; i < multipart.getCount(); i++) {
                all.append(partText(multipart.getBodyPart(i), mime));
            }
            return all.toString();
        }
        return part.isMimeType(mime) ? (String) part.getContent() : "";
    }

    @Test
    void mailIsHtmlWithTheCodeAndEscapesTheName() throws Exception {
        user.setFullName("<script>alert(1)</script>");

        MimeMessage mail = sendAndCapture();
        String html = partText(mail, "text/html");

        assertEquals(true, html.contains("&lt;script&gt;"));
        assertEquals(false, html.contains("<script>"));
        assertEquals(true, html.contains("SPORT CENTER"));
        assertEquals("Sport Center - Mã xác thực tài khoản của bạn", mail.getSubject());
    }

    @Test
    void correctCodeVerifiesTheEmailAndClearsTheHash() throws Exception {
        String code = sendAndReadCode();

        service.verify(EMAIL, code);

        assertNotNull(user.getEmailVerifiedAt());
        assertNull(user.getVerifyTokenHash());
    }

    @Test
    void wrongCodeIsRejectedAndEmailStaysUnverified() throws Exception {
        sendAndReadCode();

        ResponseStatusException ex = assertThrows(ResponseStatusException.class,
                () -> service.verify(EMAIL, "000000".equals(user.getVerifyTokenHash()) ? "111111" : "000000"));

        assertEquals(HttpStatus.BAD_REQUEST, ex.getStatusCode());
        assertNull(user.getEmailVerifiedAt());
    }

    @Test
    void expiredCodeIsRejected() throws Exception {
        String code = sendAndReadCode();
        user.setVerifyTokenExpiresAt(OffsetDateTime.now().minusMinutes(1));

        assertThrows(ResponseStatusException.class, () -> service.verify(EMAIL, code));
        assertNull(user.getEmailVerifiedAt());
    }

    @Test
    void fiveWrongCodesInvalidateTheRealOne() throws Exception {
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
    void resendWithinCooldownIsRejected() throws Exception {
        sendAndReadCode();

        ResponseStatusException ex = assertThrows(ResponseStatusException.class, () -> service.resend(EMAIL));

        assertEquals(HttpStatus.TOO_MANY_REQUESTS, ex.getStatusCode());
    }

    @Test
    void resendForUnknownOrVerifiedEmailDoesNothing() throws Exception {
        when(userRepository.findByEmailIgnoreCase("nobody@test.com")).thenReturn(Optional.empty());
        service.resend("nobody@test.com");

        user.setEmailVerifiedAt(OffsetDateTime.now());
        service.resend(EMAIL);

        verify(mailSender, never()).send(any(MimeMessage.class));
    }
}
