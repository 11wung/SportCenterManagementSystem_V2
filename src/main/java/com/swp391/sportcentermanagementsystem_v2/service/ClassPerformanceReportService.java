package com.swp391.sportcentermanagementsystem_v2.service;

import com.swp391.sportcentermanagementsystem_v2.dto.responseDTO.ClassPerformanceResponse;
import com.swp391.sportcentermanagementsystem_v2.dto.responseDTO.UnmarkedSessionResponse;
import com.swp391.sportcentermanagementsystem_v2.exception.BusinessRuleException;
import lombok.RequiredArgsConstructor;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDate;
import java.util.List;

// Báo cáo hiệu quả từng lớp cho manager: tỉ lệ lấp đầy và tỉ lệ chuyên cần.
// Tỉ lệ chuyên cần = (có mặt + đến muộn) / (có mặt + đến muộn + vắng + có phép), cùng công thức ở trang tập luyện của hội viên;
// buổi học bù bị từ chối (DECLINED_MAKEUP) không tính.
@Service
@RequiredArgsConstructor
public class ClassPerformanceReportService {

    private final JdbcTemplate jdbcTemplate;
    private final NotificationService notificationService;

    @Transactional(readOnly = true)
    public List<ClassPerformanceResponse> getClassPerformance() {
        return jdbcTemplate.query("""
                SELECT c.id, c.name, cat.name AS sport_name, u.full_name AS coach_name, c.status, c.start_date, c.max_capacity,
                       (SELECT count(*) FROM class_registrations r
                         WHERE r.class_id = c.id AND r.status IN ('PENDING_PAYMENT', 'REGISTERED')) AS reserved,
                       (SELECT count(*) FROM class_attendances a JOIN class_sessions s ON s.id = a.session_id
                         WHERE s.class_id = c.id AND a.status IN ('PRESENT', 'LATE')) AS present,
                       (SELECT count(*) FROM class_attendances a JOIN class_sessions s ON s.id = a.session_id
                         WHERE s.class_id = c.id AND a.status IN ('PRESENT', 'LATE', 'ABSENT', 'EXCUSED')) AS marked,
                       (SELECT count(DISTINCT a.session_id) FROM class_attendances a JOIN class_sessions s ON s.id = a.session_id
                         WHERE s.class_id = c.id) AS sessions_marked,
                       (SELECT count(*) FROM class_sessions s WHERE s.class_id = c.id AND s.status <> 'CANCELED_BY_CENTER'
                         AND (s.session_date + s.end_time) <= (now() AT TIME ZONE 'Asia/Ho_Chi_Minh')) AS sessions_past,
                       (SELECT count(*) FROM class_sessions s WHERE s.class_id = c.id AND s.status <> 'CANCELED_BY_CENTER') AS total_sessions
                FROM sport_classes c
                JOIN courses co ON co.id = c.course_id
                JOIN sport_categories cat ON cat.id = co.category_id
                JOIN users u ON u.id = c.coach_id
                ORDER BY c.start_date DESC, c.id DESC
                """, (rs, i) -> {
            int capacity = rs.getInt("max_capacity");
            long reserved = rs.getLong("reserved");
            long marked = rs.getLong("marked");
            long present = rs.getLong("present");
            return new ClassPerformanceResponse(
                    rs.getLong("id"), rs.getString("name"), rs.getString("sport_name"), rs.getString("coach_name"),
                    rs.getString("status"), rs.getObject("start_date", LocalDate.class), capacity, reserved,
                    capacity == 0 ? 0 : (int) Math.round(reserved * 100.0 / capacity),
                    marked, marked == 0 ? null : (int) Math.round(present * 100.0 / marked),
                    present, rs.getLong("sessions_marked"), rs.getLong("sessions_past"), rs.getLong("total_sessions"));
        });
    }

    // Buổi đã quá 48 giờ sau giờ kết thúc mà chưa chốt điểm danh, của một lớp (hoặc mọi lớp nếu classId null)
    @Transactional(readOnly = true)
    public List<UnmarkedSessionResponse> unmarkedSessions(Long classId) {
        return jdbcTemplate.query("""
                SELECT s.id, s.class_id, c.name, s.session_no, s.session_date, s.start_time, u.full_name, s.attendance_reopened_until
                FROM class_sessions s
                JOIN sport_classes c ON c.id = s.class_id
                JOIN users u ON u.id = s.coach_id
                WHERE (CAST(? AS bigint) IS NULL OR s.class_id = ?)
                  AND s.status IN ('SCHEDULED', 'DONE') AND s.attendance_finalized_at IS NULL
                  AND c.status NOT IN ('CANCELED', 'DRAFT')
                  AND (s.session_date + s.end_time) + interval '48 hours' < (now() AT TIME ZONE 'Asia/Ho_Chi_Minh')
                ORDER BY s.session_date, s.start_time
                """, (rs, i) -> new UnmarkedSessionResponse(rs.getLong(1), rs.getLong(2), rs.getString(3), rs.getInt(4),
                rs.getObject(5, LocalDate.class), rs.getObject(6, java.time.LocalTime.class), rs.getString(7),
                rs.getObject(8, java.time.OffsetDateTime.class)), classId, classId);
    }

    // Manager mở lại điểm danh cho buổi quá hạn chưa chốt: coach có thêm 24 giờ, coach được báo kèm đường dẫn tới buổi đó
    @Transactional
    public UnmarkedSessionResponse reopenAttendance(Long sessionId) {
        UnmarkedSessionResponse session = unmarkedSessions(null).stream()
                .filter(x -> x.sessionId().equals(sessionId)).findFirst()
                .orElseThrow(() -> new BusinessRuleException("Chỉ mở lại được buổi đã quá hạn điểm danh mà coach chưa chốt"));
        jdbcTemplate.update("UPDATE class_sessions SET attendance_reopened_until = now() + interval '24 hours' WHERE id = ?", sessionId);
        Long coachId = jdbcTemplate.queryForObject("SELECT coach_id FROM class_sessions WHERE id = ?", Long.class, sessionId);
        notificationService.send(coachId, "ANNOUNCEMENT", "Đã mở lại điểm danh",
                "Quản lý mở lại điểm danh buổi " + session.sessionNo() + " lớp " + session.className()
                        + " ngày " + session.sessionDate() + ". Bạn có 24 giờ để điểm danh và chốt.",
                "/coach/training?tab=sessions&session=" + sessionId);
        return unmarkedSessions(null).stream().filter(x -> x.sessionId().equals(sessionId)).findFirst().orElse(session);
    }

    // Học viên của một lớp (đang giữ chỗ, đã ghi danh, đã rời), kèm chuyên cần của từng người
    @Transactional(readOnly = true)
    public List<com.swp391.sportcentermanagementsystem_v2.dto.responseDTO.ClassRosterResponse> roster(Long classId) {
        return jdbcTemplate.query("""
                SELECT r.id, u.full_name, u.phone, r.status, r.registered_at,
                       (SELECT count(*) FROM class_attendances a JOIN class_sessions s ON s.id = a.session_id
                         WHERE s.class_id = r.class_id AND a.user_id = r.user_id AND a.status IN ('PRESENT', 'LATE')) AS present,
                       (SELECT count(*) FROM class_attendances a JOIN class_sessions s ON s.id = a.session_id
                         WHERE s.class_id = r.class_id AND a.user_id = r.user_id AND a.status IN ('PRESENT', 'LATE', 'ABSENT', 'EXCUSED')) AS marked
                FROM class_registrations r JOIN users u ON u.id = r.user_id
                WHERE r.class_id = ? AND r.status IN ('REGISTERED', 'PENDING_PAYMENT', 'LEFT')
                ORDER BY CASE r.status WHEN 'REGISTERED' THEN 0 WHEN 'PENDING_PAYMENT' THEN 1 ELSE 2 END, u.full_name
                """, (rs, i) -> new com.swp391.sportcentermanagementsystem_v2.dto.responseDTO.ClassRosterResponse(
                rs.getLong(1), rs.getString(2), rs.getString(3), rs.getString(4),
                rs.getObject(5, java.time.OffsetDateTime.class), rs.getLong(6), rs.getLong(7)), classId);
    }
}
