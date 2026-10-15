
package com.swp391.sportcentermanagementsystem_v2.controller;

import com.swp391.sportcentermanagementsystem_v2.dto.requestDTO.CreateMembershipPackageRequest;
import com.swp391.sportcentermanagementsystem_v2.dto.responseDTO.MembershipPackageResponse;
import com.swp391.sportcentermanagementsystem_v2.service.MembershipPackageService;
import com.swp391.sportcentermanagementsystem_v2.dto.requestDTO.UpdateMembershipPackageRequest;

import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping("/api/manager/packages")
@RequiredArgsConstructor
public class ManagerMembershipPackageController {

    private final MembershipPackageService membershipPackageService;

    // Create Package Request
    @PostMapping
    public ResponseEntity<MembershipPackageResponse> createPackage(
            @Valid @RequestBody CreateMembershipPackageRequest request) {

        MembershipPackageResponse response = membershipPackageService.createPackage(request);

        return ResponseEntity.status(HttpStatus.CREATED).body(response);
    }

    // Update Package by ID
    @PatchMapping("/{id}")
    public ResponseEntity<MembershipPackageResponse> updatePackage(
            @PathVariable Long id,
            @Valid @RequestBody UpdateMembershipPackageRequest request) {

        MembershipPackageResponse response = membershipPackageService.updatePackage(id, request);

        return ResponseEntity.ok(response);
    }

    // Update Package Status by ID
    @PatchMapping("/{id}/status")
    public ResponseEntity<MembershipPackageResponse> updatePackageStatus(
            @PathVariable Long id,
            @Valid @RequestBody UpdateMembershipPackageRequest request) {

        MembershipPackageResponse response = membershipPackageService.updatePackageStatus(id, request);

        return ResponseEntity.ok(response);
    }
}
