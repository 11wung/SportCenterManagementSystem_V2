package com.swp391.sportcentermanagementsystem_v2.service;

import com.swp391.sportcentermanagementsystem_v2.dto.requestDTO.RegisterRequest;
import com.swp391.sportcentermanagementsystem_v2.dto.responseDTO.RegisterResponse;
import com.swp391.sportcentermanagementsystem_v2.dto.responseDTO.UserProfileResponse;
import com.swp391.sportcentermanagementsystem_v2.entity.User;
import com.swp391.sportcentermanagementsystem_v2.repository.UserRepository;
import com.swp391.sportcentermanagementsystem_v2.dto.responseDTO.UserSummaryResponse;

import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;

import java.util.ArrayList;
import java.util.List;

@Service
@RequiredArgsConstructor
public class ReceptionistMemberService {

    private final UserRepository userRepository;
    private final UserService userService;

    private final AuthService authService;

    public RegisterResponse createMemberAtCounter(RegisterRequest request) {

        return authService.register(request);
    }

    @Transactional(readOnly = true)
    public List<UserSummaryResponse> searchMembers(String name) {

        List<User> users = userRepository.findByRole_CodeAndFullNameContainingIgnoreCase("MEMBER", name);
        List<UserSummaryResponse> responses = new ArrayList<>();
        for (User user : users) {
            UserSummaryResponse response = new UserSummaryResponse();
            response.setId(user.getId());
            response.setFullName(user.getFullName());
            response.setEmail(user.getEmail());
            response.setPhone(user.getPhone());
            response.setRole(user.getRole().getCode());
            response.setActive(user.getIsActive());

            responses.add(response);

        }
        return responses;
    }

    @Transactional(readOnly = true)
    public UserProfileResponse getMemberById(Long id) {

        User user = userRepository.findById(id)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "User not found"));
        if (!"MEMBER".equals(user.getRole().getCode())) {
            throw new ResponseStatusException(
                    HttpStatus.NOT_FOUND,
                    "Member not found!"
            );
        }
        return userService.getMyProfile(user.getEmail());
    }
}