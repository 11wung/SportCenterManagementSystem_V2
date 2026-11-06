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
    private final EmailVerificationService emailVerificationService;
    private final EmailDomainChecker emailDomainChecker;
    private final UserDataGuard userDataGuard;

    // Đăng ký online. Giao diện đang so khớp hai câu tiếng Anh dưới đây để đặt lỗi vào đúng ô, nên giữ nguyên.
    @Transactional
    public RegisterResponse register(RegisterRequest registerRequest) {
        return createMember(registerRequest, "Email already exists", "Phone already exists");
    }

    // Lễ tân tạo hộ tại quầy: cùng quy tắc với đăng ký online, thông báo viết tiếng Việt.
    @Transactional
    public RegisterResponse registerAtCounter(RegisterRequest registerRequest) {
        return createMember(registerRequest,
                "Email này đã được sử dụng", "Số điện thoại này đã được sử dụng");
    }

    private RegisterResponse createMember(RegisterRequest registerRequest, String emailTaken, String phoneTaken) {
        emailDomainChecker.requireExistingDomain(registerRequest.getEmail());
        replaceEmptyUnverified(registerRequest.getEmail().trim(), registerRequest.getPhone());
        if (userRepository.existsByEmailIgnoreCase(registerRequest.getEmail())) {
            throw new BusinessRuleException(emailTaken);
        }
        if (userRepository.existsByPhone(registerRequest.getPhone())) {
            throw new BusinessRuleException(phoneTaken);
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

        emailVerificationService.sendCode(userSaved);

        RegisterResponse userResponse = new RegisterResponse();
        userResponse.setId(userSaved.getId());
        userResponse.setFullName(userSaved.getFullName());
        userResponse.setEmail(userSaved.getEmail());
        userResponse.setRole(userSaved.getRole().getCode());
        userResponse.setEmailVerified(false);
        return userResponse;
    }

    // Tài khoản chưa xác thực mà chưa có dữ liệu nào (đơn, thanh toán, ghi danh...) thì không giữ chỗ
    // email và số điện thoại, để người gõ sai được tạo lại. Có dữ liệu thì giữ nguyên, lúc đó bị báo "đã sử dụng".
    // Tài khoản cũ chỉ bị thay sau thời gian chờ kể từ lần gửi mã gần nhất, dù trùng email hay trùng số điện thoại
    // (số điện thoại chưa xác thực, nếu không chờ thì ai cũng xóa được đăng ký đang chờ mã của người khác).
    // shortcut: chỉ chặn theo từng tài khoản bị thay, chưa giới hạn tổng số mail theo địa chỉ IP.
    private void replaceEmptyUnverified(String email, String phone) {
        for (User old : userRepository.findUnverifiedMembersByEmailOrPhone(email, phone)) {
            if (userDataGuard.hasBusinessData(old.getId())) {
                continue;
            }
            long wait = emailVerificationService.secondsUntilNextCode(old);
            if (wait > 0) {
                throw new ResponseStatusException(HttpStatus.TOO_MANY_REQUESTS,
                        "Vui lòng chờ " + wait + " giây rồi thử lại");
            }
            userRepository.delete(old);
        }
        userRepository.flush();
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

        // Email lạ báo cùng câu với sai mật khẩu, chỉ tài khoản có thật bị khóa mới báo "locked"
        if (userLogin != null && !Boolean.TRUE.equals(userLogin.getIsActive())) {
            recordFailedLogin(emailKey);
            throw new InvalidCredentialsException("Account is locked");
        }
        if (!valid) {
            recordFailedLogin(emailKey);
            throw new InvalidCredentialsException("Invalid email or password");
        }
        // Kiểm tra sau mật khẩu để không lộ email nào đã đăng ký.
        if (userLogin.getEmailVerifiedAt() == null) {
            throw new ResponseStatusException(HttpStatus.FORBIDDEN, "Email is not verified");
        }
        failedLogins.remove(emailKey);
        LoginResponse loginResponse = new LoginResponse();
        loginResponse.setJwtToken(jwtService.generateToken(userLogin));
        loginResponse.setEmail(userLogin.getEmail());
        loginResponse.setFullName(userLogin.getFullName());
        loginResponse.setRole(userLogin.getRole().getCode());

        return loginResponse;
    }

    // Đổi mật khẩu khi đã đăng nhập. Nhập sai mật khẩu hiện tại tính vào cùng bộ đếm với đăng nhập,
    // để không dò được mật khẩu qua API này.
    // shortcut: token JWT đã cấp trước đó vẫn dùng được đến khi hết hạn (không có cơ chế thu hồi).
    public void changePassword(String email, String currentPassword, String newPassword) {
        String emailKey = email.trim().toLowerCase(Locale.ROOT);

        if (isLockedOut(emailKey)) {
            throw new ResponseStatusException(
                    HttpStatus.TOO_MANY_REQUESTS,
                    "Too many failed attempts. Try again later");
        }

        User user = userRepository.findByEmailIgnoreCase(email.trim())
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "User not found!"));

        if (!passwordEncoder.matches(currentPassword, user.getPasswordHash())) {
            recordFailedLogin(emailKey);
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Current password is incorrect");
        }
        if (currentPassword.equals(newPassword)) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST,
                    "New password must be different from the current password");
        }

        user.setPasswordHash(passwordEncoder.encode(newPassword));
        userRepository.save(user);
        failedLogins.remove(emailKey);
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
