package com.swp391.sportcentermanagementsystem_v2.service;

import com.swp391.sportcentermanagementsystem_v2.dto.requestDTO.CreateEmployeeRequest;
import com.swp391.sportcentermanagementsystem_v2.dto.responseDTO.CreateEmployeeResponse;
import com.swp391.sportcentermanagementsystem_v2.dto.responseDTO.UserProfileResponse;
import com.swp391.sportcentermanagementsystem_v2.dto.responseDTO.UserSummaryResponse;
import com.swp391.sportcentermanagementsystem_v2.entity.CoachProfile;
import com.swp391.sportcentermanagementsystem_v2.entity.Role;
import com.swp391.sportcentermanagementsystem_v2.entity.StaffProfile;
import com.swp391.sportcentermanagementsystem_v2.entity.User;
import com.swp391.sportcentermanagementsystem_v2.exception.BusinessRuleException;
import com.swp391.sportcentermanagementsystem_v2.repository.CoachProfileRepository;
import com.swp391.sportcentermanagementsystem_v2.repository.RoleRepository;
import com.swp391.sportcentermanagementsystem_v2.repository.StaffProfileRepository;
import com.swp391.sportcentermanagementsystem_v2.repository.UserRepository;
import org.springframework.transaction.annotation.Transactional;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;
import org.springframework.web.server.ResponseStatusException;

import java.util.ArrayList;
import java.util.List;

@Service
@RequiredArgsConstructor
public class UserManagementService {
    private final UserRepository userRepository;
    private final RoleRepository roleRepository;
    private final CoachProfileRepository coachProfileRepository;
    private final StaffProfileRepository staffProfileRepository;
    private final PasswordEncoder passwordEncoder;

    @Transactional
    public CreateEmployeeResponse createEmployee(CreateEmployeeRequest createEmployeeRequest) {
        String roleCode = createEmployeeRequest.getRoleCode();

        if (!"COACH".equals(roleCode)
                && !"RECEPTIONIST".equals(roleCode)) {
            throw new BusinessRuleException(
                    "Only COACH or RECEPTIONIST can be created"
            );
        }
        if (userRepository.existsByEmail(createEmployeeRequest.getEmail())) {
            throw new BusinessRuleException("Email already exists!");
        }
        if (userRepository.existsByPhone(createEmployeeRequest.getPhone())) {
            throw new BusinessRuleException("Phone already exists!");
        }
        Role role = roleRepository.findByCode(createEmployeeRequest.getRoleCode())
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "Role not found!"));
        User user = new User();
        user.setFullName(createEmployeeRequest.getFullName());
        user.setEmail(createEmployeeRequest.getEmail());
        user.setPhone(createEmployeeRequest.getPhone());
        user.setRole(role);
        user.setPasswordHash(passwordEncoder.encode(createEmployeeRequest.getPassword()));
        user.setIsActive(true);

        User userSaved = userRepository.save(user);

        if ("COACH".equals(roleCode)) {
            CoachProfile coachProfile = new CoachProfile();
            coachProfile.setUser(userSaved);
            coachProfileRepository.save(coachProfile);
        } else if ("RECEPTIONIST".equals(roleCode)) {
            StaffProfile staffProfile = new StaffProfile();
            staffProfile.setUser(userSaved);
            staffProfileRepository.save(staffProfile);
        }
        CreateEmployeeResponse createEmployeeResponse = new CreateEmployeeResponse();
        createEmployeeResponse.setId(userSaved.getId());
        createEmployeeResponse.setFullName(userSaved.getFullName());
        createEmployeeResponse.setEmail(userSaved.getEmail());
        createEmployeeResponse.setRole(roleCode);

        return createEmployeeResponse;
    }

    @Transactional(readOnly = true)
    public List<UserSummaryResponse> getAllListUser() {
        List<User> users = userRepository.findAll();
        List<UserSummaryResponse> userSummaryResponseList = new ArrayList<>();
        for (User user : users) {
            UserSummaryResponse userSummaryResponse = new UserSummaryResponse();
            userSummaryResponse.setId(user.getId());
            userSummaryResponse.setFullName(user.getFullName());
            userSummaryResponse.setEmail(user.getEmail());
            userSummaryResponse.setPhone(user.getPhone());
            userSummaryResponse.setActive(user.getIsActive());
            userSummaryResponse.setRole(user.getRole().getCode());

            userSummaryResponseList.add(userSummaryResponse);
        }
        return userSummaryResponseList;
    }

    @Transactional(readOnly = true)
    public List<UserSummaryResponse> getAllListUserByName(String userName) {
        List<User> users = userRepository.findByFullNameContainingIgnoreCase(userName);
        List<UserSummaryResponse> userSummaryResponseList = new ArrayList<>();

        for (User user : users) {
            UserSummaryResponse userSummaryResponse = new UserSummaryResponse();
            userSummaryResponse.setId(user.getId());
            userSummaryResponse.setFullName(user.getFullName());
            userSummaryResponse.setEmail(user.getEmail());
            userSummaryResponse.setPhone(user.getPhone());
            userSummaryResponse.setActive(user.getIsActive());
            userSummaryResponse.setRole(user.getRole().getCode());

            userSummaryResponseList.add(userSummaryResponse);

        }
        return userSummaryResponseList;
    }

    @Transactional
    public void deactivateUser(Long id) {
        User user = userRepository.findById(id)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "User not found!"));
        String role = user.getRole().getCode();
        if ("MANAGER".equals(role)) {
            throw new BusinessRuleException(
                    "This account cannot be deactivated!"
            );
        }
        if (Boolean.TRUE.equals(user.getIsActive())) {
            user.setIsActive(false);
        }
        userRepository.save(user);
    }

    @Transactional
    public void activateUser(Long id) {
        User user = userRepository.findById(id)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "User not found!"));
        String role = user.getRole().getCode();
        if ("MANAGER".equals(role)) {
            throw new BusinessRuleException(
                    "This account cannot be deactivated!"
            );
        }
        if (Boolean.FALSE.equals(user.getIsActive())) {
            user.setIsActive(true);
        }
        userRepository.save(user);
    }
}
