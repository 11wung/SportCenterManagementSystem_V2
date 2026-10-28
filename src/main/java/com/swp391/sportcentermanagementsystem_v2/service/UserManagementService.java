package com.swp391.sportcentermanagementsystem_v2.service;

import com.swp391.sportcentermanagementsystem_v2.dto.requestDTO.CreateEmployeeRequest;
import com.swp391.sportcentermanagementsystem_v2.dto.requestDTO.UpdateStaffProfileRequest;
import com.swp391.sportcentermanagementsystem_v2.dto.requestDTO.UpdateUserByManagerRequest;
import com.swp391.sportcentermanagementsystem_v2.dto.responseDTO.CreateEmployeeResponse;
import com.swp391.sportcentermanagementsystem_v2.dto.responseDTO.SportCategoryResponse;
import com.swp391.sportcentermanagementsystem_v2.dto.responseDTO.UserProfileResponse;
import com.swp391.sportcentermanagementsystem_v2.dto.responseDTO.UserSummaryResponse;
import com.swp391.sportcentermanagementsystem_v2.entity.*;
import com.swp391.sportcentermanagementsystem_v2.exception.BusinessRuleException;
import com.swp391.sportcentermanagementsystem_v2.repository.*;
import org.springframework.transaction.annotation.Transactional;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;
import org.springframework.web.server.ResponseStatusException;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

@Service
@RequiredArgsConstructor
public class UserManagementService {
    private final UserRepository userRepository;
    private final RoleRepository roleRepository;
    private final CoachProfileRepository coachProfileRepository;
    private final StaffProfileRepository staffProfileRepository;
    private final PasswordEncoder passwordEncoder;
    private final UserService userService;
    private final MemberProfileRepository memberProfileRepository;
    private final MemberSubscriptionRepository subscriptionRepository;
    private final ClassRegistrationRepository classRegistrationRepository;
    private final SportClassRepository sportClassRepository;
    private final PaymentRepository paymentRepository;

    @Transactional
    public CreateEmployeeResponse createEmployee(CreateEmployeeRequest createEmployeeRequest) {
        String roleCode = createEmployeeRequest.getRoleCode();

        if (!"COACH".equals(roleCode)
                && !"RECEPTIONIST".equals(roleCode)) {
            throw new BusinessRuleException(
                    "Only COACH or RECEPTIONIST can be created"
            );
        }
        if (userRepository.existsByEmailIgnoreCase(createEmployeeRequest.getEmail())) {
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
        // Nhân viên do manager tạo, không đi qua bước xác thực email.
        user.setEmailVerifiedAt(java.time.OffsetDateTime.now());

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
        fillCoachDetails(userSummaryResponseList);
        return userSummaryResponseList;
    }

    // Gắn môn đang dạy và hạng cho các coach trong danh sách, chỉ một truy vấn cho cả danh sách
    private void fillCoachDetails(List<UserSummaryResponse> responses) {
        List<Long> coachIds = responses.stream().filter(r -> "COACH".equals(r.getRole())).map(UserSummaryResponse::getId).toList();
        if (coachIds.isEmpty()) {
            return;
        }
        Map<Long, CoachProfile> profiles = coachProfileRepository.findWithSportsByUserIds(coachIds).stream()
                .collect(Collectors.toMap(CoachProfile::getUserId, p -> p));
        for (UserSummaryResponse r : responses) {
            CoachProfile profile = profiles.get(r.getId());
            if (profile != null) {
                r.setSports(profile.getSports().stream().map(SportCategoryResponse::new).toList());
                r.setCoachRank(profile.getCoachRank());
            }
        }
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
                    "This account cannot be activated!"
            );
        }
        if (Boolean.FALSE.equals(user.getIsActive())) {
            user.setIsActive(true);
        }
        userRepository.save(user);
    }

    @Transactional
    public UserProfileResponse updateStaffProfile(
            Long id,
            UpdateStaffProfileRequest request) {

        User user = userRepository.findById(id)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "User not found!"));
        if (!"RECEPTIONIST".equals(user.getRole().getCode())) {
            throw new ResponseStatusException(HttpStatus.FORBIDDEN, "Only Receptionist profiles can be updated!");
        }
        StaffProfile staffProfile = staffProfileRepository.findById(user.getId())
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "Staff profile not found!"));
        if (request.getJobTitle() != null) {
            staffProfile.setJobTitle(request.getJobTitle());
        }
        if (request.getShiftSchedule() != null) {
            staffProfile.setShiftSchedule(request.getShiftSchedule());
        }
        staffProfileRepository.save(staffProfile);
        return userService.getMyProfile(user.getEmail());
    }

    @Transactional(readOnly = true)
    public UserProfileResponse getUserById(Long id) {

        User user = userRepository.findById(id)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "User not found!"));

        return userService.getMyProfile(user.getEmail());

    }

    private void ensureRoleChangeAllowed(User user, String currentRoleCode) {

        Long id = user.getId();

        boolean hasData = switch (currentRoleCode) {
            case "MEMBER" -> subscriptionRepository.existsByUserId(id)
                    || classRegistrationRepository.existsByUserId(id);
            case "COACH" -> sportClassRepository.existsByCoachId(id);
            case "RECEPTIONIST" -> subscriptionRepository.existsByCreatedById(id)
                    || paymentRepository.existsByReceivedById(id);
            default -> false;
        };

        if (hasData) {
            throw new BusinessRuleException(
                    "Cannot change role: user already has " + currentRoleCode + " data!");
        }
    }

    private void ensureProfileForRole(User user, String newRoleCode) {

        switch (newRoleCode) {

            case "MEMBER" -> {

                if (!memberProfileRepository.existsById(user.getId())) {

                    MemberProfile memberProfile = new MemberProfile();

                    memberProfile.setUser(user);

                    memberProfileRepository.save(memberProfile);
                }
            }

            case "COACH" -> {

                if (!coachProfileRepository.existsById(user.getId())) {

                    CoachProfile coachProfile = new CoachProfile();

                    coachProfile.setUser(user);

                    coachProfileRepository.save(coachProfile);
                }
            }

            case "RECEPTIONIST" -> {

                if (!staffProfileRepository.existsById(user.getId())) {

                    StaffProfile staffProfile = new StaffProfile();

                    staffProfile.setUser(user);
                    staffProfile.setJobTitle("Receptionist");

                    staffProfileRepository.save(staffProfile);
                }
            }

            default -> throw new ResponseStatusException(
                    HttpStatus.BAD_REQUEST,
                    "Invalid role!"
            );
        }
    }

    @Transactional
    public UserProfileResponse updateUserByManager(
            Long id,
            UpdateUserByManagerRequest request) {

        // Bước 1: Tìm User bằng ID
        User user = userRepository.findById(id)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "User not found!"));
        // Bước 2: Kiểm tra Role của User được cập nhật
        String role = user.getRole().getCode();
        if (!"MEMBER".equals(role)
                && !"COACH".equals(role)
                && !"RECEPTIONIST".equals(role)) {

            throw new ResponseStatusException(
                    HttpStatus.FORBIDDEN,
                    "This account cannot be updated!"
            );
        }
        // Bước 3: Cập nhật fullName
        if (request.getFullName() != null) {
            user.setFullName(request.getFullName().trim());
        }
        // Bước 4: Cập nhật phone và kiểm tra trùng
        if (request.getPhone() != null) {
            String newPhone = request.getPhone();
            if (!newPhone.equals(user.getPhone())) {
                if (userRepository.existsByPhone(newPhone)) {
                    throw new BusinessRuleException("Phone number already exists!");
                }
                user.setPhone(newPhone);
            }
        }
        // Bước 5: Cập nhật gender, dateOfBirth
        if (request.getGender() != null) {
            user.setGender(request.getGender());
        }
        if (request.getDateOfBirth() != null) {
            user.setDateOfBirth(request.getDateOfBirth());
        }
        // Bước 6: Cập nhật Role
        if (request.getRoleCode() != null) {

            String newRoleCode = request.getRoleCode();

            String currentRoleCode = user.getRole().getCode();

            // Chỉ xử lý khi Role thật sự thay đổi
            if (!currentRoleCode.equals(newRoleCode)) {

                // Role cũ còn dữ liệu nghiệp vụ thì không được đổi.
                ensureRoleChangeAllowed(user, currentRoleCode);

                // Tìm Role mới trong Database
                Role newRole = roleRepository.findByCode(newRoleCode)
                        .orElseThrow(() ->
                                new ResponseStatusException(
                                        HttpStatus.BAD_REQUEST,
                                        "Role not found!"
                                )
                        );

                // Đảm bảo hồ sơ tương ứng với Role mới tồn tại
                ensureProfileForRole(user, newRoleCode);

                // Gán Role mới
                user.setRole(newRole);
            }
        }
        userRepository.save(user);
        return userService.getMyProfile(user.getEmail());
    }
}
