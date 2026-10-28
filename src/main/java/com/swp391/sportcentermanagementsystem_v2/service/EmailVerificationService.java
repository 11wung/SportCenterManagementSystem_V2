package com.swp391.sportcentermanagementsystem_v2.service;

import com.swp391.sportcentermanagementsystem_v2.entity.User;
import com.swp391.sportcentermanagementsystem_v2.repository.UserRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;

import java.time.OffsetDateTime;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

// Mã 6 số gửi qua email khi đăng ký. DB chỉ lưu hash của (email + mã).
@Service
@RequiredArgsConstructor
public class EmailVerificationService {

    private static final int MAX_WRONG_CODES = 5;
    private static final String INVALID_CODE = "Invalid or expired code";

    private final UserRepository userRepository;
    private final SystemSettingService settings;
    private final VoucherService voucherService;
    private final CodeMailer mailer;

    private final Map<String, Integer> wrongCodes = new ConcurrentHashMap<>();

    @Transactional
    public void sendCode(User user) {
        int minutes = settings.getInt("auth.verify_code_minutes", 15);
        String code = VerificationCode.generate();

        user.setVerifyTokenHash(
                VerificationCode.hash(user.getEmail(), code));

        user.setVerifyTokenExpiresAt(
                OffsetDateTime.now().plusMinutes(minutes));

        userRepository.save(user);

        wrongCodes.remove(VerificationCode.key(user.getEmail()));

        mailer.send(
                user.getEmail(),
                user.getFullName(),
                code,
                minutes,
                CodeMailer.Kind.VERIFY_EMAIL
        );
    }

    @Transactional(noRollbackFor = ResponseStatusException.class)
    public void verify(String email, String code) {
        User user = userRepository.findByEmailIgnoreCase(email.trim())
                .orElseThrow(() -> new ResponseStatusException(
                        HttpStatus.BAD_REQUEST, INVALID_CODE));

        if (user.getEmailVerifiedAt() != null) {
            return;
        }

        if (user.getVerifyTokenHash() == null
                || user.getVerifyTokenExpiresAt() == null
                || OffsetDateTime.now().isAfter(
                        user.getVerifyTokenExpiresAt())) {

            throw new ResponseStatusException(
                    HttpStatus.BAD_REQUEST, INVALID_CODE);
        }

        String key = VerificationCode.key(user.getEmail());

        if (!VerificationCode.matches(
                user.getEmail(),
                code,
                user.getVerifyTokenHash())) {

            if (wrongCodes.merge(key, 1, Integer::sum)
                    >= MAX_WRONG_CODES) {

                user.setVerifyTokenHash(null);
                userRepository.save(user);
                wrongCodes.remove(key);
            }

            throw new ResponseStatusException(
                    HttpStatus.BAD_REQUEST, INVALID_CODE);
        }

        user.setEmailVerifiedAt(OffsetDateTime.now());
        user.setVerifyTokenHash(null);
        user.setVerifyTokenExpiresAt(null);

        userRepository.save(user);

        wrongCodes.remove(key);

        voucherService.issueWelcome(user.getId());
    }

    @Transactional
    public void resend(String email) {
        User user = userRepository
                .findByEmailIgnoreCase(email.trim())
                .orElse(null);

        if (user == null || user.getEmailVerifiedAt() != null) {
            return;
        }

        if (user.getVerifyTokenExpiresAt() != null) {
            int minutes = settings.getInt(
                    "auth.verify_code_minutes", 15);

            int cooldown = settings.getInt(
                    "auth.verify_resend_cooldown_seconds", 60);

            OffsetDateTime lastSent =
                    user.getVerifyTokenExpiresAt().minusMinutes(minutes);

            if (OffsetDateTime.now()
                    .isBefore(lastSent.plusSeconds(cooldown))) {

                throw new ResponseStatusException(
                        HttpStatus.TOO_MANY_REQUESTS,
                        "Please wait before requesting a new code");
            }
        }

        sendCode(user);
    }
}
