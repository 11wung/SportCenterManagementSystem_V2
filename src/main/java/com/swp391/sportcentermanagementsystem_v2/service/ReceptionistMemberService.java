package com.swp391.sportcentermanagementsystem_v2.service;

import com.swp391.sportcentermanagementsystem_v2.dto.requestDTO.RegisterRequest;
import com.swp391.sportcentermanagementsystem_v2.dto.responseDTO.RegisterResponse;
import com.swp391.sportcentermanagementsystem_v2.dto.responseDTO.UserProfileResponse;
import com.swp391.sportcentermanagementsystem_v2.entity.User;
import com.swp391.sportcentermanagementsystem_v2.exception.BusinessRuleException;
import com.swp391.sportcentermanagementsystem_v2.repository.UserRepository;
import com.swp391.sportcentermanagementsystem_v2.dto.responseDTO.UserSummaryResponse;

import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

@Service
@RequiredArgsConstructor
public class ReceptionistMemberService {

    private final UserRepository userRepository;
    private final UserService userService;

    private final AuthService authService;
    private final EmailVerificationService emailVerificationService;
    private final AuditService auditService;

    public RegisterResponse createMemberAtCounter(RegisterRequest request) {

        return authService.registerAtCounter(request);
    }

    @Transactional(readOnly = true)
    public List<UserSummaryResponse> searchMembers(String name) {

        List<User> users = userRepository.findByRole_CodeAndFullNameContainingIgnoreCase("MEMBER", name);
        List<UserSummaryResponse> responses = new ArrayList<>();
        for (User user : users) {
            responses.add(toSummary(user));
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

    // Lễ tân nhập mã hội viên đọc từ hộp thư ngay tại quầy.
    // noRollbackFor: nhập sai quá nhiều lần thì mã bị hủy, việc hủy đó phải được lưu dù trả lỗi.
    @Transactional(noRollbackFor = ResponseStatusException.class)
    public UserSummaryResponse verifyMemberEmail(Long memberId, String code, String staffEmail) {

        User staff = userRepository.findByEmail(staffEmail)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "User not found"));
        User member = requireMember(memberId);

        try {
            emailVerificationService.verify(member.getEmail(), code);
        } catch (ResponseStatusException ex) {
            if (ex.getStatusCode() == HttpStatus.BAD_REQUEST) {
                throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Mã xác thực sai hoặc đã hết hạn");
            }
            throw ex;
        }

        auditService.log(staff, "MEMBER_EMAIL_VERIFIED_BY_STAFF", "user", memberId,
                Map.of("email", member.getEmail()));
        return toSummary(requireMember(memberId));
    }

    // Gửi lại mã khi hội viên chưa nhận được mail, không quá một lần mỗi phút
    @Transactional
    public void resendMemberCode(Long memberId) {

        User member = requireMember(memberId);

        if (member.getEmailVerifiedAt() != null) {
            throw new BusinessRuleException("Email của hội viên này đã được xác thực");
        }
        long wait = emailVerificationService.secondsUntilNextCode(member);
        if (wait > 0) {
            throw new ResponseStatusException(HttpStatus.TOO_MANY_REQUESTS,
                    "Vui lòng chờ " + wait + " giây rồi gửi lại mã");
        }
        emailVerificationService.sendCode(member);
    }

    private User requireMember(Long id) {
        User user = userRepository.findById(id)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "Member not found!"));
        if (!"MEMBER".equals(user.getRole().getCode())) {
            throw new ResponseStatusException(HttpStatus.NOT_FOUND, "Member not found!");
        }
        return user;
    }

    private UserSummaryResponse toSummary(User user) {
        UserSummaryResponse response = new UserSummaryResponse();
        response.setId(user.getId());
        response.setFullName(user.getFullName());
        response.setEmail(user.getEmail());
        response.setPhone(user.getPhone());
        response.setRole(user.getRole().getCode());
        response.setActive(user.getIsActive());
        response.setEmailVerified(user.getEmailVerifiedAt() != null);
        return response;
    }
}
