package com.swp391.sportcentermanagementsystem_v2.service;

import com.swp391.sportcentermanagementsystem_v2.dto.requestDTO.LoginRequest;
import com.swp391.sportcentermanagementsystem_v2.dto.responseDTO.LoginResponse;
import com.swp391.sportcentermanagementsystem_v2.exception.BusinessRuleException;
import com.swp391.sportcentermanagementsystem_v2.dto.requestDTO.RegisterRequest;
import com.swp391.sportcentermanagementsystem_v2.dto.responseDTO.RegisterResponse;
import com.swp391.sportcentermanagementsystem_v2.entity.MemberProfile;
import com.swp391.sportcentermanagementsystem_v2.entity.Role;
import com.swp391.sportcentermanagementsystem_v2.entity.User;
import com.swp391.sportcentermanagementsystem_v2.exception.InvalidCredentialsException;
import com.swp391.sportcentermanagementsystem_v2.repository.MemberProfileRepository;
import com.swp391.sportcentermanagementsystem_v2.repository.RoleRepository;
import com.swp391.sportcentermanagementsystem_v2.repository.UserRepository;
import com.swp391.sportcentermanagementsystem_v2.security.JwtService;
import jakarta.transaction.Transactional;
import lombok.RequiredArgsConstructor;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;


@Service
@RequiredArgsConstructor
public class AuthService {
    private final UserRepository userRepository;
    private final RoleRepository roleRepository;
    private final PasswordEncoder passwordEncoder;
    private final MemberProfileRepository memberProfileRepository;
    private final JwtService jwtService;

    @Transactional
    public RegisterResponse register(RegisterRequest registerRequest) {
        if (userRepository.existsByEmail(registerRequest.getEmail())) {
            throw new BusinessRuleException("Email already exists");
        }
        if (userRepository.existsByPhone(registerRequest.getPhone())) {
            throw new BusinessRuleException("Phone already exists");
        }
        Role memberRole = roleRepository.findByCode("MEMBER")
                .orElseThrow(() -> new IllegalStateException("Role not found"));
        User user = new User();
        user.setRole(memberRole);
        user.setFullName(registerRequest.getFullName());
        user.setEmail(registerRequest.getEmail());
        user.setPhone(registerRequest.getPhone());
        user.setIsActive(true);
        user.setPasswordHash(passwordEncoder.encode(registerRequest.getPassword()));

        User userSaved = userRepository.save(user);
        MemberProfile memberProfile = new MemberProfile();
        memberProfile.setUser(userSaved);
        memberProfileRepository.save(memberProfile);

        RegisterResponse userResponse = new RegisterResponse();
        userResponse.setId(userSaved.getId());
        userResponse.setFullName(userSaved.getFullName());
        userResponse.setEmail(userSaved.getEmail());
        userResponse.setRole(userSaved.getRole().getCode());
        return userResponse;
    }

    public LoginResponse login(LoginRequest loginRequest) {
        User userLogin = userRepository.findByEmail(loginRequest.getEmail())
                .orElseThrow(() -> new InvalidCredentialsException("Invalid email or password"));
        if (!Boolean.TRUE.equals(userLogin.getIsActive())) {
            throw new InvalidCredentialsException("Account is not active");
        }
        boolean passwordCorrect = passwordEncoder.matches(loginRequest.getPassword(), userLogin.getPasswordHash());
        if (!passwordCorrect) {
            throw new InvalidCredentialsException("Invalid email or password");
        }
        LoginResponse loginResponse = new LoginResponse();
        loginResponse.setJwtToken(jwtService.generateToken(userLogin));
        loginResponse.setEmail(userLogin.getEmail());
        loginResponse.setFullName(userLogin.getFullName());
        loginResponse.setRole(userLogin.getRole().getCode());
        return loginResponse;
    }

}
