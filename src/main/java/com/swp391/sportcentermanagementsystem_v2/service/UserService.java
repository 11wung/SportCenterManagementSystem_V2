package com.swp391.sportcentermanagementsystem_v2.service;

import com.swp391.sportcentermanagementsystem_v2.dto.requestDTO.UpdateCoachProfileRequest;
import com.swp391.sportcentermanagementsystem_v2.dto.requestDTO.UpdateMemberProfileRequest;
import com.swp391.sportcentermanagementsystem_v2.dto.requestDTO.UpdateMyProfileRequest;
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

    @Transactional
    public UserProfileResponse updateMyprofile(String email, UpdateMyProfileRequest updateMyProfileRequest) {
        User user = userRepository.findByEmail(email)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "User not found!"));
        if (updateMyProfileRequest.getFullName() != null) {
            user.setFullName(updateMyProfileRequest.getFullName().trim());
        }
        if (updateMyProfileRequest.getGender() != null) {
            user.setGender(updateMyProfileRequest.getGender());
        }
        if (updateMyProfileRequest.getDateOfBirth() != null) {
            user.setDateOfBirth(updateMyProfileRequest.getDateOfBirth());
        }
        if (updateMyProfileRequest.getPhone() != null) {
            String newPhone = updateMyProfileRequest.getPhone();

            if (!newPhone.equals(user.getPhone())) {

                if (userRepository.existsByPhone(newPhone)) {
                    throw new BusinessRuleException(
                            "Phone number already exists!"
                    );
                }

                user.setPhone(newPhone);

            }

        }
        userRepository.save(user);

        return getMyProfile(email);
    }

    @Transactional
    public UserProfileResponse updateMemberProfile(
            String email,
            UpdateMemberProfileRequest request) {

        User user = userRepository.findByEmail(email)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "User not found!"));

        String role = user.getRole().getCode();
        if (!"MEMBER".equals(role)) {
            throw new ResponseStatusException(HttpStatus.FORBIDDEN, "Only MEMBER can update MemberProfile!");
        }

        MemberProfile memberProfile = memberProfileRepository.findById(user.getId())
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "MemberProfile not found!"));

        if (request.getHeightCm() != null) {
            memberProfile.setHeightCm(request.getHeightCm());
        }

        if (request.getWeightKg() != null) {
            memberProfile.setWeightKg(request.getWeightKg());
        }

        if (request.getFitnessGoal() != null) {
            memberProfile.setFitnessGoal(request.getFitnessGoal());
        }
        memberProfileRepository.save(memberProfile);
        return getMyProfile(email);

    }

    @Transactional
    public UserProfileResponse updateCoachProfile(
            String email,
            UpdateCoachProfileRequest request) {

        // Bước 1: Tìm User bằng email
        User user = userRepository.findByEmail(email)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "User not found!"));
        // Bước 2: Kiểm tra Role có phải COACH không
        String role = user.getRole().getCode();
        // Bước 3: Tìm CoachProfile bằng User ID
        if (!"COACH".equals(role)) {
            throw new ResponseStatusException(HttpStatus.FORBIDDEN, "Only COACH can update CoachProfile!"
            );
        }
        // Bước 4: Cập nhật specialty, yearsExperience, certifications
        CoachProfile coachProfile = coachProfileRepository.findById(user.getId())
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "CoachProfile not found!"));
        if (request.getSpecialty() != null) {
            coachProfile.setSpecialty(request.getSpecialty());
        }
        if (request.getYearsExperience() != null) {
            coachProfile.setYearsExperience(request.getYearsExperience());
        }
        if (request.getCertifications() != null) {
            coachProfile.setCertifications(request.getCertifications());
        }
        // Bước 5: Trả về UserProfileResponse
        coachProfileRepository.save(coachProfile);
        return getMyProfile(email);
    }


}
