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
import org.springframework.http.HttpStatus;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;
import org.springframework.web.server.ResponseStatusException;

import java.time.Duration;
import java.time.Instant;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;


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
        if (userRepository.existsByEmailIgnoreCase(registerRequest.getEmail())) {
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
        String emailKey = loginRequest.getEmail().trim().toLowerCase(Locale.ROOT);

        if (isLockedOut(emailKey)) {
            throw new ResponseStatusException(
                    HttpStatus.TOO_MANY_REQUESTS,
                    "Too many failed attempts. Try again later");
        }

        User userLogin = userRepository.findByEmailIgnoreCase(loginRequest.getEmail().trim()).orElse(null);
        boolean valid = userLogin != null
                && passwordEncoder.matches(loginRequest.getPassword(), userLogin.getPasswordHash());

        boolean valid_checkIsActive = userLogin != null
                && Boolean.TRUE.equals(userLogin.getIsActive());

        if (!valid_checkIsActive) {
            recordFailedLogin(emailKey);
            throw new InvalidCredentialsException("Account is locked");
        }
        if (!valid) {
            recordFailedLogin(emailKey);
            throw new InvalidCredentialsException("Invalid email or password");
        }
        failedLogins.remove(emailKey);
        LoginResponse loginResponse = new LoginResponse();
        loginResponse.setJwtToken(jwtService.generateToken(userLogin));
        loginResponse.setEmail(userLogin.getEmail());
        loginResponse.setFullName(userLogin.getFullName());
        loginResponse.setRole(userLogin.getRole().getCode());

        return loginResponse;
    }

    // shortcut: đếm trong bộ nhớ của một bản app (mất khi khởi động lại, mỗi bản
    // app đếm riêng); dùng Redis hoặc bảng DB khi chạy nhiều bản app.
    private static final int MAX_FAILED_LOGINS = 5;
    private static final Duration LOGIN_LOCK_TIME = Duration.ofMinutes(5);
    private static final int MAX_TRACKED_EMAILS = 10_000;

    private final Map<String, FailedLogin> failedLogins = new ConcurrentHashMap<>();

    private static class FailedLogin {
        int count;
        Instant lockedUntil;
    }

    private boolean isLockedOut(String emailKey) {
        FailedLogin failed = failedLogins.get(emailKey);

        if (failed == null || failed.lockedUntil == null) {
            return false;
        }

        if (Instant.now().isBefore(failed.lockedUntil)) {
            return true;
        }

        failedLogins.remove(emailKey);
        return false;
    }

    private void recordFailedLogin(String emailKey) {
        // Chặn map phình mãi khi bị thử bằng rất nhiều email giả.
        if (failedLogins.size() > MAX_TRACKED_EMAILS) {
            failedLogins.clear();
        }

        failedLogins.compute(emailKey, (key, failed) -> {
            FailedLogin updated = failed == null ? new FailedLogin() : failed;
            updated.count++;
            if (updated.count >= MAX_FAILED_LOGINS) {
                updated.lockedUntil = Instant.now().plus(LOGIN_LOCK_TIME);
                updated.count = 0;
            }
            return updated;
        });
    }

}
