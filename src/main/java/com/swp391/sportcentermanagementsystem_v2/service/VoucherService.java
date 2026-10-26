package com.swp391.sportcentermanagementsystem_v2.service;

import lombok.RequiredArgsConstructor;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.Month;
import java.time.OffsetDateTime;
import java.time.Year;
import java.time.ZoneId;
import java.util.List;
import java.util.Optional;

// BR-VCH-02: phát voucher theo cấu hình trong system_settings, và đọc, khóa, đánh dấu đã dùng voucher khi thanh toán.
// Dùng JdbcTemplate thay vì entity để không trùng với entity Voucher của Flow 1 về sau
@Service
@RequiredArgsConstructor
public class VoucherService {

    private static final ZoneId CENTER_ZONE = ZoneId.of("Asia/Ho_Chi_Minh");

    private final JdbcTemplate jdbcTemplate;
    private final SystemSettingService settings;
    private final NotificationService notificationService;

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

    // ====================== PHÁT TỰ ĐỘNG THEO NGÀY (VoucherScheduler) ======================
    // Cả ba loại đều không phát trùng, nên chạy lại trong ngày không tạo thêm voucher.
    // Thông báo chỉ gửi cho người vừa được tặng

    // Quà sinh nhật theo quyền lợi của gói (BR-BEN-02): giảm % cho một lớp, hoặc một buổi 1-1 miễn phí, mỗi năm một lần.
    // Sinh nhật 29/2 nhận vào 28/2 ở năm không nhuận
    @Transactional
    public int issueBirthday(LocalDate today) {
        String monthDay = String.format("%02d%02d", today.getMonthValue(), today.getDayOfMonth());
        boolean leapDayFallback = today.getMonth() == Month.FEBRUARY && today.getDayOfMonth() == 28
                && !Year.isLeap(today.getYear());
        OffsetDateTime yearStart = today.withDayOfYear(1).atStartOfDay(CENTER_ZONE).toOffsetDateTime();
        List<Long> users = jdbcTemplate.queryForList("""
                INSERT INTO vouchers (user_id, source, kind, value, applies_to, expires_at)
                SELECT u.id, 'BIRTHDAY', s.benefits_snapshot ->> 'birthday_gift_kind',
                       (s.benefits_snapshot ->> 'birthday_gift_value')::numeric,
                       CASE s.benefits_snapshot ->> 'birthday_gift_kind' WHEN 'FREE_ONE_ON_ONE' THEN 'ONE_ON_ONE' ELSE 'CLASS' END,
                       now() + make_interval(days => (SELECT (value #>> '{}')::int FROM system_settings
                                                      WHERE key = 'voucher.birthday_valid_days'))
                FROM users u
                JOIN member_subscriptions s ON s.user_id = u.id AND s.status = 'ACTIVE'
                     AND ?::date >= s.start_date AND ?::date < s.end_date
                WHERE u.is_active AND u.date_of_birth IS NOT NULL
                  AND s.benefits_snapshot ->> 'birthday_gift_kind' IN ('PERCENT', 'FREE_ONE_ON_ONE')
                  AND (to_char(u.date_of_birth, 'MMDD') = ?
                       OR (? AND to_char(u.date_of_birth, 'MMDD') = '0229'))
                  AND NOT EXISTS (SELECT 1 FROM vouchers v
                                  WHERE v.user_id = u.id AND v.source = 'BIRTHDAY' AND v.issued_at >= ?)
                RETURNING user_id
                """, Long.class, today, today, monthDay, leapDayFallback, yearStart);
        notify(users, "Happy birthday!", "A birthday gift from your membership is waiting in your vouchers.");
        return users.size();
    }

    // Gói còn tối đa voucher.early_renewal_days ngày mà chưa gia hạn: tặng một voucher cho kỳ gói này
    @Transactional
    public int issueEarlyRenewal(LocalDate today) {
        int withinDays = settings.getInt("voucher.early_renewal_days");
        List<Long> users = jdbcTemplate.queryForList("""
                INSERT INTO vouchers (user_id, source, kind, value, applies_to, expires_at)
                SELECT s.user_id, 'EARLY_RENEWAL', cfg.value ->> 'kind', (cfg.value ->> 'value')::numeric,
                       cfg.value ->> 'applies_to', now() + make_interval(days => (cfg.value ->> 'valid_days')::int)
                FROM member_subscriptions s
                JOIN system_settings cfg ON cfg.key = 'voucher.early_renewal'
                WHERE s.status = 'ACTIVE' AND ?::date >= s.start_date AND s.end_date > ?::date
                  AND s.end_date <= ?::date + ?
                  AND NOT EXISTS (SELECT 1 FROM member_subscriptions n
                                  WHERE n.user_id = s.user_id AND n.status IN ('SCHEDULED', 'PENDING'))
                  AND NOT EXISTS (SELECT 1 FROM vouchers v
                                  WHERE v.user_id = s.user_id AND v.source = 'EARLY_RENEWAL'
                                    AND v.issued_at >= (s.start_date::timestamp AT TIME ZONE 'Asia/Ho_Chi_Minh'))
                RETURNING user_id
                """, Long.class, today, today, today, withinDays);
        notify(users, "Renew early and save", "Your membership ends soon. A discount voucher for renewing is waiting for you.");
        return users.size();
    }

    // Gói đã hết hạn voucher.winback_after_days ngày mà chưa mua lại: tặng một voucher cho lần hết hạn đó
    @Transactional
    public int issueWinback(LocalDate today) {
        int afterDays = settings.getInt("voucher.winback_after_days");
        List<Long> users = jdbcTemplate.queryForList("""
                INSERT INTO vouchers (user_id, source, kind, value, applies_to, expires_at)
                SELECT l.user_id, 'WINBACK', cfg.value ->> 'kind', (cfg.value ->> 'value')::numeric,
                       cfg.value ->> 'applies_to', now() + make_interval(days => (cfg.value ->> 'valid_days')::int)
                FROM (SELECT user_id, max(end_date) AS last_end FROM member_subscriptions
                      WHERE status IN ('ACTIVE', 'EXPIRED', 'UPGRADED') AND end_date IS NOT NULL
                      GROUP BY user_id) l
                JOIN system_settings cfg ON cfg.key = 'voucher.winback'
                JOIN users u ON u.id = l.user_id AND u.is_active
                WHERE l.last_end <= ?::date - ?
                  AND NOT EXISTS (SELECT 1 FROM member_subscriptions c
                                  WHERE c.user_id = l.user_id AND c.status IN ('ACTIVE', 'SCHEDULED', 'PENDING'))
                  AND NOT EXISTS (SELECT 1 FROM vouchers v
                                  WHERE v.user_id = l.user_id AND v.source = 'WINBACK'
                                    AND v.issued_at >= (l.last_end::timestamp AT TIME ZONE 'Asia/Ho_Chi_Minh'))
                RETURNING user_id
                """, Long.class, today, afterDays);
        notify(users, "We miss you", "Come back with a discount voucher for a new membership.");
        return users.size();
    }

    private void notify(List<Long> userIds, String title, String content) {
        userIds.forEach(id -> notificationService.send(id, "MEMBERSHIP", title, content));
    }

    private static Voucher toVoucher(java.sql.ResultSet rs) throws java.sql.SQLException {
        return new Voucher(rs.getLong("id"), rs.getLong("user_id"), rs.getString("source"), rs.getString("kind"),
                rs.getBigDecimal("value"), rs.getString("applies_to"), rs.getObject("expires_at", OffsetDateTime.class),
                rs.getObject("used_at", OffsetDateTime.class));
    }
}
