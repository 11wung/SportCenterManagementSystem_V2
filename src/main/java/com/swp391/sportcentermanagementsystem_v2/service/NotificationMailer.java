package com.swp391.sportcentermanagementsystem_v2.service;

import jakarta.mail.MessagingException;
import jakarta.mail.internet.MimeMessage;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.mail.MailException;
import org.springframework.mail.javamail.JavaMailSender;
import org.springframework.mail.javamail.MimeMessageHelper;
import org.springframework.scheduling.annotation.Async;
import org.springframework.stereotype.Service;
import org.springframework.web.util.HtmlUtils;

import java.io.UnsupportedEncodingException;

// Gửi email thông báo (được mời từ danh sách chờ, trung tâm hủy lớp hoặc buổi học). Chạy ở luồng riêng và chỉ ghi log khi lỗi,
// vì thông báo trong ứng dụng đã có sẵn và việc gửi mail không được làm hỏng thao tác gốc.
@Slf4j
@Service
@RequiredArgsConstructor
public class NotificationMailer {

    private final JavaMailSender mailSender;

    // Trống nghĩa là chưa cấu hình Gmail: bỏ qua việc gửi mail
    @Value("${spring.mail.username:}")
    private String mailFrom;

    @Async
    public void send(String to, String fullName, String subject, String message) {
        if (mailFrom == null || mailFrom.isBlank()) {
            log.info("Mail is not configured, skip notification mail to {}: {}", to, subject);
            return;
        }
        try {
            MimeMessage mime = mailSender.createMimeMessage();
            MimeMessageHelper helper = new MimeMessageHelper(mime, true, "UTF-8");
            helper.setFrom(mailFrom, "Sport Center");
            helper.setTo(to);
            helper.setSubject(subject);
            helper.setText(plainBody(fullName, message), htmlBody(fullName, message));
            mailSender.send(mime);
        } catch (MessagingException | MailException | UnsupportedEncodingException ex) {
            log.error("Cannot send notification mail to {}", to, ex);
        }
    }

    static String plainBody(String fullName, String message) {
        return "Xin chào " + fullName + ",\n\n" + message + "\n\nSport Center";
    }

    // fullName và nội dung có thể chứa chữ do người dùng nhập (lý do hủy), nên phải escape trước khi đưa vào HTML.
    static String htmlBody(String fullName, String message) {
        return """
                <div style="background:#f3f4f6;padding:24px 12px;font-family:Arial,Helvetica,sans-serif;">
                  <table role="presentation" align="center" width="100%%" cellpadding="0" cellspacing="0"
                         style="max-width:520px;background:#ffffff;overflow:hidden;">
                    <tr>
                      <td style="background:#ff4d00;padding:20px;text-align:center;color:#0a0a0a;
                                 font-size:20px;font-weight:bold;letter-spacing:1px;">SPORT CENTER</td>
                    </tr>
                    <tr>
                      <td style="padding:28px;color:#111827;font-size:15px;line-height:1.6;">
                        <p style="margin:0 0 12px;">Xin chào <b>%s</b>,</p>
                        <p style="margin:0;">%s</p>
                      </td>
                    </tr>
                  </table>
                </div>
                """.formatted(HtmlUtils.htmlEscape(fullName), HtmlUtils.htmlEscape(message));
    }
}
