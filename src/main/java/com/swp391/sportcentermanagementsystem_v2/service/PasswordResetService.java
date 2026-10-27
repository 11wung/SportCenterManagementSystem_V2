package com.swp391.sportcentermanagementsystem_v2.service;

import com.swp391.sportcentermanagementsystem_v2.entity.User;
import com.swp391.sportcentermanagementsystem_v2.repository.UserRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;

import java.time.OffsetDateTime;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

// Quên mật khẩu: gửi mã 6 số qua email, nhập đúng mã thì đặt mật khẩu mới.
// DB chỉ lưu hash của (email + mã) ở cột reset_token_hash.
@Service
@RequiredArgsConstructor
public class PasswordResetService {

    private static final int MAX_WRONG_CODES = 5;
    private static final String INVALID_CODE = "Invalid or expired code";

    private final UserRepository userRepository;
    private final SystemSettingService settings;
    private final CodeMailer mailer;
    private final PasswordEncoder passwordEncoder;

    // shortcut: đếm nhập sai trong bộ nhớ của một bản app (mất khi khởi động lại),
    // dùng cột/bảng DB khi chạy nhiều bản app.
    private final Map<String, Integer> wrongCodes = new ConcurrentHashMap<>();

    // Luôn im lặng thành công dù email lạ, tài khoản bị khóa hay đang trong thời gian chờ,
    // để không ai dò được email nào đã đăng ký.
    // shortcut: chưa giới hạn số lần xin mã mỗi ngày, chỉ chờ giữa hai lần (auth.verify_resend_cooldown_seconds).
    @Transactional
    public void requestReset(String email) {
        User user = userRepository.findByEmailIgnoreCase(email.trim()).orElse(null);
        if (user == null || !Boolean.TRUE.equals(user.getIsActive())) {
            return;
        }

        int minutes = settings.getInt("auth.reset_code_minutes", 15);
        if (user.getResetTokenExpiresAt() != null) {
            int cooldown = settings.getInt("auth.verify_resend_cooldown_seconds", 60);
            OffsetDateTime lastSent = user.getResetTokenExpiresAt().minusMinutes(minutes);
            if (OffsetDateTime.now().isBefore(lastSent.plusSeconds(cooldown))) {
                return;
            }
        }

        String code = VerificationCode.generate();
        user.setResetTokenHash(VerificationCode.hash(user.getEmail(), code));
        user.setResetTokenExpiresAt(OffsetDateTime.now().plusMinutes(minutes));
        userRepository.save(user);
        wrongCodes.remove(VerificationCode.key(user.getEmail()));

        mailer.send(user.getEmail(), user.getFullName(), code, minutes, CodeMailer.Kind.RESET_PASSWORD);
    }

    // Mã sai, hết hạn hay email lạ đều trả cùng một thông báo.
    // Mã đúng nghĩa là người này đọc được hộp thư, nên email cũng được coi là đã xác thực.
    // shortcut: token JWT đã cấp trước đó vẫn dùng được đến khi hết hạn (không có cơ chế thu hồi).
    @Transactional(noRollbackFor = ResponseStatusException.class)
    public void resetPassword(String email, String code, String newPassword) {
        User user = userRepository.findByEmailIgnoreCase(email.trim())
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.BAD_REQUEST, INVALID_CODE));

        if (user.getResetTokenHash() == null
                || user.getResetTokenExpiresAt() == null
                || OffsetDateTime.now().isAfter(user.getResetTokenExpiresAt())) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, INVALID_CODE);
        }

        String key = VerificationCode.key(user.getEmail());
        if (!VerificationCode.matches(user.getEmail(), code, user.getResetTokenHash())) {
            // Giữ lại reset_token_expires_at để requestReset() vẫn tính được thời gian chờ.
            if (wrongCodes.merge(key, 1, Integer::sum) >= MAX_WRONG_CODES) {
                user.setResetTokenHash(null);
                userRepository.save(user);
                wrongCodes.remove(key);
            }
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, INVALID_CODE);
        }

        user.setPasswordHash(passwordEncoder.encode(newPassword));
        user.setResetTokenHash(null);
        user.setResetTokenExpiresAt(null);
        if (user.getEmailVerifiedAt() == null) {
            user.setEmailVerifiedAt(OffsetDateTime.now());
            user.setVerifyTokenHash(null);
            user.setVerifyTokenExpiresAt(null);
        }
        userRepository.save(user);
        wrongCodes.remove(key);
    }
}
