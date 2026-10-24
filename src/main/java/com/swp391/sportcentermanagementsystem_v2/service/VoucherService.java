package com.swp391.sportcentermanagementsystem_v2.service;

import lombok.RequiredArgsConstructor;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

// BR-VCH-02: phát voucher theo cấu hình trong system_settings. Chỉ ghi vào bảng vouchers; việc dùng voucher khi thanh toán
// thuộc Flow 3 nên voucher phát ra chưa dùng được cho tới khi Flow 3 nối vào.
// Dùng JdbcTemplate thay vì entity để không trùng với entity Voucher của Flow 1 về sau
@Service
@RequiredArgsConstructor
public class VoucherService {

    private final JdbcTemplate jdbcTemplate;

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
}
