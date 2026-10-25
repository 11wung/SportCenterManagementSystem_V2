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
import java.time.Duration;
import java.time.LocalDateTime;
import java.time.LocalTime;
import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.time.ZoneId;
import java.time.ZonedDateTime;
import java.time.temporal.TemporalAdjusters;
import java.util.Comparator;
import java.util.List;

@Service
@RequiredArgsConstructor
public class ClassRegistrationService {

    private static final String STATUS_REGISTERED = "REGISTERED";
    private static final String STATUS_WAITLISTED = "WAITLISTED";
    private static final String STATUS_CANCELED = "CANCELED";
    private static final String STATUS_LATE_CANCELED = "LATE_CANCELED";
    private static final List<String> ACTIVE_STATUSES = List.of(STATUS_REGISTERED, STATUS_WAITLISTED);

    // Hủy khi buổi học gần nhất bắt đầu trong vòng 2 giờ tới = hủy muộn (bị phạt)
    private static final Duration FREE_CANCEL_WINDOW = Duration.ofHours(2);

    // Giờ trong class_schedules là giờ địa phương của trung tâm, không phụ thuộc múi giờ của server
    private static final ZoneId CENTER_ZONE = ZoneId.of("Asia/Ho_Chi_Minh");

    private final ClassRegistrationRepository classRegistrationRepository;
    private final SportClassRepository sportClassRepository;
    private final UserRepository userRepository;
    private final MemberSubscriptionService subscriptionService;

    // ====================== MEMBER ======================

    // Hội viên tự đăng ký lớp (userId lấy từ token, không nhận từ body)
    // joinWaitlist = true: nếu lớp đã đầy thì vào danh sách chờ
    @Transactional
    public ClassRegistrationResponse registerMyself(String memberEmail, Long classId, boolean joinWaitlist) {
        User member = getUserByEmail(memberEmail);
        return register(member, classId, null, joinWaitlist);
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
            ClassRegistrationRequest request, boolean joinWaitlist) {
        User receptionist = getUserByEmail(receptionistEmail);
        User member = userRepository.findById(request.getUserId())
                .orElseThrow(() -> new BusinessRuleException("No member found!"));
        return register(member, request.getClassId(), receptionist, joinWaitlist);
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

    // ====================== BOOKING ======================

    private ClassRegistrationResponse register(User member, Long classId, User registeredBy,
            boolean joinWaitlist) {
        // Người được đăng ký phải là MEMBER đang hoạt động
        if (member.getRole() == null || !"MEMBER".equals(member.getRole().getCode())) {
            throw new BusinessRuleException("This user is not MEMBER !");
        }
        if (!Boolean.TRUE.equals(member.getIsActive())) {
            throw new BusinessRuleException("This member account is deactivated!");
        }

        // Khóa dòng lớp: các đăng ký cùng lớp chạy lần lượt, nên check trùng và check chỗ bên dưới
        // luôn thấy dữ liệu mới nhất (không bị vượt sức chứa, không bị đăng ký trùng)
        SportClass sportClass = sportClassRepository.findByIdForUpdate(classId)
                .orElseThrow(() -> new BusinessRuleException("Class not found!"));
        if (!"ACTIVE".equals(sportClass.getStatus())) {
            throw new BusinessRuleException("This class is not open for registration!");
        }

        // BR-BKG-01: phải có gói tập ACTIVE và còn hạn
        MemberSubscription subscription = subscriptionService
                .checkActiveSubscriptionForBooking(member.getId())
                .orElseThrow(() -> new BusinessRuleException("Member has no ACTIVE membership!"));

        // Tier của gói phải đạt tier tối thiểu mà lớp yêu cầu
        if (subscription.getTierLevelSnapshot() < sportClass.getRequiredTierLevel()) {
            throw new BusinessRuleException("Your membership tier (" + subscription.getTierLevelSnapshot()
                    + ") is lower than the tier required by this class (" + sportClass.getRequiredTierLevel()
                    + "). Please upgrade your package!");
        }

        // Không đăng ký trùng lớp đang REGISTERED / WAITLISTED
        if (classRegistrationRepository
                .findBySportClass_IdAndUser_IdAndStatusIn(classId, member.getId(), ACTIVE_STATUSES)
                .isPresent()) {
            throw new BusinessRuleException("This member has already registered for this class!");
        }

        // BR-BKG-02: còn chỗ thì REGISTERED. Hết chỗ thì chỉ vào danh sách chờ khi người dùng đồng ý
        long registeredCount = classRegistrationRepository
                .countBySportClass_IdAndStatus(classId, STATUS_REGISTERED);
        String status = STATUS_REGISTERED;
        if (registeredCount >= sportClass.getMaxCapacity()) {
            if (!joinWaitlist) {
                throw new BusinessRuleException(
                        "This class is full! You can join the waitlist by sending joinWaitlist=true.");
            }
            status = STATUS_WAITLISTED;
        }

        ClassRegistration registration = new ClassRegistration();
        registration.setSportClass(sportClass);
        registration.setUser(member);
        registration.setStatus(status);
        registration.setIsPenalized(false);
        registration.setRegisteredBy(registeredBy); // null nếu hội viên tự đăng ký

        return toResponse(classRegistrationRepository.save(registration));
    }

    // ====================== CANCEL ======================

    private ClassRegistrationResponse cancel(ClassRegistration registration) {
        if (!ACTIVE_STATUSES.contains(registration.getStatus())) {
            throw new BusinessRuleException("Only an active registration can be canceled!");
        }

        // Đang trong danh sách chờ thì chưa chiếm chỗ nên hủy tự do, không bị phạt
        if (STATUS_WAITLISTED.equals(registration.getStatus())) {
            return markCanceled(registration, STATUS_CANCELED, false);
        }

        // Còn hơn 2 giờ trước buổi học gần nhất: hủy bình thường
        if (!isLateCancel(registration.getSportClass().getId())) {
            return markCanceled(registration, STATUS_CANCELED, false);
        }

        // Hủy muộn: tự động bị phạt
        return markCanceled(registration, STATUS_LATE_CANCELED, true);
    }

    // Chỗ trống được trả lại tự động vì số chỗ trống = max_capacity - số đăng ký REGISTERED
private ClassRegistrationResponse markCanceled(ClassRegistration registration, String status, boolean penalized) {
    boolean wasRegistered = "REGISTERED".equals(registration.getStatus());

    registration.setStatus(status);
    registration.setCanceledAt(OffsetDateTime.now());
    registration.setIsPenalized(penalized);
    classRegistrationRepository.save(registration);

    if (wasRegistered) {
        classRegistrationRepository
                .findFirstBySportClass_IdAndStatusOrderByRegisteredAtAsc(
                        registration.getSportClass().getId(), "WAITLISTED")
                .ifPresent(waitlisted -> {
                    waitlisted.setStatus("REGISTERED");
                    waitlisted.setRegisteredAt(OffsetDateTime.now());
                    classRegistrationRepository.save(waitlisted);
                });
    }

    return toResponse(registration);
}

    // Lấy buổi học gần nhất sắp diễn ra của lớp, hủy muộn nếu nó bắt đầu trong <= 2 giờ.
    // Lớp chưa có lịch thì không tính được nên coi như không phải hủy muộn.
private boolean isLateCancel(Long classId) {
    LocalDateTime now = LocalDateTime.now(CENTER_ZONE);

    for (Object[] row : sportClassRepository.findSessionSlotsByClassId(classId)) {
        int dayOfWeek = ((Number) row[0]).intValue();
        LocalTime startTime = LocalTime.parse(row[1].toString());

        LocalDateTime start = nextSessionStart(dayOfWeek, startTime, now);

        if (!start.isAfter(now.plus(FREE_CANCEL_WINDOW))) {
            return true;
        }
    }

    return false;
}

private LocalDateTime nextSessionStart(int dayOfWeek, LocalTime startTime, LocalDateTime now) {
    LocalDate date = now.toLocalDate();

    while (date.getDayOfWeek().getValue() != dayOfWeek) {
        date = date.plusDays(1);
    }

    LocalDateTime start = date.atTime(startTime);

    if (start.isBefore(now)) {
        start = start.plusWeeks(1);
    }

    return start;
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