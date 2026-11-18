package com.swp391.sportcentermanagementsystem_v2.service;

import com.swp391.sportcentermanagementsystem_v2.exception.BusinessRuleException;
import jakarta.persistence.EntityNotFoundException;
import lombok.RequiredArgsConstructor;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.multipart.MultipartFile;

import java.io.IOException;
import java.time.OffsetDateTime;
import java.util.List;

// Video minh họa của môn (tùy chọn, hiện ở trang chủ), lưu trong database. Nội dung video không nằm trong entity để các
// danh sách không phải tải video theo; video chỉ được đọc qua đường dẫn riêng (GET .../video) và trình duyệt tự lưu lại.
// Độ dài tối đa do giao diện kiểm (backend không đọc được độ dài video); backend giới hạn loại file và dung lượng.
@Service
@RequiredArgsConstructor
public class VideoService {

    static final int MAX_BYTES = 5 * 1024 * 1024;

    public record Video(byte[] data, String type) {}

    private final JdbcTemplate jdbcTemplate;

    // Đường dẫn video cho giao diện; null khi môn chưa có video tải lên. ?v= đổi theo lần cập nhật để trình duyệt tải video mới
    public static String url(Long categoryId, String type, OffsetDateTime updatedAt) {
        if (type == null) {
            return null;
        }
        return "/api/sport-categories/" + categoryId + "/video?v=" + (updatedAt == null ? 0 : updatedAt.toInstant().toEpochMilli());
    }

    // Loại video theo nội dung file (không tin tên file hay Content-Type do người gửi khai):
    // MP4 có "ftyp" ở byte thứ 4, WEBM bắt đầu bằng 1A 45 DF A3
    static String detectType(byte[] b) {
        if (b.length >= 12 && b[4] == 'f' && b[5] == 't' && b[6] == 'y' && b[7] == 'p') {
            return "video/mp4";
        }
        if (b.length >= 4 && (b[0] & 0xFF) == 0x1A && (b[1] & 0xFF) == 0x45 && (b[2] & 0xFF) == 0xDF && (b[3] & 0xFF) == 0xA3) {
            return "video/webm";
        }
        return null;
    }

    @Transactional
    public void save(Long categoryId, MultipartFile file) {
        if (file == null || file.isEmpty()) {
            throw new BusinessRuleException("Hãy chọn một video");
        }
        byte[] data;
        try {
            data = file.getBytes();
        } catch (IOException e) {
            throw new BusinessRuleException("Không đọc được video, hãy thử video khác");
        }
        if (data.length > MAX_BYTES) {
            throw new BusinessRuleException("Video quá nặng, tối đa " + (MAX_BYTES / 1024 / 1024) + " MB");
        }
        String type = detectType(data);
        if (type == null) {
            throw new BusinessRuleException("Chỉ nhận video MP4 hoặc WEBM");
        }
        int updated = jdbcTemplate.update(
                "UPDATE sport_categories SET video_data = ?, video_type = ?, video_updated_at = now() WHERE id = ?",
                data, type, categoryId);
        if (updated == 0) {
            throw new EntityNotFoundException("Không tìm thấy môn thể thao có mã " + categoryId);
        }
    }

    @Transactional(readOnly = true)
    public Video read(Long categoryId) {
        List<Video> found = jdbcTemplate.query(
                "SELECT video_data, video_type FROM sport_categories WHERE id = ? AND video_data IS NOT NULL",
                (rs, i) -> new Video(rs.getBytes("video_data"), rs.getString("video_type")), categoryId);
        return found.isEmpty() ? null : found.get(0);
    }
}
