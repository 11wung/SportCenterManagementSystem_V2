package com.swp391.sportcentermanagementsystem_v2.service;

import jakarta.mail.Multipart;
import jakarta.mail.Part;
import jakarta.mail.Session;
import jakarta.mail.internet.MimeMessage;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.mockito.junit.jupiter.MockitoSettings;
import org.mockito.quality.Strictness;
import org.springframework.mail.javamail.JavaMailSender;
import org.springframework.test.util.ReflectionTestUtils;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
class CodeMailerTest {

    @Mock
    JavaMailSender mailSender;

    @InjectMocks
    CodeMailer mailer;

    @BeforeEach
    void setUp() {
        ReflectionTestUtils.setField(mailer, "mailFrom", "sender@gmail.com");
        when(mailSender.createMimeMessage()).thenAnswer(inv -> new MimeMessage((Session) null));
    }

    private MimeMessage sendAndCapture(String name, CodeMailer.Kind kind) throws Exception {
        mailer.send("to@test.com", name, "482913", 15, kind);
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
    void verifyMailHasTheCodeInHtmlAndPlainText() throws Exception {
        MimeMessage mail = sendAndCapture("Member", CodeMailer.Kind.VERIFY_EMAIL);

        assertEquals(CodeMailer.Kind.VERIFY_EMAIL.subject, mail.getSubject());
        assertTrue(partText(mail, "text/html").contains("482913"));
        assertTrue(partText(mail, "text/plain").contains("Mã của bạn: 482913"));
    }

    @Test
    void resetMailUsesItsOwnSubjectAndWording() throws Exception {
        MimeMessage mail = sendAndCapture("Member", CodeMailer.Kind.RESET_PASSWORD);

        assertEquals(CodeMailer.Kind.RESET_PASSWORD.subject, mail.getSubject());
        assertTrue(partText(mail, "text/html").contains("đặt lại mật khẩu"));
        assertFalse(partText(mail, "text/html").contains("đăng ký tài khoản"));
    }

    @Test
    void nameIsEscapedInHtml() throws Exception {
        MimeMessage mail = sendAndCapture("<script>alert(1)</script>", CodeMailer.Kind.VERIFY_EMAIL);
        String html = partText(mail, "text/html");

        assertTrue(html.contains("&lt;script&gt;"));
        assertFalse(html.contains("<script>"));
    }

    @Test
    void withoutMailConfigNothingIsSent() {
        ReflectionTestUtils.setField(mailer, "mailFrom", "");

        mailer.send("to@test.com", "Member", "482913", 15, CodeMailer.Kind.VERIFY_EMAIL);

        verify(mailSender, never()).send(any(MimeMessage.class));
    }
}
