
package com.swp391.sportcentermanagementsystem_v2.controller;

import com.swp391.sportcentermanagementsystem_v2.dto.responseDTO.MembershipPackageResponse;
import com.swp391.sportcentermanagementsystem_v2.service.MembershipPackageService;

import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

@RestController
@RequestMapping("/api/packages")
@RequiredArgsConstructor
public class MembershipPackageController {

    private final MembershipPackageService membershipPackageService;

    @GetMapping
    public ResponseEntity<List<MembershipPackageResponse>> getActivePackages() {

        List<MembershipPackageResponse> responses = membershipPackageService.getActivePackages();

        return ResponseEntity.ok(responses);
    }
    
     @GetMapping("/{id}")
    public ResponseEntity<MembershipPackageResponse> getPackageById(
            @PathVariable Long id) {

        MembershipPackageResponse response =
                membershipPackageService.getPackageById(id);

        return ResponseEntity.ok(response);
    }
}
