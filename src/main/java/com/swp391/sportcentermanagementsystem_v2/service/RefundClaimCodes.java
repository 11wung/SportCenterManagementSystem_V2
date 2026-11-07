package com.swp391.sportcentermanagementsystem_v2.service;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import java.nio.charset.StandardCharsets;
import java.security.GeneralSecurityException;

/**
 * Mã nhận tiền hoàn gồm 6 số, tính từ mã khoản hoàn và khóa bí mật của ứng dụng nên không phải lưu trong DB.
 * Chỉ hội viên (qua ứng dụng, thông báo, email) biết mã; quầy phải nhập đúng mã mới hoàn được.
 */
@Component
public class RefundClaimCodes {

    private final byte[] key;

    public RefundClaimCodes(@Value("${app.jwt.secret}") String secret) {
        this.key = ("refund-claim:" + secret).getBytes(StandardCharsets.UTF_8);
    }

    public String of(Long refundId) {
        try {
            Mac mac = Mac.getInstance("HmacSHA256");
            mac.init(new SecretKeySpec(key, "HmacSHA256"));
            byte[] h = mac.doFinal(("refund:" + refundId).getBytes(StandardCharsets.UTF_8));
            int n = ((h[0] & 0x7f) << 24 | (h[1] & 0xff) << 16 | (h[2] & 0xff) << 8 | (h[3] & 0xff)) % 1_000_000;
            return String.format("%06d", n);
        } catch (GeneralSecurityException e) {
            throw new IllegalStateException("Không tính được mã nhận tiền", e);
        }
    }

    public boolean matches(Long refundId, String code) {
        return code != null && java.security.MessageDigest.isEqual(
                of(refundId).getBytes(StandardCharsets.UTF_8), code.trim().getBytes(StandardCharsets.UTF_8));
    }
}
