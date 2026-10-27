package com.swp391.sportcentermanagementsystem_v2.service;

import lombok.RequiredArgsConstructor;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDate;

// Nhắc member trong ứng dụng: gói sắp hết hạn (BR-PKG-12) và ngày mai có buổi học.
// Dùng JdbcTemplate như NotificationService. Câu SQL bỏ qua thông báo giống hệt đã gửi trong 20 giờ qua,
// nên chạy lại (khởi động lại đúng giờ chạy) không gửi trùng
@Service
@RequiredArgsConstructor
public class ReminderService {

    private final JdbcTemplate jdbcTemplate;

    // Gói còn đúng N ngày theo các mốc subscription.expiry_notice_days (mặc định 3 và 1). Gói hết hạn vào end_date,
    // nên số ngày còn lại là end_date - hôm nay
    @Transactional
    public int remindExpiringSubscriptions(LocalDate today) {
        return jdbcTemplate.update("""
                INSERT INTO notifications (recipient_id, type, channel, title, content)
                SELECT s.user_id, 'MEMBERSHIP', 'APP', 'Membership ending soon',
                       'Your membership ends in ' || (s.end_date - ?::date) || ' day(s). Renew to keep your benefits.'
                FROM member_subscriptions s
                WHERE s.status = 'ACTIVE' AND s.end_date > ?::date
                  AND (s.end_date - ?::date) IN (
                        SELECT jsonb_array_elements_text(value)::int FROM system_settings
                        WHERE key = 'subscription.expiry_notice_days')
                  AND NOT EXISTS (SELECT 1 FROM notifications n
                                  WHERE n.recipient_id = s.user_id AND n.title = 'Membership ending soon'
                                    AND n.created_at > now() - interval '20 hours')
                """, today, today, today);
    }

    // Ngày mai có buổi lớp (đã đăng ký) hoặc buổi 1-1 (đã thanh toán). Mỗi người một thông báo, đếm số buổi
    @Transactional
    public int remindTomorrowSessions(LocalDate today) {
        LocalDate tomorrow = today.plusDays(1);
        return jdbcTemplate.update("""
                INSERT INTO notifications (recipient_id, type, channel, title, content)
                SELECT t.user_id, 'BOOKING', 'APP', 'Tomorrow you have training',
                       'You have ' || count(*) || ' session(s) on ' || ?::date || '. Open your timetable for the details.'
                FROM (
                    SELECT r.user_id FROM class_sessions cs
                    JOIN class_registrations r ON r.class_id = cs.class_id AND r.status = 'REGISTERED'
                    WHERE cs.session_date = ?::date AND cs.status = 'SCHEDULED'
                    UNION ALL
                    SELECT s.member_id FROM coach_slots s
                    WHERE s.status = 'BOOKED' AND s.member_id IS NOT NULL
                      AND (s.start_at AT TIME ZONE 'Asia/Ho_Chi_Minh')::date = ?::date
                ) t
                GROUP BY t.user_id
                HAVING NOT EXISTS (SELECT 1 FROM notifications n
                                   WHERE n.recipient_id = t.user_id AND n.title = 'Tomorrow you have training'
                                     AND n.created_at > now() - interval '20 hours')
                """, tomorrow, tomorrow, tomorrow);
    }
}
