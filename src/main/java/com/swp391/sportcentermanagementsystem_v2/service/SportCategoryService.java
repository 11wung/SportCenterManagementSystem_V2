package com.swp391.sportcentermanagementsystem_v2.service;

import com.swp391.sportcentermanagementsystem_v2.dto.responseDTO.RemovalResponse;

import com.swp391.sportcentermanagementsystem_v2.dto.requestDTO.SportCategoryRequest;
import com.swp391.sportcentermanagementsystem_v2.dto.responseDTO.SportCategoryResponse;
import com.swp391.sportcentermanagementsystem_v2.entity.SportCategory;
import com.swp391.sportcentermanagementsystem_v2.exception.BusinessRuleException;
import com.swp391.sportcentermanagementsystem_v2.repository.SportCategoryRepository;
import jakarta.persistence.EntityNotFoundException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.ArrayList;
import java.util.List;

@Service
public class SportCategoryService {

    private final SportCategoryRepository sportCategoryRepository;
    private final org.springframework.jdbc.core.JdbcTemplate jdbcTemplate;

    public SportCategoryService(SportCategoryRepository sportCategoryRepository, org.springframework.jdbc.core.JdbcTemplate jdbcTemplate) {
        this.sportCategoryRepository = sportCategoryRepository;
        this.jdbcTemplate = jdbcTemplate;
    }

    // Môn đã ngừng sử dụng không mở được khóa, lớp, khóa 1-1 hay nhận đăng ký mới
    public static void requireInUse(SportCategory category) {
        if (category.getArchivedAt() != null) {
            throw new BusinessRuleException("Môn " + category.getName() + " đã ngừng sử dụng");
        }
    }

    // Mặc định chỉ trả môn đang dùng (trang chủ, hội viên); manager xem cả môn đã ngừng
    public List<SportCategoryResponse> getAll(boolean includeArchived) {
        List<SportCategoryResponse> responses = new ArrayList<>();
        for (SportCategory category : sportCategoryRepository.findAll()) {
            if (includeArchived || category.getArchivedAt() == null) {
                responses.add(new SportCategoryResponse(category));
            }
        }
        return responses;
    }

    public SportCategoryResponse getById(Long id) {
        SportCategory category = sportCategoryRepository.findById(id)
                .orElseThrow(() -> new EntityNotFoundException(
                        "Không tìm thấy môn thể thao có mã " + id));

        return new SportCategoryResponse(category);
    }

    @Transactional
    public SportCategoryResponse create(SportCategoryRequest request) {
        String name = request.getName().trim();

        if (sportCategoryRepository.existsByName(name)) {
            throw new BusinessRuleException("Tên môn thể thao đã tồn tại");
        }

        SportCategory category = new SportCategory();
        category.setName(name);
        category.setDescription(request.getDescription());

        SportCategory savedCategory = sportCategoryRepository.save(category);

        return new SportCategoryResponse(savedCategory);
    }

    @Transactional
    public SportCategoryResponse update(Long id, SportCategoryRequest request) {
        SportCategory category = sportCategoryRepository.findById(id)
                .orElseThrow(() -> new EntityNotFoundException(
                        "Không tìm thấy môn thể thao có mã " + id));

        String name = request.getName().trim();

        if (sportCategoryRepository.existsByNameAndIdNot(name, id)) {
            throw new BusinessRuleException("Tên môn thể thao đã tồn tại");
        }

        category.setName(name);
        category.setDescription(request.getDescription());

        SportCategory updatedCategory = sportCategoryRepository.save(category);

        return new SportCategoryResponse(updatedCategory);
    }

    // Môn chưa từng có khóa, khóa 1-1, buổi 1-1 hay yêu cầu 1-1 thì xóa hẳn (kèm liên kết với coach và phòng);
    // đã có thì chỉ ngừng sử dụng để giữ lịch sử, lớp đang học vẫn học tiếp
    @Transactional
    public RemovalResponse delete(Long id) {
        SportCategory category = sportCategoryRepository.findById(id)
                .orElseThrow(() -> new EntityNotFoundException(
                        "Không tìm thấy môn thể thao có mã " + id));
        Long used = jdbcTemplate.queryForObject("""
                SELECT (SELECT count(*) FROM courses WHERE category_id = ?)
                     + (SELECT count(*) FROM one_on_one_packages WHERE category_id = ?)
                     + (SELECT count(*) FROM coach_slots WHERE category_id = ?)
                     + (SELECT count(*) FROM coach_slot_requests WHERE category_id = ?)
                """, Long.class, id, id, id, id);
        if (used == null || used == 0) {
            jdbcTemplate.update("DELETE FROM coach_sports WHERE category_id = ?", id);
            jdbcTemplate.update("DELETE FROM room_sports WHERE category_id = ?", id);
            sportCategoryRepository.delete(category);
            return new RemovalResponse("DELETED", 0);
        }
        category.setArchivedAt(java.time.OffsetDateTime.now());
        sportCategoryRepository.save(category);
        return new RemovalResponse("ARCHIVED", upcomingSessions(id));
    }

    @Transactional
    public SportCategoryResponse restore(Long id) {
        SportCategory category = sportCategoryRepository.findById(id)
                .orElseThrow(() -> new EntityNotFoundException("Không tìm thấy môn thể thao có mã " + id));
        category.setArchivedAt(null);
        return new SportCategoryResponse(sportCategoryRepository.save(category));
    }

    // Để hộp xác nhận nói trước: xóa hẳn hay ngừng sử dụng, còn bao nhiêu buổi sắp tới
    @Transactional(readOnly = true)
    public RemovalResponse usage(Long id) {
        Long used = jdbcTemplate.queryForObject("""
                SELECT (SELECT count(*) FROM courses WHERE category_id = ?)
                     + (SELECT count(*) FROM one_on_one_packages WHERE category_id = ?)
                     + (SELECT count(*) FROM coach_slots WHERE category_id = ?)
                     + (SELECT count(*) FROM coach_slot_requests WHERE category_id = ?)
                """, Long.class, id, id, id, id);
        return new RemovalResponse(used == null || used == 0 ? "DELETED" : "ARCHIVED", upcomingSessions(id));
    }

    private long upcomingSessions(Long id) {
        Long n = jdbcTemplate.queryForObject("""
                SELECT (SELECT count(*) FROM class_sessions s JOIN sport_classes c ON c.id = s.class_id JOIN courses co ON co.id = c.course_id
                         WHERE co.category_id = ? AND s.status = 'SCHEDULED' AND s.session_date >= current_date)
                     + (SELECT count(*) FROM coach_slots WHERE category_id = ? AND status IN ('HELD', 'BOOKED') AND start_at > now())
                """, Long.class, id, id);
        return n == null ? 0 : n;
    }
}
