package com.swp391.sportcentermanagementsystem_v2.service;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.security.SecureRandom;
import java.util.HexFormat;
import java.util.Locale;

// Mã 6 số dùng cho xác thực email và đặt lại mật khẩu. DB chỉ lưu hash của (email + mã).
final class VerificationCode {

    private static final SecureRandom RANDOM = new SecureRandom();

    private VerificationCode() {
    }

    static String generate() {
        return String.format("%06d", RANDOM.nextInt(1_000_000));
    }

    static String key(String email) {
        return email.trim().toLowerCase(Locale.ROOT);
    }

    // Gắn email vào hash để hai người trùng mã 6 số không đụng index unique của cột hash.
    static String hash(String email, String code) {
        try {
            byte[] digest = MessageDigest.getInstance("SHA-256")
                    .digest((key(email) + ":" + code).getBytes(StandardCharsets.UTF_8));
            return HexFormat.of().formatHex(digest);
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException(e);
        }
    }

    static boolean matches(String email, String code, String storedHash) {
        return MessageDigest.isEqual(
                hash(email, code).getBytes(StandardCharsets.UTF_8),
                storedHash.getBytes(StandardCharsets.UTF_8));
    }
}
