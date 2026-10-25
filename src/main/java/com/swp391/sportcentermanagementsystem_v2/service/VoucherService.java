package com.swp391.sportcentermanagementsystem_v2.service;

import lombok.RequiredArgsConstructor;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.time.OffsetDateTime;
import java.util.List;
import java.util.Optional;

// BR-VCH-02: phát voucher theo cấu hình trong system_settings, và đọc, khóa, đánh dấu đã dùng voucher khi thanh toán.
// Dùng JdbcTemplate thay vì entity để không trùng với entity Voucher của Flow 1 về sau
@Service
@RequiredArgsConstructor
public class VoucherService {

    private final JdbcTemplate jdbcTemplate;

    // kind: PERCENT, AMOUNT, FREE_ONE_ON_ONE. appliesTo: CLASS, ONE_ON_ONE, PACKAGE
    public record Voucher(Long id, Long userId, String source, String kind, BigDecimal value, String appliesTo,
            OffsetDateTime expiresAt, OffsetDateTime usedAt) {

        public boolean isUsable(OffsetDateTime now) {
            return usedAt == null && expiresAt.isAfter(now);
        }
    }

    // Hoàn thành: coach đánh giá xong học viên ở cuối class, giảm cho class kế tiếp
    @Transactional
    public void issueCompletion(Long userId) {
        issue(userId, "COMPLETION", "voucher.completion", false);
    }

    // Bù lỗi trung tâm: buổi học bị hủy do trung tâm hoặc coach hủy buổi 1-1. Mỗi người chỉ giữ một voucher này chưa dùng
    // tại một thời điểm, để một lớp bị hủy nhiều buổi không phát hàng loạt voucher
    @Transactional
    public void issueCompensation(Long userId) {
        issue(userId, "COMPENSATION", "voucher.compensation", true);
    }

    private void issue(Long userId, String source, String settingKey, boolean onlyOneUnused) {
        jdbcTemplate.update("""
                INSERT INTO vouchers (user_id, source, kind, value, applies_to, expires_at)
                SELECT ?, ?, s.value ->> 'kind', (s.value ->> 'value')::numeric, s.value ->> 'applies_to',
                       now() + make_interval(days => (s.value ->> 'valid_days')::int)
                FROM system_settings s
                WHERE s.key = ?
                  AND (NOT ? OR NOT EXISTS (
                        SELECT 1 FROM vouchers v
                        WHERE v.user_id = ? AND v.source = ? AND v.used_at IS NULL AND v.expires_at > now()))
                """, userId, source, settingKey, onlyOneUnused, userId, source);
    }

    // Voucher chưa dùng và chưa hết hạn của member; appliesTo = null thì lấy mọi loại
    @Transactional(readOnly = true)
    public List<Voucher> findUsable(Long userId, String appliesTo) {
        return jdbcTemplate.query("""
                SELECT id, user_id, source, kind, value, applies_to, expires_at, used_at FROM vouchers
                WHERE user_id = ? AND used_at IS NULL AND expires_at > now() AND (?::text IS NULL OR applies_to = ?)
                ORDER BY expires_at, id
                """, (rs, i) -> toVoucher(rs), userId, appliesTo, appliesTo);
    }

    @Transactional(readOnly = true)
    public Optional<Voucher> findById(Long voucherId) {
        return jdbcTemplate.query("""
                SELECT id, user_id, source, kind, value, applies_to, expires_at, used_at FROM vouchers WHERE id = ?
                """, (rs, i) -> toVoucher(rs), voucherId).stream().findFirst();
    }

    // Voucher đang được giữ cho một lần thanh toán khác của member (QR đang mở hoặc đã trả): chưa được dùng cho đơn này.
    // QR hết hạn thì payment không còn CREATING/PENDING nên voucher tự rảnh lại
    @Transactional(readOnly = true)
    public boolean isHeldByOtherOrder(Long voucherId, Long exceptOrderId) {
        Boolean held = jdbcTemplate.queryForObject("""
                SELECT EXISTS (
                    SELECT 1 FROM orders o JOIN payments p ON p.order_id = o.id
                    WHERE o.voucher_id = ? AND o.id <> ? AND p.status IN ('CREATING', 'PENDING', 'SUCCESS'))
                """, Boolean.class, voucherId, exceptOrderId);
        return Boolean.TRUE.equals(held);
    }

    // Gọi khi thanh toán thành công. Đã dùng thì không đổi lại, và không trả lại khi hủy hay hoàn tiền (BR-VCH-03)
    @Transactional
    public void markUsed(Long voucherId) {
        if (voucherId != null) {
            jdbcTemplate.update("UPDATE vouchers SET used_at = now() WHERE id = ? AND used_at IS NULL", voucherId);
        }
    }

    private static Voucher toVoucher(java.sql.ResultSet rs) throws java.sql.SQLException {
        return new Voucher(rs.getLong("id"), rs.getLong("user_id"), rs.getString("source"), rs.getString("kind"),
                rs.getBigDecimal("value"), rs.getString("applies_to"), rs.getObject("expires_at", OffsetDateTime.class),
                rs.getObject("used_at", OffsetDateTime.class));
    }
}
