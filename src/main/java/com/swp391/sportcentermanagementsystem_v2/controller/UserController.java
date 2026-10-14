package com.swp391.sportcentermanagementsystem_v2.controller;

import com.swp391.sportcentermanagementsystem_v2.dto.requestDTO.UpdateCoachProfileRequest;
import com.swp391.sportcentermanagementsystem_v2.dto.requestDTO.UpdateMemberProfileRequest;
import com.swp391.sportcentermanagementsystem_v2.dto.requestDTO.UpdateMyProfileRequest;
import com.swp391.sportcentermanagementsystem_v2.dto.responseDTO.UserProfileResponse;
import com.swp391.sportcentermanagementsystem_v2.service.UserService;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping("/api/users")
@RequiredArgsConstructor
public class UserController {

    private final UserService userService;

    @GetMapping("/me")
    public ResponseEntity<UserProfileResponse> getUserProfile(Authentication authentication) {
        String email = authentication.getName();

        UserProfileResponse userProfileResponse = userService.getMyProfile(email);
        return new ResponseEntity<>(userProfileResponse, HttpStatus.OK);
    }

    @PatchMapping("/me")
    public ResponseEntity<UserProfileResponse> updateMyprofile(Authentication authentication, @Valid @RequestBody UpdateMyProfileRequest updateMyProfileRequest) {
        String email = authentication.getName();
        UserProfileResponse userProfileResponse = userService.updateMyprofile(email, updateMyProfileRequest);
        return new ResponseEntity<>(userProfileResponse, HttpStatus.OK);
    }

    @PatchMapping("/me/member-profile")
    public ResponseEntity<UserProfileResponse> updateMemberProfile(
            Authentication authentication,
            @Valid @RequestBody UpdateMemberProfileRequest request) {
        String email = authentication.getName();
        UserProfileResponse userProfileResponse = userService.updateMemberProfile(email, request);
        return new ResponseEntity<>(userProfileResponse, HttpStatus.OK);
    }

    @PatchMapping("/me/coach-profile")
    public ResponseEntity<UserProfileResponse> updateCoachProfile(
            Authentication authentication,
            @Valid @RequestBody UpdateCoachProfileRequest request
    ) {
        String email = authentication.getName();
        UserProfileResponse userProfileResponse = userService.updateCoachProfile(email  , request);
        return new ResponseEntity<>(userProfileResponse, HttpStatus.OK);
    }

}
