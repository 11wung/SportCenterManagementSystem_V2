package com.swp391.sportcentermanagementsystem_v2.controller;

import com.swp391.sportcentermanagementsystem_v2.dto.requestDTO.CreateSportClassRequest;
import com.swp391.sportcentermanagementsystem_v2.dto.requestDTO.UpdateSportClassRequest;
import com.swp391.sportcentermanagementsystem_v2.dto.responseDTO.SportClassResponse;
import com.swp391.sportcentermanagementsystem_v2.service.SportClassService;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.List;

@RestController
@RequestMapping("/api/sport-classes")
@RequiredArgsConstructor
public class SportClassController {

    private final SportClassService sportClassService;

    @PostMapping
    public ResponseEntity<SportClassResponse> createSportClass(
            @Valid @RequestBody CreateSportClassRequest request) {
        return ResponseEntity.status(HttpStatus.CREATED)
                .body(sportClassService.createSportClass(request));
    }

    @PutMapping("/{id}")
    public ResponseEntity<SportClassResponse> updateSportClass(
            @PathVariable Long id,
            @Valid @RequestBody UpdateSportClassRequest request) {
        return ResponseEntity.ok(sportClassService.updateSportClass(id, request));
    }

    @GetMapping
    public ResponseEntity<List<SportClassResponse>> getAllSportClasses() {
        return ResponseEntity.ok(sportClassService.getAllSportClasses());
    }

    @GetMapping("/{id}")
    public ResponseEntity<SportClassResponse> getSportClassById(@PathVariable Long id) {
        return ResponseEntity.ok(sportClassService.getSportClassById(id));
    }
}
