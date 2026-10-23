package com.swp391.sportcentermanagementsystem_v2.service;

import com.swp391.sportcentermanagementsystem_v2.dto.responseDTO.SportCategoryResponse;
import com.swp391.sportcentermanagementsystem_v2.entity.CoachProfile;
import com.swp391.sportcentermanagementsystem_v2.entity.FacilityRoom;
import com.swp391.sportcentermanagementsystem_v2.entity.SportCategory;
import com.swp391.sportcentermanagementsystem_v2.repository.CoachProfileRepository;
import com.swp391.sportcentermanagementsystem_v2.repository.FacilityRoomRepository;
import com.swp391.sportcentermanagementsystem_v2.repository.SportCategoryRepository;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;

import java.util.Comparator;
import java.util.List;
import java.util.Set;

// BR-SCH-04: phòng và coach chỉ được xếp vào class thuộc đúng môn đã gán
@Service
public class SportAssignmentService {

    private final FacilityRoomRepository facilityRoomRepository;
    private final CoachProfileRepository coachProfileRepository;
    private final SportCategoryRepository sportCategoryRepository;

    public SportAssignmentService(FacilityRoomRepository facilityRoomRepository,
                                  CoachProfileRepository coachProfileRepository,
                                  SportCategoryRepository sportCategoryRepository) {
        this.facilityRoomRepository = facilityRoomRepository;
        this.coachProfileRepository = coachProfileRepository;
        this.sportCategoryRepository = sportCategoryRepository;
    }

    @Transactional
    public List<SportCategoryResponse> replaceRoomSports(Long roomId, Set<Long> sportCategoryIds) {
        FacilityRoom room = facilityRoomRepository.findById(roomId)
                .orElseThrow(() -> new ResponseStatusException(
                        HttpStatus.NOT_FOUND, "Room does not exist with ID: " + roomId));

        room.getSports().clear();
        room.getSports().addAll(loadCategories(sportCategoryIds));

        return toResponses(room.getSports());
    }

    @Transactional
    public List<SportCategoryResponse> replaceCoachSports(Long coachId, Set<Long> sportCategoryIds) {
        CoachProfile coach = coachProfileRepository.findById(coachId)
                .orElseThrow(() -> new ResponseStatusException(
                        HttpStatus.NOT_FOUND, "Coach does not exist with ID: " + coachId));

        coach.getSports().clear();
        coach.getSports().addAll(loadCategories(sportCategoryIds));

        return toResponses(coach.getSports());
    }

    private List<SportCategory> loadCategories(Set<Long> ids) {
        List<SportCategory> categories = sportCategoryRepository.findAllById(ids);
        if (categories.size() != ids.size()) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Some sport categories do not exist");
        }
        return categories;
    }

    private List<SportCategoryResponse> toResponses(Set<SportCategory> categories) {
        return categories.stream()
                .sorted(Comparator.comparing(SportCategory::getId))
                .map(SportCategoryResponse::new)
                .toList();
    }
}
