package com.swp391.sportcentermanagementsystem_v2.controller;

import com.swp391.sportcentermanagementsystem_v2.dto.requestDTO.CreateEmployeeRequest;
import com.swp391.sportcentermanagementsystem_v2.dto.requestDTO.UpdateStaffProfileRequest;
import com.swp391.sportcentermanagementsystem_v2.dto.requestDTO.UpdateUserByManagerRequest;
import com.swp391.sportcentermanagementsystem_v2.dto.responseDTO.CreateEmployeeResponse;
import com.swp391.sportcentermanagementsystem_v2.dto.responseDTO.UserProfileResponse;
import com.swp391.sportcentermanagementsystem_v2.dto.responseDTO.UserSummaryResponse;
import com.swp391.sportcentermanagementsystem_v2.service.UserManagementService;
import com.swp391.sportcentermanagementsystem_v2.service.UserService;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.List;

@RestController
@RequestMapping("/api/manager/accounts")
@RequiredArgsConstructor
public class UserManagementController {

    private final UserManagementService userManagementService;
    private final UserService userService;

    @PostMapping
    public ResponseEntity<CreateEmployeeResponse> createEmployee(@Valid @RequestBody CreateEmployeeRequest createEmployeeRequest) {
        CreateEmployeeResponse response =
                userManagementService.createEmployee(createEmployeeRequest);

        return ResponseEntity.status(HttpStatus.CREATED).body(response);
    }

    @GetMapping
    public ResponseEntity<List<UserSummaryResponse>> getAllUsers() {
        List<UserSummaryResponse> userSummaryResponse = userManagementService.getAllListUser();

        return ResponseEntity.status(HttpStatus.OK).body(userSummaryResponse);
    }

    @GetMapping("/search")
    public ResponseEntity<List<UserSummaryResponse>> searchUsers(
            @RequestParam String userName) {
        List<UserSummaryResponse> userSummaryResponses = userManagementService.getAllListUserByName(userName);
        return ResponseEntity.status(HttpStatus.OK).body(userSummaryResponses);
    }

    @PatchMapping("/{id}/deactivate")
    public ResponseEntity<Void> deactivateUser(
            @PathVariable Long id) {

        userManagementService.deactivateUser(id);

        return ResponseEntity.noContent().build();
    }

    @PatchMapping("/{id}/active")
    public ResponseEntity<Void> activateUser(
            @PathVariable Long id) {

        userManagementService.activateUser(id);

        return ResponseEntity.noContent().build();
    }

    @PatchMapping("/{id}/staff-profile")
    public ResponseEntity<UserProfileResponse> updateStaffProfile(
            @PathVariable Long id,
            @Valid @RequestBody UpdateStaffProfileRequest request) {

        UserProfileResponse response =
                userManagementService.updateStaffProfile(id, request);

        return ResponseEntity.ok(response);
    }

    @GetMapping("/{id}")
    public ResponseEntity<UserProfileResponse> getUserById(
            @PathVariable Long id) {

        UserProfileResponse response =
                userManagementService.getUserById(id);

        return ResponseEntity.ok(response);
    }

    @PatchMapping("/{id}")
    public ResponseEntity<UserProfileResponse> updateUserByManager(
            @PathVariable Long id,
            @Valid @RequestBody UpdateUserByManagerRequest request) {

        UserProfileResponse response =
                userManagementService.updateUserByManager(id, request);

        return ResponseEntity.ok(response);
    }

}
