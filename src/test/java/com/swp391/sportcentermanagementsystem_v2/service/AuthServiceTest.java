package com.swp391.sportcentermanagementsystem_v2.service;

import com.swp391.sportcentermanagementsystem_v2.dto.requestDTO.LoginRequest;
import com.swp391.sportcentermanagementsystem_v2.dto.requestDTO.RegisterRequest;
import com.swp391.sportcentermanagementsystem_v2.dto.responseDTO.RegisterResponse;
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

import java.util.List;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doThrow;
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
    @Mock
    EmailVerificationService emailVerificationService;
    @Mock
    EmailDomainChecker emailDomainChecker;
    @Mock
    UserDataGuard userDataGuard;

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
        user.setEmailVerifiedAt(java.time.OffsetDateTime.now());
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
    void unknownEmailGetsTheSameMessageAsWrongPasswordAndLockedAccountSaysLocked() {
        user.setIsActive(false);

        InvalidCredentialsException inactive = assertThrows(InvalidCredentialsException.class,
                () -> authService.login(login(EMAIL, "good")));
        when(userRepository.findByEmailIgnoreCase("nobody@test.com")).thenReturn(Optional.empty());
        InvalidCredentialsException unknown = assertThrows(InvalidCredentialsException.class,
                () -> authService.login(login("nobody@test.com", "good")));

        // Tài khoản có thật bị khóa thì báo rõ để người dùng biết liên hệ trung tâm; email lạ vẫn báo như sai mật khẩu
        assertEquals("Account is locked", inactive.getMessage());
        assertEquals("Invalid email or password", unknown.getMessage());
    }

    @Test
    void unverifiedEmailCannotLoginEvenWithTheRightPassword() {
        user.setEmailVerifiedAt(null);

        ResponseStatusException ex = assertThrows(ResponseStatusException.class,
                () -> authService.login(login(EMAIL, "good")));

        assertEquals(HttpStatus.FORBIDDEN, ex.getStatusCode());
        verify(jwtService, never()).generateToken(any(User.class));
    }

    @Test
    void changePasswordWithTheRightCurrentPasswordStoresTheNewHash() {
        when(passwordEncoder.encode("NewPass#123")).thenReturn("ENCODED");

        authService.changePassword(EMAIL, "good", "NewPass#123");

        assertEquals("ENCODED", user.getPasswordHash());
        verify(userRepository).save(user);
    }

    @Test
    void changePasswordWithAWrongCurrentPasswordKeepsTheOldOne() {
        ResponseStatusException ex = assertThrows(ResponseStatusException.class,
                () -> authService.changePassword(EMAIL, "bad", "NewPass#123"));

        assertEquals(HttpStatus.BAD_REQUEST, ex.getStatusCode());
        assertEquals("hash", user.getPasswordHash());
        verify(userRepository, never()).save(any(User.class));
    }

    @Test
    void changePasswordRejectsTheSamePasswordAgain() {
        ResponseStatusException ex = assertThrows(ResponseStatusException.class,
                () -> authService.changePassword(EMAIL, "good", "good"));

        assertEquals(HttpStatus.BAD_REQUEST, ex.getStatusCode());
        verify(userRepository, never()).save(any(User.class));
    }

    @Test
    void wrongCurrentPasswordsAreCountedSoItCannotBeUsedToGuessThePassword() {
        for (int i = 0; i < 5; i++) {
            assertThrows(ResponseStatusException.class, () -> authService.changePassword(EMAIL, "bad", "NewPass#123"));
        }

        // Từ lần thứ sáu, kể cả mật khẩu đúng cũng bị chặn, và đăng nhập cũng bị khóa cùng bộ đếm.
        ResponseStatusException ex = assertThrows(ResponseStatusException.class,
                () -> authService.changePassword(EMAIL, "good", "NewPass#123"));
        assertEquals(HttpStatus.TOO_MANY_REQUESTS, ex.getStatusCode());
        assertThrows(ResponseStatusException.class, () -> authService.login(login(EMAIL, "good")));
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

    private RegisterRequest registration(String email, String phone) {
        RegisterRequest request = new RegisterRequest();
        request.setFullName("An");
        request.setEmail(email);
        request.setPhone(phone);
        request.setPassword("12345678");
        return request;
    }

    // Tài khoản cũ chưa xác thực, trùng email hoặc số điện thoại với người đang đăng ký
    private User staleAccount(Long id, String email) {
        User stale = new User();
        stale.setId(id);
        stale.setEmail(email);
        when(userRepository.findUnverifiedMembersByEmailOrPhone(any(), any())).thenReturn(List.of(stale));
        return stale;
    }

    private void readyToCreateAccount() {
        Role memberRole = new Role();
        memberRole.setCode("MEMBER");
        when(roleRepository.findByCode("MEMBER")).thenReturn(Optional.of(memberRole));
        when(userRepository.save(any(User.class))).thenAnswer(inv -> {
            User saved = inv.getArgument(0);
            saved.setId(99L);
            return saved;
        });
    }

    @Test
    void registrationWithAnEmailDomainThatDoesNotExistCreatesNothing() {
        doThrow(new ResponseStatusException(HttpStatus.BAD_REQUEST, "Tên miền email '@gmail.con' không tồn tại"))
                .when(emailDomainChecker).requireExistingDomain("an@gmail.con");

        ResponseStatusException ex = assertThrows(ResponseStatusException.class,
                () -> authService.registerAtCounter(registration("an@gmail.con", "0912345678")));

        assertEquals(HttpStatus.BAD_REQUEST, ex.getStatusCode());
        verify(userRepository, never()).save(any(User.class));
        verify(emailVerificationService, never()).sendCode(any(User.class));
    }

    @Test
    void anEmptyUnverifiedAccountIsReplacedSoTheStaffCanRetypeAWrongEmail() {
        readyToCreateAccount();
        User stale = staleAccount(50L, "an@gmial.com");
        when(userDataGuard.hasBusinessData(50L)).thenReturn(false);

        RegisterResponse created = authService.registerAtCounter(registration("an@gmail.com", "0912345678"));

        verify(userRepository).delete(stale);
        verify(emailVerificationService).sendCode(any(User.class));
        assertEquals(false, created.isEmailVerified());
    }

    @Test
    void anUnverifiedAccountThatAlreadyHasDataIsNeverDeleted() {
        staleAccount(50L, "an@gmail.com");
        when(userDataGuard.hasBusinessData(50L)).thenReturn(true);
        when(userRepository.existsByEmailIgnoreCase("an@gmail.com")).thenReturn(true);

        BusinessRuleException ex = assertThrows(BusinessRuleException.class,
                () -> authService.registerAtCounter(registration("an@gmail.com", "0912345678")));

        assertEquals("Email này đã được sử dụng", ex.getMessage());
        verify(userRepository, never()).delete(any(User.class));
    }

    @Test
    void onlineRegistrationKeepsTheEnglishMessagesTheFrontEndMatches() {
        when(userRepository.existsByPhone("0912345678")).thenReturn(true);

        BusinessRuleException ex = assertThrows(BusinessRuleException.class,
                () -> authService.register(registration("an@gmail.com", "0912345678")));

        assertEquals("Phone already exists", ex.getMessage());
    }

    @Test
    void theSameEmailCannotGetANewCodeAgainWithinTheWaitingTime() {
        User stale = staleAccount(50L, "an@gmail.com");
        when(userDataGuard.hasBusinessData(50L)).thenReturn(false);
        when(emailVerificationService.secondsUntilNextCode(stale)).thenReturn(42L);

        ResponseStatusException ex = assertThrows(ResponseStatusException.class,
                () -> authService.register(registration("AN@gmail.com", "0912345678")));

        assertEquals(HttpStatus.TOO_MANY_REQUESTS, ex.getStatusCode());
        assertEquals("Vui lòng chờ 42 giây rồi thử lại", ex.getReason());
        verify(userRepository, never()).delete(any(User.class));
        verify(emailVerificationService, never()).sendCode(any(User.class));
    }

    @Test
    void aDifferentEmailOnTheSamePhoneIsNotMadeToWait() {
        readyToCreateAccount();
        User stale = staleAccount(50L, "an@gmial.com");
        when(userDataGuard.hasBusinessData(50L)).thenReturn(false);
        when(emailVerificationService.secondsUntilNextCode(stale)).thenReturn(42L);

        authService.registerAtCounter(registration("an@gmail.com", "0912345678"));

        verify(userRepository).delete(stale);
    }

    @Test
    void lockoutIgnoresEmailLetterCase() {
        failLogin(5);

        assertThrows(ResponseStatusException.class,
                () -> authService.login(login("MEMBER@TEST.COM", "good")));
    }
}
