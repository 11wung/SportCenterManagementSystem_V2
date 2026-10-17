package com.swp391.sportcentermanagementsystem_v2.service;

import com.swp391.sportcentermanagementsystem_v2.dto.requestDTO.LoginRequest;
import com.swp391.sportcentermanagementsystem_v2.dto.requestDTO.RegisterRequest;
import com.swp391.sportcentermanagementsystem_v2.entity.Role;
import com.swp391.sportcentermanagementsystem_v2.entity.User;
import com.swp391.sportcentermanagementsystem_v2.exception.BusinessRuleException;
import com.swp391.sportcentermanagementsystem_v2.exception.InvalidCredentialsException;
import com.swp391.sportcentermanagementsystem_v2.repository.MemberProfileRepository;
import com.swp391.sportcentermanagementsystem_v2.repository.RoleRepository;
import com.swp391.sportcentermanagementsystem_v2.repository.UserRepository;
import com.swp391.sportcentermanagementsystem_v2.security.JwtService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.mockito.junit.jupiter.MockitoSettings;
import org.mockito.quality.Strictness;
import org.springframework.http.HttpStatus;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.web.server.ResponseStatusException;

import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
class AuthServiceTest {

    private static final String EMAIL = "member@test.com";

    @Mock
    UserRepository userRepository;
    @Mock
    RoleRepository roleRepository;
    @Mock
    PasswordEncoder passwordEncoder;
    @Mock
    MemberProfileRepository memberProfileRepository;
    @Mock
    JwtService jwtService;

    @InjectMocks
    AuthService authService;

    private User user;

    @BeforeEach
    void setUp() {
        Role role = new Role();
        role.setCode("MEMBER");

        user = new User();
        user.setEmail(EMAIL);
        user.setFullName("Member");
        user.setPasswordHash("hash");
        user.setIsActive(true);
        user.setRole(role);

        when(userRepository.findByEmailIgnoreCase(EMAIL)).thenReturn(Optional.of(user));
        when(passwordEncoder.matches("good", "hash")).thenReturn(true);
        when(passwordEncoder.matches("bad", "hash")).thenReturn(false);
        when(jwtService.generateToken(any(User.class))).thenReturn("token");
    }

    private LoginRequest login(String email, String password) {
        LoginRequest request = new LoginRequest();
        request.setEmail(email);
        request.setPassword(password);
        return request;
    }

    private void failLogin(int times) {
        for (int i = 0; i < times; i++) {
            assertThrows(InvalidCredentialsException.class,
                    () -> authService.login(login(EMAIL, "bad")));
        }
    }

    @Test
    void fiveWrongPasswordsLockTheAccountEvenForTheCorrectPassword() {
        failLogin(5);

        ResponseStatusException ex = assertThrows(ResponseStatusException.class,
                () -> authService.login(login(EMAIL, "good")));

        assertEquals(HttpStatus.TOO_MANY_REQUESTS, ex.getStatusCode());
    }

    @Test
    void successfulLoginResetsTheFailureCount() {
        failLogin(4);
        assertNotNull(authService.login(login(EMAIL, "good")));

        // Nếu đếm không được xóa thì lần sai thứ 5 (tổng) sẽ khóa.
        failLogin(4);
        assertNotNull(authService.login(login(EMAIL, "good")));
    }

    @Test
    void inactiveAccountAndUnknownEmailGetTheSameMessageAsWrongPassword() {
        user.setIsActive(false);

        InvalidCredentialsException inactive = assertThrows(InvalidCredentialsException.class,
                () -> authService.login(login(EMAIL, "good")));
        when(userRepository.findByEmailIgnoreCase("nobody@test.com")).thenReturn(Optional.empty());
        InvalidCredentialsException unknown = assertThrows(InvalidCredentialsException.class,
                () -> authService.login(login("nobody@test.com", "good")));

        assertEquals("Invalid email or password", inactive.getMessage());
        assertEquals("Invalid email or password", unknown.getMessage());
    }

    @Test
    void loginWorksWhenEmailIsTypedWithDifferentLetterCase() {
        when(userRepository.findByEmailIgnoreCase("MEMBER@TEST.COM")).thenReturn(Optional.of(user));

        assertNotNull(authService.login(login("MEMBER@TEST.COM", "good")));
    }

    @Test
    void registerRejectsEmailThatDiffersOnlyByLetterCase() {
        // "Member@Test.com" đã có (đăng ký bằng "member@test.com") thì không tạo tài khoản thứ hai.
        when(userRepository.existsByEmailIgnoreCase("Member@Test.com")).thenReturn(true);

        RegisterRequest request = new RegisterRequest();
        request.setFullName("Member Two");
        request.setEmail("Member@Test.com");
        request.setPhone("0933111222");
        request.setPassword("12345678");

        assertThrows(BusinessRuleException.class, () -> authService.register(request));

        verify(userRepository, never()).save(any(User.class));
    }

    @Test
    void lockoutIgnoresEmailLetterCase() {
        failLogin(5);

        assertThrows(ResponseStatusException.class,
                () -> authService.login(login("MEMBER@TEST.COM", "good")));
    }
}
