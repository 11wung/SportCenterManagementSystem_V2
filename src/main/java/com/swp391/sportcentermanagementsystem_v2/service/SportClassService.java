package com.swp391.sportcentermanagementsystem_v2.service;

import com.swp391.sportcentermanagementsystem_v2.dto.requestDTO.CreateSportClassRequest;
import com.swp391.sportcentermanagementsystem_v2.dto.requestDTO.UpdateSportClassRequest;
import com.swp391.sportcentermanagementsystem_v2.dto.responseDTO.SportClassResponse;
import com.swp391.sportcentermanagementsystem_v2.entity.CoachProfile;
import com.swp391.sportcentermanagementsystem_v2.entity.Course;
import com.swp391.sportcentermanagementsystem_v2.entity.SportClass;
import com.swp391.sportcentermanagementsystem_v2.entity.User;
import com.swp391.sportcentermanagementsystem_v2.exception.BusinessRuleException;
import com.swp391.sportcentermanagementsystem_v2.repository.ClassSessionRepository;
import com.swp391.sportcentermanagementsystem_v2.repository.CoachProfileRepository;
import com.swp391.sportcentermanagementsystem_v2.repository.CourseRepository;
import com.swp391.sportcentermanagementsystem_v2.repository.SportClassRepository;
import com.swp391.sportcentermanagementsystem_v2.repository.UserRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;

import java.time.LocalDate;
import java.time.ZoneId;
import java.util.List;

@Service
@RequiredArgsConstructor
public class SportClassService {

    static final ZoneId CENTER_ZONE = ZoneId.of("Asia/Ho_Chi_Minh");

    private final SportClassRepository sportClassRepository;
    private final CourseRepository courseRepository;
    private final UserRepository userRepository;
    private final CoachProfileRepository coachProfileRepository;
    private final ClassSessionRepository classSessionRepository;

    // BR-CLS-01: tạo class ở trạng thái nháp từ một course
    @Transactional
    public SportClassResponse createSportClass(CreateSportClassRequest request) {
        Course course = courseRepository.findDetailById(request.getCourseId())
                .orElseThrow(() -> new ResponseStatusException(
                        HttpStatus.BAD_REQUEST, "Course not found with id: " + request.getCourseId()));
        if (!course.isActive()) {
            throw new BusinessRuleException("Course is no longer offered");
        }

        User coach = findTeachingCoach(request.getCoachId(), course.getCategory().getId());
        validateDates(request.getStartDate(), request.getRegistrationOpensOn());

        SportClass sportClass = new SportClass();
        sportClass.setCourse(course);
        sportClass.setCoach(coach);
        sportClass.setName(request.getName().trim());
        sportClass.setDescription(request.getDescription());
        sportClass.setMaxCapacity(request.getMaxCapacity());
        sportClass.setTuition(request.getTuition() != null ? request.getTuition() : course.getDefaultTuition());
        sportClass.setStartDate(request.getStartDate());
        sportClass.setRegistrationOpensOn(request.getRegistrationOpensOn());

        return toResponse(sportClassRepository.save(sportClass));
    }

    // Chỉ sửa được khi còn nháp. Sau khi mở đăng ký là phạm vi của BR-CLS-04
    @Transactional
    public SportClassResponse updateSportClass(Long id, UpdateSportClassRequest request) {
        // Khóa dòng lớp để không có thao tác nào chen vào giữa lúc đang kiểm tra
        SportClass sportClass = sportClassRepository.findByIdForUpdate(id)
                .orElseThrow(() -> new ResponseStatusException(
                        HttpStatus.NOT_FOUND, "Sport class not found with id: " + id));
        ClassScheduleService.requireDraft(sportClass);

        User coach = findTeachingCoach(request.getCoachId(), sportClass.getCourse().getCategory().getId());
        validateDates(request.getStartDate(), request.getRegistrationOpensOn());

        // Đổi coach hoặc ngày khai giảng thì các buổi đã sinh không còn đúng, phải sinh lại
        boolean sessionsOutdated = !sportClass.getCoach().getId().equals(coach.getId())
                || !sportClass.getStartDate().equals(request.getStartDate());
        if (sessionsOutdated) {
            classSessionRepository.deleteByClassId(id);
        }

        sportClass.setCoach(coach);
        sportClass.setName(request.getName().trim());
        sportClass.setDescription(request.getDescription());
        sportClass.setMaxCapacity(request.getMaxCapacity());
        sportClass.setTuition(request.getTuition());
        sportClass.setStartDate(request.getStartDate());
        sportClass.setRegistrationOpensOn(request.getRegistrationOpensOn());

        // saveAndFlush để lỗi từ trigger database (vd. sức chứa vượt phòng) nổ ngay trong hàm này
        return toResponse(sportClassRepository.saveAndFlush(sportClass));
    }

    @Transactional(readOnly = true)
    public List<SportClassResponse> getAllSportClasses() {
        return sportClassRepository.findAllByOrderByIdDesc()
                .stream()
                .map(this::toResponse)
                .toList();
    }

    @Transactional(readOnly = true)
    public SportClassResponse getSportClassById(Long id) {
        SportClass sportClass = sportClassRepository.findWithDetailsById(id)
                .orElseThrow(() -> new ResponseStatusException(
                        HttpStatus.NOT_FOUND, "Sport class not found with id: " + id));

        return toResponse(sportClass);
    }

    // BR-SCH-04: coach phải có vai trò COACH, đang hoạt động và dạy đúng môn của course
    private User findTeachingCoach(Long coachId, Long categoryId) {
        User coach = userRepository.findById(coachId)
                .orElseThrow(() -> new ResponseStatusException(
                        HttpStatus.BAD_REQUEST, "Coach not found with id: " + coachId));

        if (!Boolean.TRUE.equals(coach.getIsActive())) {
            throw new BusinessRuleException("Coach not active");
        }
        if (coach.getRole() == null || !"COACH".equalsIgnoreCase(coach.getRole().getCode())) {
            throw new BusinessRuleException("Selected user does not have the role of Coach");
        }

        CoachProfile profile = coachProfileRepository.findById(coachId)
                .orElseThrow(() -> new BusinessRuleException("Coach has no profile"));
        boolean teachesSport = profile.getSports().stream().anyMatch(s -> s.getId().equals(categoryId));
        if (!teachesSport) {
            throw new BusinessRuleException("Coach does not teach the sport of this course");
        }
        return coach;
    }

    private void validateDates(LocalDate startDate, LocalDate registrationOpensOn) {
        if (startDate.isBefore(LocalDate.now(CENTER_ZONE))) {
            throw new BusinessRuleException("Start date cannot be in the past");
        }
        if (registrationOpensOn.isAfter(startDate)) {
            throw new BusinessRuleException("Registration must open on or before the start date");
        }
    }

    private SportClassResponse toResponse(SportClass sportClass) {
        SportClassResponse response = new SportClassResponse();
        response.setId(sportClass.getId());
        response.setCourseId(sportClass.getCourse().getId());
        response.setCourseName(sportClass.getCourse().getName());
        response.setLevel(sportClass.getCourse().getLevel());
        response.setTotalSessions(sportClass.getCourse().getTotalSessions());
        response.setCategoryId(sportClass.getCourse().getCategory().getId());
        response.setCategoryName(sportClass.getCourse().getCategory().getName());
        response.setCoachId(sportClass.getCoach().getId());
        response.setCoachName(sportClass.getCoach().getFullName());
        response.setName(sportClass.getName());
        response.setDescription(sportClass.getDescription());
        response.setMaxCapacity(sportClass.getMaxCapacity());
        response.setTuition(sportClass.getTuition());
        response.setStartDate(sportClass.getStartDate());
        response.setRegistrationOpensOn(sportClass.getRegistrationOpensOn());
        response.setStatus(sportClass.getStatus());
        response.setCreatedAt(sportClass.getCreatedAt());
        response.setUpdatedAt(sportClass.getUpdatedAt());
        return response;
    }
}
