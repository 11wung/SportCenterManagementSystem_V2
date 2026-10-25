package com.swp391.sportcentermanagementsystem_v2.service;

import com.swp391.sportcentermanagementsystem_v2.dto.requestDTO.CreateSportClassRequest;
import com.swp391.sportcentermanagementsystem_v2.dto.requestDTO.UpdateSportClassRequest;
import com.swp391.sportcentermanagementsystem_v2.dto.responseDTO.ClassCancelResponse;
import com.swp391.sportcentermanagementsystem_v2.dto.responseDTO.SportClassResponse;
import com.swp391.sportcentermanagementsystem_v2.entity.ClassRegistration;
import com.swp391.sportcentermanagementsystem_v2.entity.ClassSession;
import com.swp391.sportcentermanagementsystem_v2.entity.CoachProfile;
import com.swp391.sportcentermanagementsystem_v2.entity.Course;
import com.swp391.sportcentermanagementsystem_v2.entity.Payment;
import com.swp391.sportcentermanagementsystem_v2.entity.SportClass;
import com.swp391.sportcentermanagementsystem_v2.entity.User;
import com.swp391.sportcentermanagementsystem_v2.exception.BusinessRuleException;
import com.swp391.sportcentermanagementsystem_v2.repository.ClassRegistrationRepository;
import com.swp391.sportcentermanagementsystem_v2.repository.ClassSessionRepository;
import com.swp391.sportcentermanagementsystem_v2.repository.CoachProfileRepository;
import com.swp391.sportcentermanagementsystem_v2.repository.CourseRepository;
import com.swp391.sportcentermanagementsystem_v2.repository.PaymentRepository;
import com.swp391.sportcentermanagementsystem_v2.repository.SportClassRepository;
import com.swp391.sportcentermanagementsystem_v2.repository.UserRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.OffsetDateTime;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;

@Slf4j
@Service
@RequiredArgsConstructor
public class SportClassService {

    static final ZoneId CENTER_ZONE = ZoneId.of("Asia/Ho_Chi_Minh");

    private final SportClassRepository sportClassRepository;
    private final CourseRepository courseRepository;
    private final UserRepository userRepository;
    private final CoachProfileRepository coachProfileRepository;
    private final ClassSessionRepository classSessionRepository;
    private final ClassRegistrationRepository classRegistrationRepository;
    private final PaymentRepository paymentRepository;
    private final RefundService refundService;
    private final AuditService auditService;
    private final CoachSlotService coachSlotService;
    private final NotificationService notificationService;

    // BR-CLS-01: tạo class ở trạng thái nháp từ một course
    @Transactional
    public SportClassResponse createSportClass(CreateSportClassRequest request) {
        Course course = courseRepository.findDetailById(request.getCourseId())
                .orElseThrow(() -> new ResponseStatusException(
                        HttpStatus.BAD_REQUEST, "Course not found with id: " + request.getCourseId()));
        if (!course.isActive()) {
            throw new BusinessRuleException("Course is no longer offered");
        }

        User coach = findTeachingCoach(request.getCoachId(), course.getCategory().getId());
        validateDates(request.getStartDate(), request.getRegistrationOpensOn());

        SportClass sportClass = new SportClass();
        sportClass.setCourse(course);
        sportClass.setCoach(coach);
        sportClass.setName(request.getName().trim());
        sportClass.setDescription(request.getDescription());
        sportClass.setMaxCapacity(request.getMaxCapacity());
        sportClass.setTuition(request.getTuition() != null ? request.getTuition() : course.getDefaultTuition());
        sportClass.setStartDate(request.getStartDate());
        sportClass.setRegistrationOpensOn(request.getRegistrationOpensOn());

        return toResponse(sportClassRepository.save(sportClass));
    }

    // Nháp sửa tự do; đã mở đăng ký thì bị khóa bớt (BR-CLS-04); từ khai giảng trở đi không sửa nữa
    @Transactional
    public SportClassResponse updateSportClass(Long id, UpdateSportClassRequest request) {
        // Khóa dòng lớp để không có thao tác nào chen vào giữa lúc đang kiểm tra
        SportClass sportClass = sportClassRepository.findByIdForUpdate(id)
                .orElseThrow(() -> new ResponseStatusException(
                        HttpStatus.NOT_FOUND, "Sport class not found with id: " + id));

        switch (sportClass.getStatus()) {
            case "DRAFT" -> updateDraft(sportClass, request);
            case "OPEN" -> updateOpen(sportClass, request);
            default -> throw new BusinessRuleException(
                    "Class can no longer be edited (status: " + sportClass.getStatus() + ")");
        }

        // saveAndFlush để lỗi từ trigger database (vd. sức chứa vượt phòng) nổ ngay trong hàm này
        return toResponse(sportClassRepository.saveAndFlush(sportClass));
    }

    private void updateDraft(SportClass sportClass, UpdateSportClassRequest request) {
        User coach = findTeachingCoach(request.getCoachId(), sportClass.getCourse().getCategory().getId());
        validateDates(request.getStartDate(), request.getRegistrationOpensOn());

        // Đổi coach hoặc ngày khai giảng thì các buổi đã sinh không còn đúng, phải sinh lại
        boolean sessionsOutdated = !sportClass.getCoach().getId().equals(coach.getId())
                || !sportClass.getStartDate().equals(request.getStartDate());
        if (sessionsOutdated) {
            classSessionRepository.deleteByClassId(sportClass.getId());
        }

        sportClass.setCoach(coach);
        sportClass.setName(request.getName().trim());
        sportClass.setDescription(request.getDescription());
        sportClass.setMaxCapacity(request.getMaxCapacity());
        sportClass.setTuition(request.getTuition());
        sportClass.setStartDate(request.getStartDate());
        sportClass.setRegistrationOpensOn(request.getRegistrationOpensOn());
    }

    // Khách có thể đã đăng ký vì coach, học phí hay ngày khai giảng nên các thứ này khóa từ lúc mở đăng ký.
    // Chỉ còn sửa tên, mô tả và sức chứa (không thấp hơn số chỗ đã giữ hoặc đã ghi danh)
    private void updateOpen(SportClass sportClass, UpdateSportClassRequest request) {
        if (!sportClass.getCoach().getId().equals(request.getCoachId())) {
            throw new BusinessRuleException("Coach cannot be changed after registration opens");
        }
        if (sportClass.getTuition().compareTo(request.getTuition()) != 0) {
            throw new BusinessRuleException("Tuition cannot be changed after registration opens");
        }
        if (!sportClass.getStartDate().equals(request.getStartDate())
                || !sportClass.getRegistrationOpensOn().equals(request.getRegistrationOpensOn())) {
            throw new BusinessRuleException("Start date and registration date cannot be changed after registration opens");
        }

        long taken = classRegistrationRepository.countBySportClass_IdAndStatusIn(
                sportClass.getId(), List.of("PENDING_PAYMENT", "REGISTERED"));
        if (request.getMaxCapacity() < taken) {
            throw new BusinessRuleException("Max capacity (" + request.getMaxCapacity()
                    + ") is lower than the number of registrations (" + taken + ")");
        }

        sportClass.setName(request.getName().trim());
        sportClass.setDescription(request.getDescription());
        sportClass.setMaxCapacity(request.getMaxCapacity());
    }

    // BR-CLS-03, BR-CLS-06: DRAFT -> OPEN khi đã sinh đủ số buổi của course
    @Transactional
    public SportClassResponse openRegistration(Long id) {
        SportClass sportClass = sportClassRepository.findByIdForUpdate(id)
                .orElseThrow(() -> new ResponseStatusException(
                        HttpStatus.NOT_FOUND, "Sport class not found with id: " + id));

        if (!"DRAFT".equals(sportClass.getStatus())) {
            throw new BusinessRuleException("Only a DRAFT class can be opened (status: " + sportClass.getStatus() + ")");
        }
        if (sportClass.getStartDate().isBefore(LocalDate.now(CENTER_ZONE))) {
            throw new BusinessRuleException("Start date has passed, update the start date first");
        }

        long sessions = classSessionRepository.countBySportClassId(id);
        int expected = sportClass.getCourse().getTotalSessions();
        if (sessions != expected) {
            throw new BusinessRuleException("Generate the sessions before opening registration (class has "
                    + sessions + " of " + expected + " sessions)");
        }

        sportClass.setStatus("OPEN");
        return toResponse(sportClassRepository.saveAndFlush(sportClass));
    }

    // BR-CAN-02: manager hủy class. Đăng ký chưa trả tiền thì hủy; đã trả thì hoàn đủ nếu chưa khai giảng,
    // giữa chừng hoàn theo số buổi chưa học. Các buổi chưa diễn ra bị hủy (BR-MKP-04), ghi nhật ký (BR-AUD-02)
    @Transactional
    public ClassCancelResponse cancelClass(Long id, String reason, String managerEmail) {
        User manager = userRepository.findByEmail(managerEmail)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "User not found"));
        SportClass sportClass = sportClassRepository.findByIdForUpdate(id)
                .orElseThrow(() -> new ResponseStatusException(
                        HttpStatus.NOT_FOUND, "Sport class not found with id: " + id));

        if (!List.of("DRAFT", "OPEN", "IN_PROGRESS").contains(sportClass.getStatus())) {
            throw new BusinessRuleException("Only a DRAFT, OPEN or IN_PROGRESS class can be canceled (status: "
                    + sportClass.getStatus() + ")");
        }

        LocalDate today = LocalDate.now(CENTER_ZONE);
        boolean started = !today.isBefore(sportClass.getStartDate());
        // shortcut: "chưa học" tính theo ngày vì chưa có gì đánh dấu buổi DONE (thuộc Flow 4), nâng cấp khi có điểm danh
        long totalSessions = classSessionRepository.countBySportClassIdAndStatusIn(id, List.of("SCHEDULED", "DONE"));
        long remainingSessions = classSessionRepository
                .countBySportClassIdAndStatusAndSessionDateGreaterThanEqual(id, "SCHEDULED", today);

        int unpaid = 0;
        int refunded = 0;
        BigDecimal refundTotal = BigDecimal.ZERO;
        for (ClassRegistration listed : classRegistrationRepository
                .findBySportClass_IdAndStatusInOrderById(id, List.of("PENDING_PAYMENT", "REGISTERED", "WAITLISTED"))) {
            // Khóa từng đăng ký rồi đọc lại trạng thái: có thể vừa được thanh toán hoặc hủy
            ClassRegistration registration = classRegistrationRepository.findByIdForUpdate(listed.getId())
                    .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "Registration not found"));

            if ("PENDING_PAYMENT".equals(registration.getStatus())) {
                // Nếu khách đang trả tiền dở, tiền về sau sẽ không kích hoạt được (class đã hủy) và được hoàn tự động
                unpaid++;
            } else if ("REGISTERED".equals(registration.getStatus())) {
                Optional<Payment> paid = paymentRepository
                        .findFirstByOrder_Registration_IdAndStatusOrderByIdDesc(registration.getId(), "SUCCESS");
                if (paid.isPresent()) {
                    // Trừ các khoản đã hoàn từng buổi (vd. buổi bù không đi được) để không hoàn trùng
                    BigDecimal amount = classCancelRefund(paid.get().getAmount(), started, totalSessions, remainingSessions)
                            .min(refundService.refundable(paid.get()));
                    if (amount.signum() > 0) {
                        refundService.createRefund(paid.get(), amount, "Class canceled by the center: " + reason);
                        refunded++;
                        refundTotal = refundTotal.add(amount);
                    }
                }
            } else if (!"WAITLISTED".equals(registration.getStatus())) {
                continue; // đã đổi trạng thái giữa lúc đọc và lúc khóa
            }
            // WAITLISTED: chưa giữ chỗ, chưa có tiền, chỉ cần hủy và báo

            registration.setStatus("CANCELED");
            registration.setCanceledAt(OffsetDateTime.now());
            classRegistrationRepository.save(registration);
            notificationService.send(registration.getUser().getId(), "ANNOUNCEMENT", "Class canceled",
                    "Class " + sportClass.getName() + " was canceled by the center (" + reason + ").");
        }

        int canceledSessions = classSessionRepository.cancelUpcoming(id, today, reason);
        sportClass.setStatus("CANCELED");
        sportClassRepository.saveAndFlush(sportClass);

        auditService.log(manager, "CLASS_CANCELED", "sport_class", id, Map.of(
                "reason", reason,
                "canceledUnpaidRegistrations", unpaid,
                "refundedRegistrations", refunded,
                "refundTotal", refundTotal,
                "canceledSessions", canceledSessions));

        return new ClassCancelResponse(id, "CANCELED", unpaid, refunded, refundTotal, canceledSessions);
    }

    // BR-MKP-05: coach nghỉ việc hẳn thì manager thay coach giữa lớp (ngoại lệ của BR-CLS-04). Coach mới phải dạy đúng môn
    // và rảnh ở mọi buổi chưa diễn ra, nếu trùng buổi nào thì từ chối và liệt kê, không đổi nửa vời
    @Transactional
    public SportClassResponse replaceCoach(Long id, Long newCoachId, String reason, String managerEmail) {
        User manager = userRepository.findByEmail(managerEmail)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "User not found"));
        SportClass sportClass = sportClassRepository.findByIdForUpdate(id)
                .orElseThrow(() -> new ResponseStatusException(
                        HttpStatus.NOT_FOUND, "Sport class not found with id: " + id));
        if (!List.of("OPEN", "IN_PROGRESS").contains(sportClass.getStatus())) {
            throw new BusinessRuleException("Only an OPEN or IN_PROGRESS class can change coach here (status: "
                    + sportClass.getStatus() + ")");
        }
        User oldCoach = sportClass.getCoach();
        if (oldCoach.getId().equals(newCoachId)) {
            throw new BusinessRuleException("The new coach is already the coach of this class");
        }
        User newCoach = findTeachingCoach(newCoachId, sportClass.getCourse().getCategory().getId());

        LocalDateTime now = LocalDateTime.now(CENTER_ZONE);
        List<ClassSession> upcoming = classSessionRepository.findBySportClassIdOrderBySessionNo(id).stream()
                .filter(s -> "SCHEDULED".equals(s.getStatus())
                        && LocalDateTime.of(s.getSessionDate(), s.getEndTime()).isAfter(now))
                .toList();

        List<String> conflicts = new ArrayList<>();
        List<Long> openSlotIds = new ArrayList<>();
        int gap = ClassScheduleService.GAP_MINUTES;
        for (ClassSession s : upcoming) {
            String when = "session " + s.getSessionNo() + " on " + s.getSessionDate();
            if (classSessionRepository.existsCoachConflict(newCoachId, s.getSessionDate(),
                    s.getStartTime().minusMinutes(gap), s.getEndTime().plusMinutes(gap), null, s.getId())) {
                conflicts.add(when + " overlaps another session of the new coach (15-minute gap required)");
                continue;
            }
            String blocker = coachSlotService.findBookedSlotBlocker(
                    newCoachId, s.getSessionDate(), s.getStartTime(), s.getEndTime());
            if (blocker != null) {
                conflicts.add(when + ": " + blocker);
                continue;
            }
            openSlotIds.addAll(coachSlotService.findOpenSlotIds(
                    newCoachId, s.getSessionDate(), s.getStartTime(), s.getEndTime()));
        }
        if (!conflicts.isEmpty()) {
            throw new BusinessRuleException("The new coach is not free for every remaining session: "
                    + String.join("; ", conflicts.stream().limit(10).toList())
                    + (conflicts.size() > 10 ? " (and " + (conflicts.size() - 10) + " more)" : ""));
        }

        // Lớp học được ưu tiên: các suất 1-1 còn trống của coach mới trùng buổi học bị hủy
        coachSlotService.cancelOpenSlotsForClass(openSlotIds.stream().distinct().toList(), newCoachId, manager, id);
        for (ClassSession s : upcoming) {
            s.setCoach(newCoach);
            classSessionRepository.saveAndFlush(s);
        }
        sportClass.setCoach(newCoach);
        sportClassRepository.saveAndFlush(sportClass);

        auditService.log(manager, "CLASS_COACH_REPLACED", "sport_class", id, Map.of(
                "fromCoachId", oldCoach.getId(), "toCoachId", newCoachId, "reason", reason,
                "updatedSessions", upcoming.size()));
        notificationService.sendToClass(id, "ANNOUNCEMENT", "Coach changed",
                "The coach of " + sportClass.getName() + " changed to " + newCoach.getFullName() + " (" + reason + ").");
        return toResponse(sportClass);
    }

    // Trước khai giảng hoàn đủ; giữa chừng hoàn học phí nhân số buổi chưa học chia tổng số buổi, làm tròn đến đồng
    static BigDecimal classCancelRefund(BigDecimal paid, boolean started, long totalSessions, long remainingSessions) {
        if (!started || totalSessions <= 0) {
            return paid;
        }
        long remaining = Math.min(remainingSessions, totalSessions);
        return paid.multiply(BigDecimal.valueOf(remaining))
                .divide(BigDecimal.valueOf(totalSessions), 0, RoundingMode.HALF_UP);
    }

    // BR-CLS-06: chạy hằng ngày, đưa class sang đang học / kết thúc theo ngày
    @Transactional
    public void advanceLifecycle(LocalDate today) {
        // BR-WL-04: đến ngày khai giảng, danh sách chờ đóng
        int closedWaitlist = classRegistrationRepository.closeWaitlistsOfStartedClasses(OffsetDateTime.now(), today);
        if (closedWaitlist > 0) {
            log.info("Closed {} waitlist entr(ies) of started classes", closedWaitlist);
        }
        int started = sportClassRepository.startClasses(today);
        int completed = sportClassRepository.completeFinishedClasses(today);
        log.info("Class lifecycle: {} class(es) started, {} class(es) completed", started, completed);
    }

    @Transactional(readOnly = true)
    public List<SportClassResponse> getAllSportClasses() {
        return sportClassRepository.findAllByOrderByIdDesc()
                .stream()
                .map(this::toResponse)
                .toList();
    }

    @Transactional(readOnly = true)
    public SportClassResponse getSportClassById(Long id) {
        SportClass sportClass = sportClassRepository.findWithDetailsById(id)
                .orElseThrow(() -> new ResponseStatusException(
                        HttpStatus.NOT_FOUND, "Sport class not found with id: " + id));

        return toResponse(sportClass);
    }

    // BR-SCH-04: coach phải có vai trò COACH, đang hoạt động và dạy đúng môn của course
    private User findTeachingCoach(Long coachId, Long categoryId) {
        User coach = userRepository.findById(coachId)
                .orElseThrow(() -> new ResponseStatusException(
                        HttpStatus.BAD_REQUEST, "Coach not found with id: " + coachId));

        if (!Boolean.TRUE.equals(coach.getIsActive())) {
            throw new BusinessRuleException("Coach not active");
        }
        if (coach.getRole() == null || !"COACH".equalsIgnoreCase(coach.getRole().getCode())) {
            throw new BusinessRuleException("Selected user does not have the role of Coach");
        }

        CoachProfile profile = coachProfileRepository.findById(coachId)
                .orElseThrow(() -> new BusinessRuleException("Coach has no profile"));
        boolean teachesSport = profile.getSports().stream().anyMatch(s -> s.getId().equals(categoryId));
        if (!teachesSport) {
            throw new BusinessRuleException("Coach does not teach the sport of this course");
        }
        return coach;
    }

    private void validateDates(LocalDate startDate, LocalDate registrationOpensOn) {
        if (startDate.isBefore(LocalDate.now(CENTER_ZONE))) {
            throw new BusinessRuleException("Start date cannot be in the past");
        }
        if (registrationOpensOn.isAfter(startDate)) {
            throw new BusinessRuleException("Registration must open on or before the start date");
        }
    }

    private SportClassResponse toResponse(SportClass sportClass) {
        SportClassResponse response = new SportClassResponse();
        response.setId(sportClass.getId());
        response.setCourseId(sportClass.getCourse().getId());
        response.setCourseName(sportClass.getCourse().getName());
        response.setLevel(sportClass.getCourse().getLevel());
        response.setTotalSessions(sportClass.getCourse().getTotalSessions());
        response.setCategoryId(sportClass.getCourse().getCategory().getId());
        response.setCategoryName(sportClass.getCourse().getCategory().getName());
        response.setCoachId(sportClass.getCoach().getId());
        response.setCoachName(sportClass.getCoach().getFullName());
        response.setName(sportClass.getName());
        response.setDescription(sportClass.getDescription());
        response.setMaxCapacity(sportClass.getMaxCapacity());
        response.setTuition(sportClass.getTuition());
        response.setStartDate(sportClass.getStartDate());
        response.setRegistrationOpensOn(sportClass.getRegistrationOpensOn());
        response.setStatus(sportClass.getStatus());
        response.setCreatedAt(sportClass.getCreatedAt());
        response.setUpdatedAt(sportClass.getUpdatedAt());
        return response;
    }
}
