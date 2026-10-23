package com.swp391.sportcentermanagementsystem_v2.service;

import com.swp391.sportcentermanagementsystem_v2.dto.requestDTO.CourseRequest;
import com.swp391.sportcentermanagementsystem_v2.dto.responseDTO.CourseResponse;
import com.swp391.sportcentermanagementsystem_v2.entity.Course;
import com.swp391.sportcentermanagementsystem_v2.entity.SportCategory;
import com.swp391.sportcentermanagementsystem_v2.exception.BusinessRuleException;
import com.swp391.sportcentermanagementsystem_v2.repository.CourseRepository;
import com.swp391.sportcentermanagementsystem_v2.repository.SportCategoryRepository;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;

import java.util.List;

@Service
public class CourseService {

    // Thứ tự cấp độ, dùng để so sánh khóa tiên quyết
    private static final List<String> LEVEL_ORDER = List.of("BEGINNER", "INTERMEDIATE", "ADVANCED");

    private final CourseRepository courseRepository;
    private final SportCategoryRepository sportCategoryRepository;

    public CourseService(CourseRepository courseRepository,
                         SportCategoryRepository sportCategoryRepository) {
        this.courseRepository = courseRepository;
        this.sportCategoryRepository = sportCategoryRepository;
    }

    @Transactional(readOnly = true)
    public List<CourseResponse> search(Long sportCategoryId, String level, boolean active) {
        return courseRepository.search(active, sportCategoryId, level).stream()
                .map(CourseResponse::new)
                .toList();
    }

    @Transactional(readOnly = true)
    public CourseResponse getById(Long id) {
        return new CourseResponse(findDetail(id));
    }

    @Transactional
    public CourseResponse create(CourseRequest request) {
        SportCategory category = findCategory(request.getSportCategoryId());
        String name = request.getName().trim();

        if (courseRepository.existsByCategoryIdAndName(category.getId(), name)) {
            throw new BusinessRuleException("Course name already exists in this sport category");
        }

        Course course = new Course();
        course.setCategory(category);
        apply(course, request, name, null);

        return new CourseResponse(courseRepository.save(course));
    }

    @Transactional
    public CourseResponse update(Long id, CourseRequest request) {
        Course course = findDetail(id);
        SportCategory category = findCategory(request.getSportCategoryId());
        String name = request.getName().trim();

        if (courseRepository.existsByCategoryIdAndNameAndIdNot(category.getId(), name, id)) {
            throw new BusinessRuleException("Course name already exists in this sport category");
        }

        course.setCategory(category);
        apply(course, request, name, id);

        return new CourseResponse(courseRepository.save(course));
    }

    // Ngừng bán thay cho xóa: class đã mở từ course này vẫn giữ nguyên
    @Transactional
    public CourseResponse setActive(Long id, boolean active) {
        Course course = findDetail(id);
        course.setActive(active);
        return new CourseResponse(courseRepository.save(course));
    }

    private void apply(Course course, CourseRequest request, String name, Long selfId) {
        course.setName(name);
        course.setDescription(request.getDescription());
        course.setLevel(request.getLevel());
        course.setTotalSessions(request.getTotalSessions());
        course.setDefaultTuition(request.getDefaultTuition());
        course.setPrerequisiteCourse(resolvePrerequisite(request, selfId));
    }

    // BR-CRS-02: khóa tiên quyết cùng môn và cấp thấp hơn.
    // Cấp thấp hơn nghiêm ngặt nên không thể tạo vòng lặp giữa các khóa.
    private Course resolvePrerequisite(CourseRequest request, Long selfId) {
        Long prerequisiteId = request.getPrerequisiteCourseId();
        if (prerequisiteId == null) {
            return null;
        }
        if (prerequisiteId.equals(selfId)) {
            throw new BusinessRuleException("A course cannot be its own prerequisite");
        }

        Course prerequisite = courseRepository.findDetailById(prerequisiteId)
                .orElseThrow(() -> new ResponseStatusException(
                        HttpStatus.BAD_REQUEST, "Prerequisite course does not exist with ID: " + prerequisiteId));

        if (!prerequisite.getCategory().getId().equals(request.getSportCategoryId())) {
            throw new BusinessRuleException("Prerequisite course must be in the same sport category");
        }
        if (LEVEL_ORDER.indexOf(prerequisite.getLevel()) >= LEVEL_ORDER.indexOf(request.getLevel())) {
            throw new BusinessRuleException("Prerequisite course must be a lower level");
        }
        return prerequisite;
    }

    private Course findDetail(Long id) {
        return courseRepository.findDetailById(id)
                .orElseThrow(() -> new ResponseStatusException(
                        HttpStatus.NOT_FOUND, "Course does not exist with ID: " + id));
    }

    private SportCategory findCategory(Long categoryId) {
        return sportCategoryRepository.findById(categoryId)
                .orElseThrow(() -> new ResponseStatusException(
                        HttpStatus.BAD_REQUEST, "Sport category does not exist with ID: " + categoryId));
    }
}
