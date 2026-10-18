package com.swp391.sportcentermanagementsystem_v2.service;

import com.swp391.sportcentermanagementsystem_v2.dto.requestDTO.ClassRegistrationRequest;
import com.swp391.sportcentermanagementsystem_v2.dto.responseDTO.ClassRegistrationResponse;
import com.swp391.sportcentermanagementsystem_v2.entity.ClassRegistration;
import com.swp391.sportcentermanagementsystem_v2.entity.MemberSubscription;
import com.swp391.sportcentermanagementsystem_v2.entity.SportClass;
import com.swp391.sportcentermanagementsystem_v2.entity.User;
import com.swp391.sportcentermanagementsystem_v2.exception.BusinessRuleException;
import com.swp391.sportcentermanagementsystem_v2.repository.ClassRegistrationRepository;
import com.swp391.sportcentermanagementsystem_v2.repository.SportClassRepository;
import com.swp391.sportcentermanagementsystem_v2.repository.UserRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.DayOfWeek;
import java.time.OffsetDateTime;
import java.time.ZonedDateTime;
import java.time.temporal.TemporalAdjusters;
import java.util.List;

@Service
@RequiredArgsConstructor
public class ClassRegistrationService {

    private static final String STATUS_REGISTERED = "REGISTERED";
    private static final String STATUS_WAITLISTED = "WAITLISTED";
    private static final String STATUS_CANCELED = "CANCELED";
    private static final List<String> ACTIVE_STATUSES = List.of(STATUS_REGISTERED, STATUS_WAITLISTED);

    private final ClassRegistrationRepository classRegistrationRepository;
    private final SportClassRepository sportClassRepository;
    private final UserRepository userRepository;
    private final MemberSubscriptionService subscriptionService;

    // ====================== MEMBER ======================

    // Hội viên tự đăng ký lớp (userId lấy từ token, không nhận từ body)
    @Transactional
    public ClassRegistrationResponse registerMyself(String memberEmail, Long classId) {
        User member = getUserByEmail(memberEmail);
        return register(member, classId, null);
    }

    // Hội viên tự hủy đăng ký của chính mình
    @Transactional
    public ClassRegistrationResponse cancelMyRegistration(String memberEmail, Long registrationId) {
        User member = getUserByEmail(memberEmail);
        ClassRegistration registration = getRegistrationOrThrow(registrationId);

        if (!registration.getUser().getId().equals(member.getId())) {
            throw new BusinessRuleException("You can only cancel your own registrations!");
        }
        return cancel(registration);
    }

    // Hội viên xem lịch sử đăng ký lớp của mình
    @Transactional(readOnly = true)
    public List<ClassRegistrationResponse> getMyRegistrations(String memberEmail) {
        User member = getUserByEmail(memberEmail);
        return classRegistrationRepository.findByUser_IdOrderByRegisteredAtDesc(member.getId())
                .stream()
                .map(this::toResponse)
                .toList();
    }

    // ====================== RECEPTIONIST ======================

    // Lễ tân đăng ký lớp hộ hội viên tại quầy
    @Transactional
    public ClassRegistrationResponse registerMemberByReceptionist(String receptionistEmail,
            ClassRegistrationRequest request) {
        User receptionist = getUserByEmail(receptionistEmail);
        User member = userRepository.findById(request.getUserId())
                .orElseThrow(() -> new BusinessRuleException("No member found!"));
        return register(member, request.getClassId(), receptionist);
    }

    // Lễ tân hủy đăng ký hộ hội viên
    @Transactional
    public ClassRegistrationResponse cancelRegistrationByReceptionist(Long registrationId) {
        return cancel(getRegistrationOrThrow(registrationId));
    }

    // Lễ tân xem lịch sử đăng ký lớp của 1 hội viên bất kỳ
    @Transactional(readOnly = true)
    public List<ClassRegistrationResponse> getRegistrationsByMemberId(Long memberId) {
        if (!userRepository.existsById(memberId)) {
            throw new BusinessRuleException("No member found with ID: " + memberId);
        }
        return classRegistrationRepository.findByUser_IdOrderByRegisteredAtDesc(memberId)
                .stream()
                .map(this::toResponse)
                .toList();
    }

    // ====================== CORE LOGIC ======================

    private ClassRegistrationResponse register(User member, Long classId, User registeredBy) {
        // 1. Người được đăng ký phải là MEMBER đang hoạt động
        if (member.getRole() == null || !"MEMBER".equals(member.getRole().getCode())) {
            throw new BusinessRuleException("This user is not MEMBER !");
        }
        if (!Boolean.TRUE.equals(member.getIsActive())) {
            throw new BusinessRuleException("This member account is deactivated!");
        }

        // 2. Lớp phải tồn tại và đang mở
        SportClass sportClass = sportClassRepository.findById(classId)
                .orElseThrow(() -> new BusinessRuleException("Class not found!"));
        if (!"ACTIVE".equals(sportClass.getStatus())) {
            throw new BusinessRuleException("This class is not open for registration!");
        }

        // 3. BR-BKG-01: phải có gói tập ACTIVE và còn hạn
        MemberSubscription subscription = subscriptionService
                .checkActiveSubscriptionForBooking(member.getId())
                .orElseThrow(() -> new BusinessRuleException("Member has no ACTIVE membership!"));

        // 4. Tier của gói tập phải đạt tier tối thiểu mà lớp yêu cầu
        if (subscription.getTierLevelSnapshot() < sportClass.getRequiredTierLevel()) {
            throw new BusinessRuleException("Your membership tier (" + subscription.getTierLevelSnapshot()
                    + ") is lower than the tier required by this class (" + sportClass.getRequiredTierLevel()
                    + "). Please upgrade your package!");
        }

        // 5. Không đăng ký trùng lớp đang REGISTERED / WAITLISTED
        if (classRegistrationRepository
                .findBySportClass_IdAndUser_IdAndStatusIn(classId, member.getId(), ACTIVE_STATUSES)
                .isPresent()) {
            throw new BusinessRuleException("This member has already registered for this class!");
        }

        // 6. BR-BKG-03: giới hạn số lớp mỗi tuần (tuần bắt đầu từ thứ Hai)
        ZonedDateTime now = ZonedDateTime.now();
        OffsetDateTime weekStart = now.toLocalDate()
                .with(TemporalAdjusters.previousOrSame(DayOfWeek.MONDAY))
                .atStartOfDay(now.getZone())
                .toOffsetDateTime();
        OffsetDateTime weekEnd = weekStart.plusWeeks(1).minusNanos(1);

        long usedThisWeek = classRegistrationRepository.countByUser_IdAndStatusAndRegisteredAtBetween(
                member.getId(), STATUS_REGISTERED, weekStart, weekEnd);
        if (usedThisWeek >= subscription.getMaxClassesPerWeekSnapshot()) {
            throw new BusinessRuleException("Weekly class limit ("
                    + subscription.getMaxClassesPerWeekSnapshot() + ") reached!");
        }

        // 7. BR-BKG-02: lớp còn chỗ
        long registeredCount = classRegistrationRepository
                .countBySportClass_IdAndStatus(classId, STATUS_REGISTERED);
        if (registeredCount >= sportClass.getMaxCapacity()) {
            throw new BusinessRuleException("This class is full!");
        }

        // 8. Lưu đăng ký
        ClassRegistration registration = new ClassRegistration();
        registration.setSportClass(sportClass);
        registration.setUser(member);
        registration.setStatus(STATUS_REGISTERED);
        registration.setIsPenalized(false);
        registration.setRegisteredBy(registeredBy); // null nếu hội viên tự đăng ký

        return toResponse(classRegistrationRepository.save(registration));
    }

    private ClassRegistrationResponse cancel(ClassRegistration registration) {
        if (!ACTIVE_STATUSES.contains(registration.getStatus())) {
            throw new BusinessRuleException("Only an active registration can be canceled!");
        }
        registration.setStatus(STATUS_CANCELED);
        registration.setCanceledAt(OffsetDateTime.now());
        return toResponse(classRegistrationRepository.save(registration));
    }

    // ====================== HELPERS ======================

    private User getUserByEmail(String email) {
        return userRepository.findByEmail(email)
                .orElseThrow(() -> new BusinessRuleException("User not found!"));
    }

    private ClassRegistration getRegistrationOrThrow(Long registrationId) {
        return classRegistrationRepository.findById(registrationId)
                .orElseThrow(() -> new BusinessRuleException("Registration not found!"));
    }

    private ClassRegistrationResponse toResponse(ClassRegistration r) {
        return new ClassRegistrationResponse(r.getId(), r.getSportClass().getId(), r.getSportClass().getName(),
                r.getUser().getId(), r.getUser().getFullName(), r.getRegisteredAt(), r.getStatus(),
                r.getCanceledAt(), r.getIsPenalized());
    }
}