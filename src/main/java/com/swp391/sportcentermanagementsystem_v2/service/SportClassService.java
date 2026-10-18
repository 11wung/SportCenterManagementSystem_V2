
package com.swp391.sportcentermanagementsystem_v2.service;

import com.swp391.sportcentermanagementsystem_v2.dto.requestDTO.CreateSportClassRequest;
import com.swp391.sportcentermanagementsystem_v2.dto.requestDTO.UpdateSportClassRequest;
import com.swp391.sportcentermanagementsystem_v2.dto.responseDTO.SportClassResponse;
import com.swp391.sportcentermanagementsystem_v2.entity.SportCategory;
import com.swp391.sportcentermanagementsystem_v2.entity.SportClass;
import com.swp391.sportcentermanagementsystem_v2.entity.User;
import com.swp391.sportcentermanagementsystem_v2.exception.BusinessRuleException;
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
        SportClass sportClass = sportClassRepository.findById(id)
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

        sportClass.setCategory(category);
        sportClass.setCoach(coach);
        sportClass.setName(request.getName().trim());
        sportClass.setDescription(request.getDescription());
        sportClass.setLevel(request.getLevel().trim().toUpperCase());
        sportClass.setMaxCapacity(request.getMaxCapacity());
        sportClass.setRequiredTierLevel(request.getRequiredTierLevel());
        sportClass.setStatus(request.getStatus().trim().toUpperCase());

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
