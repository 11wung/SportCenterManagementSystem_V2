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
import java.time.temporal.ChronoUnit;
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
                        HttpStatus.BAD_REQUEST, "Không tìm thấy khóa học có mã " + request.getCourseId()));
        if (!course.isActive()) {
            throw new BusinessRuleException("Khóa học này đã ngừng mở");
        }

        User coach = findTeachingCoach(request.getCoachId(), course.getCategory().getId());
        validateDates(request.getStartDate(), request.getRegistrationOpensOn(), null);

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
                        HttpStatus.NOT_FOUND, "Không tìm thấy lớp có mã " + id));

        switch (sportClass.getStatus()) {
            case "DRAFT" -> updateDraft(sportClass, request);
            case "OPEN" -> updateOpen(sportClass, request);
            default -> throw new BusinessRuleException(
                    "Lớp không còn sửa được (trạng thái hiện tại: " + sportClass.getStatus() + ")");
        }

        // saveAndFlush để lỗi từ trigger database (vd. sức chứa vượt phòng) nổ ngay trong hàm này
        return toResponse(sportClassRepository.saveAndFlush(sportClass));
    }

    private void updateDraft(SportClass sportClass, UpdateSportClassRequest request) {
        User coach = findTeachingCoach(request.getCoachId(), sportClass.getCourse().getCategory().getId());
        validateDates(request.getStartDate(), request.getRegistrationOpensOn(), sportClass.getRegistrationOpensOn());

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
            throw new BusinessRuleException("Không đổi được coach sau khi đã mở đăng ký");
        }
        if (sportClass.getTuition().compareTo(request.getTuition()) != 0) {
            throw new BusinessRuleException("Không đổi được học phí sau khi đã mở đăng ký");
        }
        if (!sportClass.getStartDate().equals(request.getStartDate())) {
            throw new BusinessRuleException("Không đổi được ngày khai giảng sau khi đã mở đăng ký");
        }

        long taken = classRegistrationRepository.countBySportClass_IdAndStatusIn(
                sportClass.getId(), List.of("PENDING_PAYMENT", "REGISTERED"));
        if (request.getMaxCapacity() < taken) {
            throw new BusinessRuleException("Sức chứa tối đa (" + request.getMaxCapacity()
                    + ") nhỏ hơn số đăng ký hiện có (" + taken + ")");
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
                        HttpStatus.NOT_FOUND, "Không tìm thấy lớp có mã " + id));

        if (!"DRAFT".equals(sportClass.getStatus())) {
            throw new BusinessRuleException("Chỉ mở đăng ký được lớp nháp (trạng thái hiện tại: " + sportClass.getStatus() + ")");
        }
        String problem = problemOpening(sportClass);
        if (problem != null) {
            throw new BusinessRuleException(problem);
        }

        sportClass.setStatus("OPEN");
        return toResponse(sportClassRepository.saveAndFlush(sportClass));
    }

    // Lý do lớp nháp chưa mở đăng ký được, null nếu mở được
    private String problemOpening(SportClass sportClass) {
        if (sportClass.getStartDate().isBefore(LocalDate.now(CENTER_ZONE))) {
            return "Đã qua ngày khai giảng, hãy cập nhật ngày khai giảng trước";
        }
        long sessions = classSessionRepository.countBySportClassId(sportClass.getId());
        int expected = sportClass.getCourse().getTotalSessions();
        if (sessions != expected) {
            return "Hãy sinh đủ buổi học trước khi mở đăng ký (lớp đang có " + sessions + " trên " + expected
                    + " buổi)";
        }
        return null;
    }

    // BR-CAN-02: manager hủy class. Đăng ký chưa trả tiền thì hủy; đã trả thì hoàn đủ nếu chưa khai giảng,
    // giữa chừng hoàn theo số buổi chưa học. Các buổi chưa diễn ra bị hủy (BR-MKP-04), ghi nhật ký (BR-AUD-02)
    @Transactional
    public ClassCancelResponse cancelClass(Long id, String reason, String managerEmail) {
        User manager = userRepository.findByEmail(managerEmail)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "Không tìm thấy người dùng"));
        SportClass sportClass = sportClassRepository.findByIdForUpdate(id)
                .orElseThrow(() -> new ResponseStatusException(
                        HttpStatus.NOT_FOUND, "Không tìm thấy lớp có mã " + id));

        if (!List.of("DRAFT", "OPEN", "IN_PROGRESS").contains(sportClass.getStatus())) {
            throw new BusinessRuleException("Chỉ hủy được lớp nháp, đang mở hoặc đang học (trạng thái hiện tại: "
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
                    .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "Không tìm thấy đăng ký"));

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
            notificationService.sendWithEmail(registration.getUser().getId(), "ANNOUNCEMENT", "Lớp đã bị hủy",
                    "Class " + sportClass.getName() + " đã bị trung tâm hủy (" + reason + ").",
                    "Sport Center - Lớp " + sportClass.getName() + " đã bị hủy",
                    "Trung tâm đã hủy lớp " + sportClass.getName() + ". Lý do: " + reason
                            + ". Nếu bạn đã thanh toán, khoản hoàn tiền sẽ được xử lý theo quy định.");
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
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "Không tìm thấy người dùng"));
        SportClass sportClass = sportClassRepository.findByIdForUpdate(id)
                .orElseThrow(() -> new ResponseStatusException(
                        HttpStatus.NOT_FOUND, "Không tìm thấy lớp có mã " + id));
        if (!List.of("OPEN", "IN_PROGRESS").contains(sportClass.getStatus())) {
            throw new BusinessRuleException("Chỉ đổi coach được ở lớp đang mở hoặc đang học (trạng thái hiện tại: "
                    + sportClass.getStatus() + ")");
        }
        User oldCoach = sportClass.getCoach();
        if (oldCoach.getId().equals(newCoachId)) {
            throw new BusinessRuleException("Coach mới đã là coach của lớp này");
        }
        User newCoach = findTeachingCoach(newCoachId, sportClass.getCourse().getCategory().getId());

        LocalDateTime now = LocalDateTime.now(CENTER_ZONE);
        List<ClassSession> upcoming = classSessionRepository.findBySportClassIdOrderBySessionNo(id).stream()
                .filter(s -> "SCHEDULED".equals(s.getStatus())
                        && LocalDateTime.of(s.getSessionDate(), s.getEndTime()).isAfter(now))
                // Buổi manager đã gán riêng cho coach khác giữ nguyên, chỉ đổi những buổi đang là coach cũ
                .filter(s -> s.getCoach().getId().equals(oldCoach.getId()))
                .toList();

        List<String> conflicts = new ArrayList<>();
        int gap = ClassScheduleService.GAP_MINUTES;
        for (ClassSession s : upcoming) {
            String when = "session " + s.getSessionNo() + " ngày " + s.getSessionDate();
            if (classSessionRepository.existsCoachConflict(newCoachId, s.getSessionDate(),
                    s.getStartTime().minusMinutes(gap), s.getEndTime().plusMinutes(gap), null, s.getId())) {
                conflicts.add(when + " trùng buổi dạy khác của coach mới (cần cách nhau ít nhất 15 phút)");
                continue;
            }
            String blocker = coachSlotService.findBookedSlotBlocker(
                    newCoachId, s.getSessionDate(), s.getStartTime(), s.getEndTime());
            if (blocker != null) {
                conflicts.add(when + ": " + blocker);
                continue;
            }
        }
        if (!conflicts.isEmpty()) {
            throw new BusinessRuleException("Coach mới không rảnh hết các buổi còn lại: "
                    + String.join("; ", conflicts.stream().limit(10).toList())
                    + (conflicts.size() > 10 ? " (và " + (conflicts.size() - 10) + " mục khác)" : ""));
        }

        for (ClassSession s : upcoming) {
            s.setCoach(newCoach);
            classSessionRepository.saveAndFlush(s);
        }
        sportClass.setCoach(newCoach);
        sportClassRepository.saveAndFlush(sportClass);

        auditService.log(manager, "CLASS_COACH_REPLACED", "sport_class", id, Map.of(
                "fromCoachId", oldCoach.getId(), "toCoachId", newCoachId, "reason", reason,
                "updatedSessions", upcoming.size()));
        notificationService.sendToClass(id, "ANNOUNCEMENT", "Đã đổi coach",
                "Coach của lớp " + sportClass.getName() + " đã đổi sang " + newCoach.getFullName() + " (" + reason + ").");
        notificationService.send(newCoach.getId(), "ANNOUNCEMENT", "Bạn được phân công dạy lớp",
                "Bạn được giao dạy tiếp lớp " + sportClass.getName() + " (" + upcoming.size() + " buổi còn lại).");
        notificationService.send(oldCoach.getId(), "ANNOUNCEMENT", "Bạn không còn dạy lớp này",
                "Lớp " + sportClass.getName() + " đã chuyển sang coach " + newCoach.getFullName() + " (" + reason + ").");
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

    // Lớp nháp có ngày tự mở đã đến hạn thì mở đăng ký nếu đủ điều kiện. Chưa mở được thì bỏ qua và báo manager
    // đúng ngày hẹn (một lần); những ngày sau job vẫn thử lại, manager sinh đủ buổi xong là lớp tự mở
    private void autoOpenDueClasses(LocalDate today) {
        for (Long id : sportClassRepository.findDraftIdsDueToOpen(today)) {
            SportClass sportClass = sportClassRepository.findByIdForUpdate(id).orElse(null);
            if (sportClass == null || !"DRAFT".equals(sportClass.getStatus())) {
                continue;
            }
            String problem = problemOpening(sportClass);
            if (problem == null) {
                sportClass.setStatus("OPEN");
                sportClassRepository.save(sportClass);
                log.info("Auto-opened registration of class {}", id);
            } else if (today.equals(sportClass.getRegistrationOpensOn())) {
                for (User manager : userRepository.findByRole_CodeAndIsActiveTrue("MANAGER")) {
                    notificationService.send(manager.getId(), "ANNOUNCEMENT", "Lớp chưa tự mở đăng ký được",
                            sportClass.getName() + " đến hạn tự mở đăng ký hôm nay nhưng chưa mở được: "
                                    + problem + ".");
                }
            }
        }
    }

    // Còn bao nhiêu ngày tới ngày khai giảng thì nhắc manager nếu lớp vẫn nháp. Job chạy mỗi ngày một lần nên mỗi mốc chỉ nhắc một lần
    private static final List<Integer> UNOPENED_REMINDER_DAYS = List.of(7, 1);

    // Lớp vẫn nháp khi sắp khai giảng (kể cả lớp không hẹn ngày tự mở): nhắc manager mở đăng ký hoặc dời ngày khai giảng,
    // kèm lý do chưa mở được nếu có. Quá ngày khai giảng thì lớp nháp không mở được nữa nên phải nhắc trước đó
    private void remindUnopenedClasses(LocalDate today) {
        List<LocalDate> dates = UNOPENED_REMINDER_DAYS.stream().map(today::plusDays).toList();
        for (Long id : sportClassRepository.findDraftIdsStartingOn(dates)) {
            SportClass sportClass = sportClassRepository.findWithDetailsById(id).orElse(null);
            if (sportClass == null || !"DRAFT".equals(sportClass.getStatus())) {
                continue;
            }
            long days = ChronoUnit.DAYS.between(today, sportClass.getStartDate());
            String problem = problemOpening(sportClass);
            String text = sportClass.getName() + " khai giảng ngày " + sportClass.getStartDate() + " (còn " + days
                    + " ngày) nhưng chưa mở đăng ký. "
                    + (problem == null ? "Hãy mở đăng ký thủ công hoặc dời ngày khai giảng." : problem + ".");
            for (User manager : userRepository.findByRole_CodeAndIsActiveTrue("MANAGER")) {
                notificationService.send(manager.getId(), "ANNOUNCEMENT", "Lớp chưa mở đăng ký", text);
            }
        }
    }

    // BR-CLS-06: chạy hằng ngày, đưa class sang đang học / kết thúc theo ngày
    @Transactional
    public void advanceLifecycle(LocalDate today) {
        autoOpenDueClasses(today);
        remindUnopenedClasses(today);
        // BR-WL-04: đến ngày khai giảng, danh sách chờ đóng
        int closedWaitlist = classRegistrationRepository.closeWaitlistsOfStartedClasses(OffsetDateTime.now(), today);
        if (closedWaitlist > 0) {
            log.info("Closed {} waitlist entr(ies) of started classes", closedWaitlist);
        }
        int started = sportClassRepository.startClasses(today);
        List<Long> finished = sportClassRepository.findFinishedClassIds(today);
        int completed = sportClassRepository.completeFinishedClasses(today);
        finished.forEach(this::onClassCompleted);
        log.info("Class lifecycle: {} class(es) started, {} class(es) completed", started, completed);
    }

    // Lớp kết thúc: học viên còn học được báo đang chờ coach xếp loại;
    // coach được nhắc chấm xếp loại cuối khóa
    private void onClassCompleted(Long classId) {
        SportClass sportClass = sportClassRepository.findWithDetailsById(classId).orElse(null);
        if (sportClass == null) {
            return;
        }
        List<ClassRegistration> students = classRegistrationRepository
                .findBySportClass_IdAndStatusInOrderById(classId, List.of("REGISTERED"));
        for (ClassRegistration r : students) {
            Long memberId = r.getUser().getId();
            notificationService.send(memberId, "ANNOUNCEMENT", "Lớp đã kết thúc",
                    sportClass.getName() + " đã kết thúc. Coach sẽ sớm chấm điểm cuối lớp cho bạn.");
        }
        if (!students.isEmpty()) {
            notificationService.send(sportClass.getCoach().getId(), "ANNOUNCEMENT", "Hãy chấm điểm lớp của bạn",
                    sportClass.getName() + " đã kết thúc. Hãy chấm điểm và nhận xét cho từng học viên trong " + students.size()
                            + " học viên.");
        }
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
                        HttpStatus.NOT_FOUND, "Không tìm thấy lớp có mã " + id));

        return toResponse(sportClass);
    }

    // BR-SCH-04: coach phải có vai trò COACH, đang hoạt động và dạy đúng môn của course
    User findTeachingCoach(Long coachId, Long categoryId) {
        User coach = userRepository.findById(coachId)
                .orElseThrow(() -> new ResponseStatusException(
                        HttpStatus.BAD_REQUEST, "Không tìm thấy coach có mã " + coachId));

        if (!Boolean.TRUE.equals(coach.getIsActive())) {
            throw new BusinessRuleException("Coach không còn hoạt động");
        }
        if (coach.getRole() == null || !"COACH".equalsIgnoreCase(coach.getRole().getCode())) {
            throw new BusinessRuleException("Người được chọn không phải coach");
        }

        CoachProfile profile = coachProfileRepository.findById(coachId)
                .orElseThrow(() -> new BusinessRuleException("Coach chưa có hồ sơ"));
        boolean teachesSport = profile.getSports().stream().anyMatch(s -> s.getId().equals(categoryId));
        if (!teachesSport) {
            throw new BusinessRuleException("Coach không dạy môn của khóa này");
        }
        return coach;
    }

    // Ngày tự mở (nếu có) phải trước ngày khai giảng và không nằm trong quá khứ.
    // currentOpensOn: ngày đang lưu của lớp, giữ nguyên ngày cũ thì không bắt lỗi quá khứ khi sửa các trường khác
    private void validateDates(LocalDate startDate, LocalDate registrationOpensOn, LocalDate currentOpensOn) {
        LocalDate today = LocalDate.now(CENTER_ZONE);
        if (startDate.isBefore(today)) {
            throw new BusinessRuleException("Ngày khai giảng không được ở quá khứ");
        }
        if (registrationOpensOn == null) {
            return;
        }
        if (!registrationOpensOn.isBefore(startDate)) {
            throw new BusinessRuleException("Ngày tự mở đăng ký phải trước ngày khai giảng");
        }
        if (registrationOpensOn.isBefore(today) && !registrationOpensOn.equals(currentOpensOn)) {
            throw new BusinessRuleException("Ngày tự mở đăng ký không được ở quá khứ");
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
