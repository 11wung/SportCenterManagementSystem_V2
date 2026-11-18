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

// Ảnh minh họa của môn và lớp, lưu trong database. Nội dung ảnh không nằm trong entity để các danh sách không phải
// tải ảnh theo; ảnh chỉ được đọc qua đường dẫn riêng (GET .../image) và trình duyệt tự lưu lại.
@Service
@RequiredArgsConstructor
public class ImageService {

    public static final String SPORT = "sport_categories";
    public static final String CLASS = "sport_classes";
    static final int MAX_BYTES = 500 * 1024;

    public record Image(byte[] data, String type) {}

    private final JdbcTemplate jdbcTemplate;

    // Đường dẫn ảnh cho giao diện; null khi chưa có ảnh. ?v= đổi theo lần cập nhật để trình duyệt tải ảnh mới
    public static String url(String basePath, Long id, String type, OffsetDateTime updatedAt) {
        if (type == null) {
            return null;
        }
        return basePath + "/" + id + "/image?v=" + (updatedAt == null ? 0 : updatedAt.toInstant().toEpochMilli());
    }

    // Loại ảnh theo nội dung file (không tin tên file hay Content-Type do người gửi khai)
    static String detectType(byte[] b) {
        if (b.length >= 8 && (b[0] & 0xFF) == 0x89 && b[1] == 'P' && b[2] == 'N' && b[3] == 'G') {
            return "image/png";
        }
        if (b.length >= 3 && (b[0] & 0xFF) == 0xFF && (b[1] & 0xFF) == 0xD8 && (b[2] & 0xFF) == 0xFF) {
            return "image/jpeg";
        }
        if (b.length >= 12 && b[0] == 'R' && b[1] == 'I' && b[2] == 'F' && b[3] == 'F'
                && b[8] == 'W' && b[9] == 'E' && b[10] == 'B' && b[11] == 'P') {
            return "image/webp";
        }
        return null;
    }

    @Transactional
    public void save(String table, Long id, MultipartFile file) {
        if (file == null || file.isEmpty()) {
            throw new BusinessRuleException("Hãy chọn một ảnh");
        }
        byte[] data;
        try {
            data = file.getBytes();
        } catch (IOException e) {
            throw new BusinessRuleException("Không đọc được ảnh, hãy thử ảnh khác");
        }
        if (data.length > MAX_BYTES) {
            throw new BusinessRuleException("Ảnh quá lớn, tối đa " + (MAX_BYTES / 1024) + " KB");
        }
        String type = detectType(data);
        if (type == null) {
            throw new BusinessRuleException("Chỉ nhận ảnh JPG, PNG hoặc WEBP");
        }
        // table chỉ nhận hai hằng số ở trên nên ghép chuỗi vào câu SQL an toàn
        int updated = jdbcTemplate.update("UPDATE " + requireTable(table)
                + " SET image_data = ?, image_type = ?, image_updated_at = now() WHERE id = ?", data, type, id);
        if (updated == 0) {
            throw new EntityNotFoundException("Không tìm thấy mục cần gắn ảnh");
        }
    }

    @Transactional
    public void clear(String table, Long id) {
        jdbcTemplate.update("UPDATE " + requireTable(table)
                + " SET image_data = NULL, image_type = NULL, image_updated_at = NULL WHERE id = ?", id);
    }

    @Transactional(readOnly = true)
    public Image read(String table, Long id) {
        List<Image> found = jdbcTemplate.query("SELECT image_data, image_type FROM " + requireTable(table)
                        + " WHERE id = ? AND image_data IS NOT NULL",
                (rs, i) -> new Image(rs.getBytes("image_data"), rs.getString("image_type")), id);
        return found.isEmpty() ? null : found.get(0);
    }

    private static String requireTable(String table) {
        if (!SPORT.equals(table) && !CLASS.equals(table)) {
            throw new IllegalArgumentException("Bảng không hợp lệ: " + table);
        }
        return table;
    }
}
