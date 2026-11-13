package com.swp391.sportcentermanagementsystem_v2.service;

import com.swp391.sportcentermanagementsystem_v2.dto.responseDTO.ClassPerformanceResponse;
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
}
