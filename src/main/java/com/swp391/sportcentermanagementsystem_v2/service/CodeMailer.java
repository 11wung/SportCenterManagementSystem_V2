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

// Gửi email HTML chứa mã 6 số. Chạy ở luồng riêng để SMTP chậm không làm treo request,
// và để thời gian trả lời không lộ email có tồn tại hay không.
@Slf4j
@Service
@RequiredArgsConstructor
public class CodeMailer {

    public enum Kind {
        VERIFY_EMAIL(
                "Sport Center - Mã xác thực tài khoản của bạn",
                "Cảm ơn bạn đã đăng ký tài khoản. Hãy nhập mã dưới đây để xác thực email của bạn:",
                "Nếu bạn không đăng ký tài khoản, hãy bỏ qua email này."),
        RESET_PASSWORD(
                "Sport Center - Mã đặt lại mật khẩu của bạn",
                "Chúng tôi nhận được yêu cầu đặt lại mật khẩu cho tài khoản của bạn. Hãy nhập mã dưới đây để tiếp tục:",
                "Nếu bạn không yêu cầu đặt lại mật khẩu, hãy bỏ qua email này, mật khẩu của bạn vẫn giữ nguyên.");

        final String subject;
        final String intro;
        final String footer;

        Kind(String subject, String intro, String footer) {
            this.subject = subject;
            this.intro = intro;
            this.footer = footer;
        }
    }

    private final JavaMailSender mailSender;

    // Trống nghĩa là chưa cấu hình Gmail: mã chỉ được in ra log
    @Value("${spring.mail.username:}")
    private String mailFrom;

    // Lỗi gửi mail chỉ được ghi log, người dùng có thể xin gửi lại.
    @Async
    public void send(String to, String fullName, String code, int minutes, Kind kind) {
        if (mailFrom == null || mailFrom.isBlank()) {
            log.warn("Mail is not configured, {} code for {} is {}", kind, to, code);
            return;
        }
        try {
            MimeMessage message = mailSender.createMimeMessage();
            MimeMessageHelper helper = new MimeMessageHelper(message, true, "UTF-8");
            helper.setFrom(mailFrom, "Sport Center");
            helper.setTo(to);
            helper.setSubject(kind.subject);
            helper.setText(plainBody(fullName, code, minutes, kind), htmlBody(fullName, code, minutes, kind));
            mailSender.send(message);
        } catch (MessagingException | MailException | UnsupportedEncodingException ex) {
            log.error("Cannot send {} mail to {}", kind, to, ex);
        }
    }

    // Bản văn bản thường cho ứng dụng mail không hiển thị được HTML.
    static String plainBody(String fullName, String code, int minutes, Kind kind) {
        return "Xin chào " + fullName + ",\n\n"
                + kind.intro + "\n"
                + "Mã của bạn: " + code + "\n\n"
                + "Mã có hiệu lực trong " + minutes + " phút. Vui lòng không chia sẻ mã này cho bất kỳ ai.\n"
                + kind.footer + "\n\n"
                + "Sport Center";
    }

    // fullName do người dùng nhập nên phải escape trước khi đưa vào HTML.
    static String htmlBody(String fullName, String code, int minutes, Kind kind) {
        return """
                <div style="background:#f3f4f6;padding:24px 12px;font-family:Arial,Helvetica,sans-serif;">
                  <table role="presentation" align="center" width="100%%" cellpadding="0" cellspacing="0"
                         style="max-width:520px;background:#ffffff;border-radius:12px;overflow:hidden;">
                    <tr>
                      <td style="background:#2563eb;padding:24px;text-align:center;color:#ffffff;
                                 font-size:22px;font-weight:bold;letter-spacing:1px;">
                        SPORT CENTER
                      </td>
                    </tr>
                    <tr>
                      <td style="padding:32px 28px;color:#111827;font-size:15px;line-height:1.6;">
                        <p style="margin:0 0 12px;">Xin chào <b>%s</b>,</p>
                        <p style="margin:0 0 20px;">%s</p>
                        <div style="text-align:center;margin:24px 0;">
                          <span style="display:inline-block;background:#eff6ff;border:1px dashed #2563eb;
                                       border-radius:8px;padding:14px 28px;font-size:34px;font-weight:bold;
                                       letter-spacing:10px;color:#1d4ed8;">%s</span>
                        </div>
                        <p style="margin:0 0 8px;">Mã có hiệu lực trong <b>%d phút</b>.</p>
                        <p style="margin:0;color:#b91c1c;">Vui lòng không chia sẻ mã này cho bất kỳ ai.</p>
                      </td>
                    </tr>
                    <tr>
                      <td style="background:#f9fafb;padding:16px 28px;color:#6b7280;font-size:12px;text-align:center;">
                        %s
                      </td>
                    </tr>
                  </table>
                </div>
                """.formatted(HtmlUtils.htmlEscape(fullName), kind.intro, code, minutes, kind.footer);
    }
}
