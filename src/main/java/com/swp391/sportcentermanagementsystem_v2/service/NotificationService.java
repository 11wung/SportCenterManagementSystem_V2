package com.swp391.sportcentermanagementsystem_v2.service;

import com.swp391.sportcentermanagementsystem_v2.dto.responseDTO.NotificationListResponse;
import com.swp391.sportcentermanagementsystem_v2.dto.responseDTO.NotificationResponse;
import com.swp391.sportcentermanagementsystem_v2.repository.UserRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;
import org.springframework.web.server.ResponseStatusException;

import java.time.OffsetDateTime;
import java.util.List;

// BR-NTF-01: thông báo trong ứng dụng, ghi vào bảng notifications ngay trong transaction của thao tác gốc (thao tác bị
// hủy thì thông báo cũng không còn). Dùng JdbcTemplate thay vì entity để không trùng với entity của Flow 1 về sau.
// Chỉ vài thông báo quan trọng gửi thêm email (sendWithEmail, sendToClassWithEmail); còn lại chỉ nằm trong ứng dụng.
@Service
@RequiredArgsConstructor
public class NotificationService {

    private static final int LIST_LIMIT = 50;
    private static final int TITLE_MAX = 200;

    private final JdbcTemplate jdbcTemplate;
    private final UserRepository userRepository;
    private final NotificationMailer mailer;

    // type: một trong ANNOUNCEMENT, BOOKING, PAYMENT, WAITLIST, MAKEUP, MEMBERSHIP, SYSTEM (ràng buộc của bảng)
    @Transactional
    public void send(Long recipientId, String type, String title, String content) {
        send(recipientId, type, title, content, null);
    }

    // link: trang mở ra khi bấm thông báo; null thì chọn theo vai trò người nhận và loại thông báo (defaultLink)
    @Transactional
    public void send(Long recipientId, String type, String title, String content, String link) {
        String role = jdbcTemplate.queryForList(
                "SELECT ro.code FROM users u JOIN roles ro ON ro.id = u.role_id WHERE u.id = ?", String.class, recipientId)
                .stream().findFirst().orElse("");
        jdbcTemplate.update("""
                INSERT INTO notifications (recipient_id, type, channel, title, content, link) VALUES (?, ?, 'APP', ?, ?, ?)
                """, recipientId, type, truncate(title), content, link != null ? link : defaultLink(role, type));
    }

    // Trang đích mặc định theo vai trò và loại thông báo, khớp với các tab của từng trang FE
    static String defaultLink(String role, String type) {
        return switch (role) {
            case "MEMBER" -> switch (type) {
                case "PAYMENT" -> "/member?tab=payments";
                case "MEMBERSHIP" -> "/member?tab=subscription";
                case "BOOKING" -> "/member?tab=slots";
                default -> "/member?tab=classes";
            };
            case "COACH" -> "/coach?view=schedule";
            case "MANAGER" -> switch (type) {
                case "BOOKING" -> "/manager?tab=coachslots";
                case "PAYMENT" -> "/manager?tab=refunds";
                default -> "/manager?tab=classes";
            };
            case "RECEPTIONIST" -> "/receptionist?view=desk";
            default -> null;
        };
    }

    // Như send, và gửi thêm email cho người nhận sau khi transaction commit (thao tác bị hủy thì không gửi mail)
    @Transactional
    public void sendWithEmail(Long recipientId, String type, String title, String content, String mailSubject, String mailMessage) {
        send(recipientId, type, title, content);
        userRepository.findById(recipientId)
                .ifPresent(u -> emailAfterCommit(u.getEmail(), u.getFullName(), mailSubject, mailMessage));
    }

    // Như sendToClass, và gửi thêm email cho từng học viên đang giữ chỗ hoặc đã ghi danh
    @Transactional
    public void sendToClassWithEmail(Long classId, String type, String title, String content, String mailSubject, String mailMessage) {
        List<Long> recipientIds = jdbcTemplate.queryForList(
                "SELECT r.user_id FROM class_registrations r WHERE r.class_id = ? AND r.status IN ('REGISTERED', 'PENDING_PAYMENT')",
                Long.class, classId);
        sendToClass(classId, type, title, content);
        userRepository.findAllById(recipientIds)
                .forEach(u -> emailAfterCommit(u.getEmail(), u.getFullName(), mailSubject, mailMessage));
    }

    private void emailAfterCommit(String to, String fullName, String subject, String message) {
        if (TransactionSynchronizationManager.isSynchronizationActive()) {
            TransactionSynchronizationManager.registerSynchronization(new TransactionSynchronization() {
                @Override
                public void afterCommit() {
                    mailer.send(to, fullName, subject, message);
                }
            });
        } else {
            mailer.send(to, fullName, subject, message);
        }
    }

    // Báo cho mọi học viên đang giữ chỗ hoặc đã ghi danh của class
    @Transactional
    public void sendToClass(Long classId, String type, String title, String content) {
        jdbcTemplate.update("""
                INSERT INTO notifications (recipient_id, type, channel, title, content, link)
                SELECT r.user_id, ?, 'APP', ?, ?, '/member?tab=classes' FROM class_registrations r
                WHERE r.class_id = ? AND r.status IN ('REGISTERED', 'PENDING_PAYMENT')
                """, type, truncate(title), content, classId);
    }

    @Transactional(readOnly = true)
    public NotificationListResponse myNotifications(String email, boolean unreadOnly) {
        Long userId = userIdOf(email);
        List<NotificationResponse> items = jdbcTemplate.query("""
                SELECT id, type, title, content, is_read, created_at, link FROM notifications
                WHERE recipient_id = ? AND (? = false OR is_read = false)
                ORDER BY created_at DESC, id DESC LIMIT ?
                """, (rs, i) -> new NotificationResponse(rs.getLong("id"), rs.getString("type"), rs.getString("title"),
                rs.getString("content"), rs.getBoolean("is_read"), rs.getObject("created_at", OffsetDateTime.class),
                rs.getString("link")),
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
            throw new ResponseStatusException(HttpStatus.NOT_FOUND, "Không tìm thấy thông báo");
        }
    }

    @Transactional
    public int markAllRead(String email) {
        return jdbcTemplate.update("UPDATE notifications SET is_read = true WHERE recipient_id = ? AND is_read = false",
                userIdOf(email));
    }

    private Long userIdOf(String email) {
        return userRepository.findByEmail(email)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "Không tìm thấy người dùng")).getId();
    }

    private String truncate(String title) {
        return title.length() > TITLE_MAX ? title.substring(0, TITLE_MAX) : title;
    }
}
