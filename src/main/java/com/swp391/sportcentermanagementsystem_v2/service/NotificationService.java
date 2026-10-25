package com.swp391.sportcentermanagementsystem_v2.service;

import com.swp391.sportcentermanagementsystem_v2.dto.responseDTO.NotificationListResponse;
import com.swp391.sportcentermanagementsystem_v2.dto.responseDTO.NotificationResponse;
import com.swp391.sportcentermanagementsystem_v2.repository.UserRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;

import java.time.OffsetDateTime;
import java.util.List;

// BR-NTF-01: thông báo trong ứng dụng, ghi vào bảng notifications ngay trong transaction của thao tác gốc (thao tác bị
// hủy thì thông báo cũng không còn). Dùng JdbcTemplate thay vì entity để không trùng với entity của Flow 1 về sau.
// shortcut: chưa gửi email (dự án chưa có thư viện gửi mail), cột channel đã sẵn cho EMAIL khi Flow 1 tích hợp
@Service
@RequiredArgsConstructor
public class NotificationService {

    private static final int LIST_LIMIT = 50;
    private static final int TITLE_MAX = 200;

    private final JdbcTemplate jdbcTemplate;
    private final UserRepository userRepository;

    // type: một trong ANNOUNCEMENT, BOOKING, PAYMENT, WAITLIST, MAKEUP, MEMBERSHIP, SYSTEM (ràng buộc của bảng)
    @Transactional
    public void send(Long recipientId, String type, String title, String content) {
        jdbcTemplate.update("""
                INSERT INTO notifications (recipient_id, type, channel, title, content) VALUES (?, ?, 'APP', ?, ?)
                """, recipientId, type, truncate(title), content);
    }

    // Báo cho mọi học viên đang giữ chỗ hoặc đã ghi danh của class
    @Transactional
    public void sendToClass(Long classId, String type, String title, String content) {
        jdbcTemplate.update("""
                INSERT INTO notifications (recipient_id, type, channel, title, content)
                SELECT r.user_id, ?, 'APP', ?, ? FROM class_registrations r
                WHERE r.class_id = ? AND r.status IN ('REGISTERED', 'PENDING_PAYMENT')
                """, type, truncate(title), content, classId);
    }

    @Transactional(readOnly = true)
    public NotificationListResponse myNotifications(String email, boolean unreadOnly) {
        Long userId = userIdOf(email);
        List<NotificationResponse> items = jdbcTemplate.query("""
                SELECT id, type, title, content, is_read, created_at FROM notifications
                WHERE recipient_id = ? AND (? = false OR is_read = false)
                ORDER BY created_at DESC, id DESC LIMIT ?
                """, (rs, i) -> new NotificationResponse(rs.getLong("id"), rs.getString("type"), rs.getString("title"),
                rs.getString("content"), rs.getBoolean("is_read"), rs.getObject("created_at", OffsetDateTime.class)),
                userId, unreadOnly, LIST_LIMIT);
        Long unread = jdbcTemplate.queryForObject(
                "SELECT count(*) FROM notifications WHERE recipient_id = ? AND is_read = false", Long.class, userId);
        return new NotificationListResponse(unread == null ? 0 : unread, items);
    }

    // Chỉ đánh dấu được thông báo của chính mình; của người khác cũng báo không tìm thấy để không lộ sự tồn tại
    @Transactional
    public void markRead(String email, Long notificationId) {
        int updated = jdbcTemplate.update("UPDATE notifications SET is_read = true WHERE id = ? AND recipient_id = ?",
                notificationId, userIdOf(email));
        if (updated == 0) {
            throw new ResponseStatusException(HttpStatus.NOT_FOUND, "Notification not found");
        }
    }

    @Transactional
    public int markAllRead(String email) {
        return jdbcTemplate.update("UPDATE notifications SET is_read = true WHERE recipient_id = ? AND is_read = false",
                userIdOf(email));
    }

    private Long userIdOf(String email) {
        return userRepository.findByEmail(email)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "User not found")).getId();
    }

    private String truncate(String title) {
        return title.length() > TITLE_MAX ? title.substring(0, TITLE_MAX) : title;
    }
}
