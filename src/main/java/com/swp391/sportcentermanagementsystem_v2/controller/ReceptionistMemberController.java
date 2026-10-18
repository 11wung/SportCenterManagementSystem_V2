package com.swp391.sportcentermanagementsystem_v2.controller;

import com.swp391.sportcentermanagementsystem_v2.dto.requestDTO.RegisterRequest;
import com.swp391.sportcentermanagementsystem_v2.dto.responseDTO.RegisterResponse;
import com.swp391.sportcentermanagementsystem_v2.dto.responseDTO.UserProfileResponse;
import com.swp391.sportcentermanagementsystem_v2.dto.responseDTO.UserSummaryResponse;
import com.swp391.sportcentermanagementsystem_v2.service.ReceptionistMemberService;

import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.List;

@RestController
@RequestMapping("/api/receptionist/members")
@RequiredArgsConstructor
public class ReceptionistMemberController {

    private final ReceptionistMemberService receptionistMemberService;

    @GetMapping
    public ResponseEntity<List<UserSummaryResponse>> searchMembers(
            @RequestParam(defaultValue = "") String name) {

        List<UserSummaryResponse> responses =
                receptionistMemberService.searchMembers(name.trim());

        return ResponseEntity.ok(responses);
    }

    @GetMapping("/{id}")
    public ResponseEntity<UserProfileResponse> getMemberById(
            @PathVariable Long id) {

        UserProfileResponse response =
                receptionistMemberService.getMemberById(id);

        return ResponseEntity.ok(response);
    }

    @PostMapping
    public ResponseEntity<RegisterResponse> createMemberAtCounter(
            @Valid @RequestBody RegisterRequest request) {

        RegisterResponse response =
                receptionistMemberService.createMemberAtCounter(request);

        return ResponseEntity.status(HttpStatus.CREATED).body(response);
    }
}