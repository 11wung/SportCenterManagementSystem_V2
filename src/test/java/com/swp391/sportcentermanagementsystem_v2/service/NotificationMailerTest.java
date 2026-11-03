package com.swp391.sportcentermanagementsystem_v2.service;

import jakarta.mail.Session;
import jakarta.mail.internet.MimeMessage;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.mail.javamail.JavaMailSender;
import org.springframework.test.util.ReflectionTestUtils;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;

@ExtendWith(MockitoExtension.class)
class NotificationMailerTest {

    @Mock
    JavaMailSender mailSender;

    @InjectMocks
    NotificationMailer mailer;

    @Test
    void doesNothingWhenMailIsNotConfigured() {
        ReflectionTestUtils.setField(mailer, "mailFrom", "");

        mailer.send("member@test.com", "Lan", "Chủ đề", "Nội dung");

        verify(mailSender, never()).send(any(MimeMessage.class));
    }

    @Test
    void sendsOneMessageWhenMailIsConfigured() {
        ReflectionTestUtils.setField(mailer, "mailFrom", "sender@gmail.com");
        lenient().when(mailSender.createMimeMessage()).thenReturn(new MimeMessage((Session) null));

        mailer.send("member@test.com", "Lan", "Chủ đề", "Nội dung");

        verify(mailSender).send(any(MimeMessage.class));
    }

    @Test
    void escapesTextTheUserTyped() {
        String html = NotificationMailer.htmlBody("<b>Lan</b>", "Lý do: <script>alert(1)</script>");

        assertFalse(html.contains("<script>"));
        assertTrue(html.contains("&lt;script&gt;"));
        assertTrue(html.contains("&lt;b&gt;Lan&lt;/b&gt;"));
    }
}
