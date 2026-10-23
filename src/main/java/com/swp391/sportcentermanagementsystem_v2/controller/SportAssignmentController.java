package com.swp391.sportcentermanagementsystem_v2.controller;

import com.swp391.sportcentermanagementsystem_v2.dto.requestDTO.SportIdsRequest;
import com.swp391.sportcentermanagementsystem_v2.dto.responseDTO.SportCategoryResponse;
import com.swp391.sportcentermanagementsystem_v2.service.SportAssignmentService;
import jakarta.validation.Valid;
import org.springframework.web.bind.annotation.*;

import java.util.List;

@RestController
@RequestMapping("/api")
public class SportAssignmentController {

    private final SportAssignmentService sportAssignmentService;

    public SportAssignmentController(SportAssignmentService sportAssignmentService) {
        this.sportAssignmentService = sportAssignmentService;
    }

    @PutMapping("/rooms/{id}/sports")
    public List<SportCategoryResponse> replaceRoomSports(@PathVariable Long id,
                                                         @Valid @RequestBody SportIdsRequest request) {
        return sportAssignmentService.replaceRoomSports(id, request.getSportCategoryIds());
    }

    @PutMapping("/coaches/{id}/sports")
    public List<SportCategoryResponse> replaceCoachSports(@PathVariable Long id,
                                                          @Valid @RequestBody SportIdsRequest request) {
        return sportAssignmentService.replaceCoachSports(id, request.getSportCategoryIds());
    }
}
