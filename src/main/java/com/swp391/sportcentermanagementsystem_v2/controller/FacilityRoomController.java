package com.swp391.sportcentermanagementsystem_v2.controller;

import com.swp391.sportcentermanagementsystem_v2.dto.requestDTO.FacilityRoomRequest;
import com.swp391.sportcentermanagementsystem_v2.dto.responseDTO.FacilityRoomResponse;
import com.swp391.sportcentermanagementsystem_v2.service.FacilityRoomService;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.List;

@RestController
@RequestMapping("/api/rooms")
@RequiredArgsConstructor
public class FacilityRoomController {

    private final FacilityRoomService facilityRoomService;

    @PostMapping
    public ResponseEntity<FacilityRoomResponse> createRoom(
            @Valid @RequestBody FacilityRoomRequest request) {

        FacilityRoomResponse response =
                facilityRoomService.create(request);

        return ResponseEntity.status(HttpStatus.CREATED).body(response);
    }

    @GetMapping
    public ResponseEntity<List<FacilityRoomResponse>> getAllRooms() {
        List<FacilityRoomResponse> responses =
                facilityRoomService.getAll();

        return ResponseEntity.status(HttpStatus.OK).body(responses);
    }

    @GetMapping("/{id}")
    public ResponseEntity<FacilityRoomResponse> getRoomById(
            @PathVariable Long id) {

        FacilityRoomResponse response =
                facilityRoomService.getById(id);

        return ResponseEntity.ok(response);
    }

    @PutMapping("/{id}")
    public ResponseEntity<FacilityRoomResponse> updateRoom(
            @PathVariable Long id,
            @Valid @RequestBody FacilityRoomRequest request) {

        FacilityRoomResponse response =
                facilityRoomService.update(id, request);

        return ResponseEntity.ok(response);
    }

    @DeleteMapping("/{id}")
    public ResponseEntity<Void> deleteRoom(
            @PathVariable Long id) {

        facilityRoomService.delete(id);

        return ResponseEntity.noContent().build();
    }
}
