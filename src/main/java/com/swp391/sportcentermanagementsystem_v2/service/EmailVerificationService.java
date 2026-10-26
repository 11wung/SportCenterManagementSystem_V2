package com.swp391.sportcentermanagementsystem_v2.service;

import com.swp391.sportcentermanagementsystem_v2.entity.User;
import com.swp391.sportcentermanagementsystem_v2.repository.UserRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpStatus;
import org.springframework.mail.MailException;
import org.springframework.mail.javamail.JavaMailSender;
import org.springframework.mail.javamail.MimeMessageHelper;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;
import org.springframework.web.util.HtmlUtils;

import jakarta.mail.MessagingException;
import jakarta.mail.internet.MimeMessage;

import java.io.UnsupportedEncodingException;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.security.SecureRandom;
import java.time.OffsetDateTime;
import java.util.HexFormat;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

// Mã 6 số gửi qua email khi đăng ký. DB chỉ lưu hash của (email + mã).
@Slf4j
@Service
@RequiredArgsConstructor
public class EmailVerificationService {

    private static final SecureRandom RANDOM = new SecureRandom();
    private static final int MAX_WRONG_CODES = 5;
    private static final String INVALID_CODE = "Invalid or expired code";

    private final UserRepository userRepository;
    private final SystemSettingService settings;
    private final JavaMailSender mailSender;

    // Trống nghĩa là chưa cấu hình Gmail: mã chỉ được in ra log
    @Value("${spring.mail.username:}")
    private String mailFrom;

    // shortcut: đếm nhập sai trong bộ nhớ của một bản app (mất khi khởi động lại),
    // dùng cột/bảng DB khi chạy nhiều bản app.
    private final Map<String, Integer> wrongCodes = new ConcurrentHashMap<>();

    // Tạo mã mới, lưu hash và gửi email. Lỗi gửi mail không làm hỏng đăng ký, member có thể gửi lại.
    @Transactional
    public void sendCode(User user) {
        int minutes = settings.getInt("auth.verify_code_minutes", 15);
        String code = String.format("%06d", RANDOM.nextInt(1_000_000));

        user.setVerifyTokenHash(hash(user.getEmail(), code));
        user.setVerifyTokenExpiresAt(OffsetDateTime.now().plusMinutes(minutes));
        userRepository.save(user);
        wrongCodes.remove(key(user.getEmail()));

        deliver(user.getEmail(), user.getFullName(), code, minutes);
    }

    // Câu trả lời sai chung một thông báo, không tiết lộ email có tồn tại hay không.
    @Transactional(noRollbackFor = ResponseStatusException.class)
    public void verify(String email, String code) {
        User user = userRepository.findByEmailIgnoreCase(email.trim())
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.BAD_REQUEST, INVALID_CODE));

        if (user.getEmailVerifiedAt() != null) {
            return;
        }
        if (user.getVerifyTokenHash() == null
                || user.getVerifyTokenExpiresAt() == null
                || OffsetDateTime.now().isAfter(user.getVerifyTokenExpiresAt())) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, INVALID_CODE);
        }

        String key = key(user.getEmail());
        boolean match = MessageDigest.isEqual(
                hash(user.getEmail(), code).getBytes(StandardCharsets.UTF_8),
                user.getVerifyTokenHash().getBytes(StandardCharsets.UTF_8));

        if (!match) {
            // Sai quá nhiều lần thì huỷ mã, phải xin mã mới (chống đoán mã).
            // Giữ lại verify_token_expires_at để resend() vẫn tính được thời gian chờ.
            if (wrongCodes.merge(key, 1, Integer::sum) >= MAX_WRONG_CODES) {
                user.setVerifyTokenHash(null);
                userRepository.save(user);
                wrongCodes.remove(key);
            }
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, INVALID_CODE);
        }

        user.setEmailVerifiedAt(OffsetDateTime.now());
        user.setVerifyTokenHash(null);
        user.setVerifyTokenExpiresAt(null);
        userRepository.save(user);
        wrongCodes.remove(key);
    }

    // Email lạ hoặc đã xác thực thì im lặng thành công. Chỉ chặn gửi dồn dập theo thời gian chờ.
    // shortcut: chưa giới hạn số lần gửi lại mỗi ngày (auth.verify_resend_per_day), cần cột đếm trong DB.
    @Transactional
    public void resend(String email) {
        User user = userRepository.findByEmailIgnoreCase(email.trim()).orElse(null);
        if (user == null || user.getEmailVerifiedAt() != null) {
            return;
        }

        if (user.getVerifyTokenExpiresAt() != null) {
            int minutes = settings.getInt("auth.verify_code_minutes", 15);
            int cooldown = settings.getInt("auth.verify_resend_cooldown_seconds", 60);
            OffsetDateTime lastSent = user.getVerifyTokenExpiresAt().minusMinutes(minutes);
            if (OffsetDateTime.now().isBefore(lastSent.plusSeconds(cooldown))) {
                throw new ResponseStatusException(HttpStatus.TOO_MANY_REQUESTS,
                        "Please wait before requesting a new code");
            }
        }
        sendCode(user);
    }

    private void deliver(String to, String fullName, String code, int minutes) {
        if (mailFrom == null || mailFrom.isBlank()) {
            log.warn("Mail is not configured, verification code for {} is {}", to, code);
            return;
        }
        try {
            MimeMessage message = mailSender.createMimeMessage();
            MimeMessageHelper helper = new MimeMessageHelper(message, true, "UTF-8");
            helper.setFrom(mailFrom, "Sport Center");
            helper.setTo(to);
            helper.setSubject("Sport Center - Mã xác thực tài khoản của bạn");
            helper.setText(plainBody(fullName, code, minutes), htmlBody(fullName, code, minutes));
            mailSender.send(message);
        } catch (MessagingException | MailException | UnsupportedEncodingException ex) {
            log.error("Cannot send verification mail to {}", to, ex);
        }
    }

    // Bản văn bản thường cho ứng dụng mail không hiển thị được HTML.
    private static String plainBody(String fullName, String code, int minutes) {
        return "Xin chào " + fullName + ",\n\n"
                + "Cảm ơn bạn đã đăng ký tài khoản Sport Center.\n"
                + "Mã xác thực của bạn: " + code + "\n\n"
                + "Mã có hiệu lực trong " + minutes + " phút. Vui lòng không chia sẻ mã này cho bất kỳ ai.\n"
                + "Nếu bạn không đăng ký, hãy bỏ qua email này.\n\n"
                + "Sport Center";
    }

    // fullName do người dùng nhập nên phải escape trước khi đưa vào HTML.
    private static String htmlBody(String fullName, String code, int minutes) {
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
                        <p style="margin:0 0 20px;">Cảm ơn bạn đã đăng ký tài khoản. Hãy nhập mã dưới đây để xác thực email của bạn:</p>
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
                        Nếu bạn không đăng ký tài khoản, hãy bỏ qua email này.
                      </td>
                    </tr>
                  </table>
                </div>
                """.formatted(HtmlUtils.htmlEscape(fullName), code, minutes);
    }

    private static String key(String email) {
        return email.trim().toLowerCase(Locale.ROOT);
    }

    // Gắn email vào hash để hai member trùng mã 6 số không đụng index unique của verify_token_hash.
    private static String hash(String email, String code) {
        try {
            byte[] digest = MessageDigest.getInstance("SHA-256")
                    .digest((key(email) + ":" + code).getBytes(StandardCharsets.UTF_8));
            return HexFormat.of().formatHex(digest);
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException(e);
        }
    }
}
