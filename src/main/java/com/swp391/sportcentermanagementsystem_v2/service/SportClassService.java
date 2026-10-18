package com.swp391.sportcentermanagementsystem_v2.service;

import com.swp391.sportcentermanagementsystem_v2.dto.requestDTO.CreateSportClassRequest;
import com.swp391.sportcentermanagementsystem_v2.dto.requestDTO.UpdateSportClassRequest;
import com.swp391.sportcentermanagementsystem_v2.dto.responseDTO.SportClassResponse;
import com.swp391.sportcentermanagementsystem_v2.entity.SportCategory;
import com.swp391.sportcentermanagementsystem_v2.entity.SportClass;
import com.swp391.sportcentermanagementsystem_v2.entity.User;
import com.swp391.sportcentermanagementsystem_v2.exception.BusinessRuleException;
import com.swp391.sportcentermanagementsystem_v2.repository.ClassRegistrationRepository;
import com.swp391.sportcentermanagementsystem_v2.repository.SportCategoryRepository;
import com.swp391.sportcentermanagementsystem_v2.repository.SportClassRepository;
import com.swp391.sportcentermanagementsystem_v2.repository.UserRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;

@Service
@RequiredArgsConstructor
public class SportClassService {

    private final SportClassRepository sportClassRepository;
    private final SportCategoryRepository sportCategoryRepository;
    private final UserRepository userRepository;
    private final ClassRegistrationRepository classRegistrationRepository;

    @Transactional
    public SportClassResponse createSportClass(CreateSportClassRequest request) {
        SportCategory category = sportCategoryRepository.findById(request.getCategoryId())
                .orElseThrow(() -> new BusinessRuleException("Sport category not found with id: " + request.getCategoryId()));

        User coach = userRepository.findById(request.getCoachId())
                .orElseThrow(() -> new BusinessRuleException("Coach not found with id: " + request.getCoachId()));

        validateCoach(coach);
        validateLevel(request.getLevel());
        validateCapacity(request.getMaxCapacity());
        validateTier(request.getRequiredTierLevel());

        SportClass sportClass = new SportClass();
        sportClass.setCategory(category);
        sportClass.setCoach(coach);
        sportClass.setName(request.getName().trim());
        sportClass.setDescription(request.getDescription());
        sportClass.setLevel(request.getLevel().trim().toUpperCase());
        sportClass.setMaxCapacity(request.getMaxCapacity());
        sportClass.setRequiredTierLevel(request.getRequiredTierLevel());
        sportClass.setStatus("ACTIVE");

        return toResponse(sportClassRepository.save(sportClass));
    }

    @Transactional
    public SportClassResponse updateSportClass(Long id, UpdateSportClassRequest request) {
        // Khóa dòng lớp để không có ai đăng ký chen vào giữa lúc đang kiểm tra
        SportClass sportClass = sportClassRepository.findByIdForUpdate(id)
                .orElseThrow(() -> new BusinessRuleException("Sport class not found with id: " + id));

        SportCategory category = sportCategoryRepository.findById(request.getCategoryId())
                .orElseThrow(() -> new BusinessRuleException("Sport category not found with id: " + request.getCategoryId()));

        User coach = userRepository.findById(request.getCoachId())
                .orElseThrow(() -> new BusinessRuleException("Coach not found with id: " + request.getCoachId()));

        validateCoach(coach);
        validateLevel(request.getLevel());
        validateCapacity(request.getMaxCapacity());
        validateTier(request.getRequiredTierLevel());
        validateStatus(request.getStatus());

        String newStatus = request.getStatus().trim().toUpperCase();
        validateAgainstRegistrations(sportClass, request.getMaxCapacity(), newStatus);
        validateAgainstSchedules(sportClass, coach, request.getMaxCapacity());

        sportClass.setCategory(category);
        sportClass.setCoach(coach);
        sportClass.setName(request.getName().trim());
        sportClass.setDescription(request.getDescription());
        sportClass.setLevel(request.getLevel().trim().toUpperCase());
        sportClass.setMaxCapacity(request.getMaxCapacity());
        sportClass.setRequiredTierLevel(request.getRequiredTierLevel());
        sportClass.setStatus(newStatus);

        return toResponse(sportClassRepository.save(sportClass));
    }

    @Transactional(readOnly = true)
    public List<SportClassResponse> getAllSportClasses() {
        return sportClassRepository.findAll()
                .stream()
                .map(this::toResponse)
                .toList();
    }

    @Transactional(readOnly = true)
    public SportClassResponse getSportClassById(Long id) {
        SportClass sportClass = sportClassRepository.findById(id)
                .orElseThrow(() -> new BusinessRuleException("Sport class not found with id: " + id));

        return toResponse(sportClass);
    }

    // Sửa lớp không được làm hỏng các đăng ký đang có
    private void validateAgainstRegistrations(SportClass sportClass, Integer newMaxCapacity, String newStatus) {
        long registered = classRegistrationRepository.countBySportClass_IdAndStatus(sportClass.getId(), "REGISTERED");
        if (newMaxCapacity < registered) {
            throw new BusinessRuleException("Max capacity (" + newMaxCapacity
                    + ") is lower than the number of registered members (" + registered + ")");
        }

        boolean closing = List.of("INACTIVE", "CANCELED").contains(newStatus);
        if (closing && !newStatus.equals(sportClass.getStatus())) {
            long active = classRegistrationRepository.countBySportClass_IdAndStatusIn(
                    sportClass.getId(), List.of("REGISTERED", "WAITLISTED"));
            if (active > 0) {
                throw new BusinessRuleException("Cannot change status to " + newStatus + " while " + active
                        + " registration(s) are still active. Cancel them first");
            }
        }
    }

    // Sửa lớp không được vi phạm rule lịch học: BR-CLS-01 (sức chứa phòng), BR-CLS-02 (coach trùng giờ)
    private void validateAgainstSchedules(SportClass sportClass, User newCoach, Integer newMaxCapacity) {
        Integer minRoomCapacity = sportClassRepository.findMinRoomCapacityByClassId(sportClass.getId());
        if (minRoomCapacity != null && newMaxCapacity > minRoomCapacity) {
            throw new BusinessRuleException("Max capacity (" + newMaxCapacity
                    + ") exceeds the capacity (" + minRoomCapacity + ") of a room assigned to this class");
        }

        boolean coachChanged = !sportClass.getCoach().getId().equals(newCoach.getId());
        if (coachChanged
                && sportClassRepository.countCoachScheduleConflicts(sportClass.getId(), newCoach.getId()) > 0) {
            throw new BusinessRuleException("Selected coach already has a class in a time slot of this class");
        }
    }

    private void validateCoach(User coach) {
        if (coach.getIsActive() == null || !coach.getIsActive()) {
            throw new BusinessRuleException("Coach not active");
        }

        if (coach.getRole() == null || !"COACH".equalsIgnoreCase(coach.getRole().getCode())) {
            throw new BusinessRuleException("Selected user does not have the role of Coach");
        }
    }

    private void validateLevel(String level) {
        if (level == null || !List.of("BEGINNER", "INTERMEDIATE", "ADVANCED")
                .contains(level.trim().toUpperCase())) {
            throw new BusinessRuleException("Level must be BEGINNER, INTERMEDIATE, or ADVANCED");
        }
    }

    private void validateCapacity(Integer maxCapacity) {
        if (maxCapacity == null || maxCapacity <= 0) {
            throw new BusinessRuleException("Max capacity must be greater than 0");
        }
    }

    private void validateTier(Short requiredTierLevel) {
        if (requiredTierLevel == null || requiredTierLevel < 1) {
            throw new BusinessRuleException("Tier must be greater than or equal to 1");
        }
    }

    private void validateStatus(String status) {
        if (status == null || !List.of("ACTIVE", "INACTIVE", "CANCELED", "COMPLETED")
                .contains(status.trim().toUpperCase())) {
            throw new BusinessRuleException("Status is invalid");
        }
    }

    private SportClassResponse toResponse(SportClass sportClass) {
        SportClassResponse response = new SportClassResponse();
        response.setId(sportClass.getId());
        response.setCategoryId(sportClass.getCategory().getId());
        response.setCategoryName(sportClass.getCategory().getName());
        response.setCoachId(sportClass.getCoach().getId());
        response.setCoachName(sportClass.getCoach().getFullName());
        response.setName(sportClass.getName());
        response.setDescription(sportClass.getDescription());
        response.setLevel(sportClass.getLevel());
        response.setMaxCapacity(sportClass.getMaxCapacity());
        response.setRequiredTierLevel(sportClass.getRequiredTierLevel());
        response.setStatus(sportClass.getStatus());
        response.setCreatedAt(sportClass.getCreatedAt());
        response.setUpdatedAt(sportClass.getUpdatedAt());
        return response;
    }
}