package com.swp391.sportcentermanagementsystem_v2.service;

import com.swp391.sportcentermanagementsystem_v2.dto.responseDTO.CoachProfileResponse;
import com.swp391.sportcentermanagementsystem_v2.dto.responseDTO.MemberProfileResponse;
import com.swp391.sportcentermanagementsystem_v2.dto.responseDTO.StaffProfileResponse;
import com.swp391.sportcentermanagementsystem_v2.dto.responseDTO.UserProfileResponse;
import com.swp391.sportcentermanagementsystem_v2.entity.CoachProfile;
import com.swp391.sportcentermanagementsystem_v2.entity.MemberProfile;
import com.swp391.sportcentermanagementsystem_v2.entity.StaffProfile;
import com.swp391.sportcentermanagementsystem_v2.entity.User;
import com.swp391.sportcentermanagementsystem_v2.exception.BusinessRuleException;
import com.swp391.sportcentermanagementsystem_v2.repository.CoachProfileRepository;
import com.swp391.sportcentermanagementsystem_v2.repository.MemberProfileRepository;
import com.swp391.sportcentermanagementsystem_v2.repository.StaffProfileRepository;
import com.swp391.sportcentermanagementsystem_v2.repository.UserRepository;
import org.springframework.http.HttpStatus;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;

import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;

@Service
@RequiredArgsConstructor
public class UserService {
    private final UserRepository userRepository;
    private final MemberProfileRepository memberProfileRepository;
    private final CoachProfileRepository coachProfileRepository;
    private final StaffProfileRepository staffProfileRepository;

    @Transactional(readOnly = true)
    public UserProfileResponse getMyProfile(String email) {
        User user = userRepository.findByEmail(email)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "User not found!"));
        UserProfileResponse userProfileResponse = new UserProfileResponse();
        userProfileResponse.setId(user.getId());
        userProfileResponse.setFullName(user.getFullName());
        userProfileResponse.setEmail(user.getEmail());
        userProfileResponse.setPhone(user.getPhone());
        userProfileResponse.setAvatarUrl(user.getAvatarUrl());
        userProfileResponse.setGender(user.getGender());
        userProfileResponse.setActive(user.getIsActive());
        userProfileResponse.setDateOfBirth(user.getDateOfBirth());
        userProfileResponse.setRole(user.getRole().getCode());

        if ("MEMBER".equals(user.getRole().getCode())) {
            MemberProfile memberProfile = memberProfileRepository.findById(user.getId())
                    .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "MemberProfile not found!"));

            MemberProfileResponse memberProfileResponse = new MemberProfileResponse();

            memberProfileResponse.setJoinDate(memberProfile.getJoinDate());
            memberProfileResponse.setHeightCm(memberProfile.getHeightCm());
            memberProfileResponse.setWeightKg(memberProfile.getWeightKg());
            memberProfileResponse.setFitnessGoal(memberProfile.getFitnessGoal());
            memberProfileResponse.setMembershipTier(memberProfile.getMembershipTier());
            userProfileResponse.setMemberProfile(memberProfileResponse);
        }
        if ("COACH".equals(user.getRole().getCode())) {
            CoachProfile coachProfile = coachProfileRepository.findById(user.getId())
                    .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND,
                            "CoachProfile not found!"));

            CoachProfileResponse coachProfileResponse = new CoachProfileResponse();

            coachProfileResponse.setSpecialty(coachProfile.getSpecialty());
            coachProfileResponse.setCertifications(coachProfile.getCertifications());
            coachProfileResponse.setRating(coachProfile.getRating());
            coachProfileResponse.setYearsExperience(coachProfile.getYearsExperience());

            userProfileResponse.setCoachProfile(coachProfileResponse);
        }
        if ("MANAGER".equals(user.getRole().getCode())
                || "RECEPTIONIST".equals(user.getRole().getCode())) {
            StaffProfile staffProfile = staffProfileRepository.findById(user.getId())
                    .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND,
                            "StaffProfile not found!"));

            StaffProfileResponse staffProfileResponse = new StaffProfileResponse();

            staffProfileResponse.setJobTitle(staffProfile.getJobTitle());
            staffProfileResponse.setShiftSchedule(staffProfile.getShiftSchedule());

            userProfileResponse.setStaffProfile(staffProfileResponse);
        }


        return userProfileResponse;
    }

}
