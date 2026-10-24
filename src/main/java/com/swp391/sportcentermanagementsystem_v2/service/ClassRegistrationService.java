package com.swp391.sportcentermanagementsystem_v2.service;

import com.swp391.sportcentermanagementsystem_v2.dto.requestDTO.ClassRegistrationRequest;
import com.swp391.sportcentermanagementsystem_v2.dto.responseDTO.ClassRegistrationResponse;
import com.swp391.sportcentermanagementsystem_v2.entity.ClassRegistration;
import com.swp391.sportcentermanagementsystem_v2.entity.Payment;
import com.swp391.sportcentermanagementsystem_v2.entity.SportClass;
import com.swp391.sportcentermanagementsystem_v2.entity.User;
import com.swp391.sportcentermanagementsystem_v2.exception.BusinessRuleException;
import com.swp391.sportcentermanagementsystem_v2.repository.ClassRegistrationRepository;
import com.swp391.sportcentermanagementsystem_v2.repository.ClassSessionRepository;
import com.swp391.sportcentermanagementsystem_v2.repository.CoachSlotRepository;
import com.swp391.sportcentermanagementsystem_v2.repository.PaymentRepository;
import com.swp391.sportcentermanagementsystem_v2.repository.SportClassRepository;
import com.swp391.sportcentermanagementsystem_v2.repository.UserRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;

import java.time.LocalDate;
import java.math.BigDecimal;
import java.time.OffsetDateTime;
import java.util.List;
import java.util.Optional;

@Service
@RequiredArgsConstructor
public class ClassRegistrationService {

    private static final String PENDING_PAYMENT = "PENDING_PAYMENT";
    private static final String REGISTERED = "REGISTERED";
    private static final String WAITLISTED = "WAITLISTED";
    private static final String CANCELED = "CANCELED";
    // Rời lớp sau ngày khai giảng (không hoàn tiền)
    private static final String LEFT = "LEFT";

    // Chỗ bị chiếm: đang giữ chỗ chờ thanh toán hoặc đã ghi danh
    private static final List<String> SEAT_STATUSES = List.of(PENDING_PAYMENT, REGISTERED);
    private static final List<String> ACTIVE_STATUSES = List.of(PENDING_PAYMENT, REGISTERED, WAITLISTED);

    private final ClassRegistrationRepository classRegistrationRepository;
    private final SportClassRepository sportClassRepository;
    private final UserRepository userRepository;
    private final SystemSettingService systemSettingService;
    private final PaymentRepository paymentRepository;
    private final CoachSlotRepository coachSlotRepository;
    private final RefundService refundService;
    private final WaitlistService waitlistService;
    private final ClassSessionRepository classSessionRepository;

    // ====================== MEMBER ======================

    // Hội viên tự ghi danh (userId lấy từ token, không nhận từ body)
    @Transactional
    public ClassRegistrationResponse registerMyself(String memberEmail, Long classId,
            boolean acknowledgeAdvancedWarning) {
        User member = getUserByEmail(memberEmail);
        return register(member, classId, null, acknowledgeAdvancedWarning);
    }

    // Hội viên tự hủy đăng ký của chính mình
    @Transactional
    public ClassRegistrationResponse cancelMyRegistration(String memberEmail, Long registrationId) {
        User member = getUserByEmail(memberEmail);
        return cancel(registrationId, member.getId(), "member");
    }

    // BR-MKP-07: số buổi bị trung tâm hủy vượt ngưỡng (30%) thì học viên được rời lớp, hoàn theo số buổi chưa học.
    // Trước ngày khai giảng thì hủy đăng ký như thường (hoàn đủ), nên chỉ dùng được khi lớp đang học
    @Transactional
    public ClassRegistrationResponse leaveAfterCancellations(String memberEmail, Long registrationId) {
        User member = getUserByEmail(memberEmail);
        ClassRegistration listed = classRegistrationRepository.findById(registrationId)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "Registration not found"));
        Long classId = listed.getSportClass().getId();
        // Thứ tự khóa: class, đăng ký, rồi payment
        SportClass sportClass = sportClassRepository.findByIdForUpdate(classId)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "Class not found"));
        ClassRegistration registration = getRegistrationForUpdate(registrationId);

        if (!registration.getUser().getId().equals(member.getId())) {
            throw new ResponseStatusException(HttpStatus.FORBIDDEN, "You can only leave your own registrations");
        }
        if (!REGISTERED.equals(registration.getStatus()) || !"IN_PROGRESS".equals(sportClass.getStatus())) {
            throw new BusinessRuleException("Only a registered member of a class in progress can leave because of canceled sessions");
        }

        long total = classSessionRepository.countBySportClassIdAndMakeupFalse(classId);
        long canceled = classSessionRepository.countBySportClassIdAndStatusIn(classId, List.of("CANCELED_BY_CENTER"));
        int thresholdPct = systemSettingService.getInt("makeup.leave_threshold_pct");
        if (total == 0 || canceled * 100 <= total * thresholdPct) {
            throw new BusinessRuleException("You can leave only when more than " + thresholdPct
                    + "% of the sessions were canceled by the center (" + canceled + " of " + total + " so far)");
        }

        LocalDate today = LocalDate.now(SportClassService.CENTER_ZONE);
        // Buổi chưa học = số buổi gốc trừ số buổi đã học, nên buổi bị hủy chưa bù vẫn được hoàn. Buổi bù đã báo
        // không đi được thì đã hoàn riêng rồi nên không tính lại
        long remaining = total - classSessionRepository.countStudied(classId, today)
                - classSessionRepository.countDeclinedUpcoming(classId, member.getId(), today);

        registration.setStatus(LEFT);
        registration.setCanceledAt(OffsetDateTime.now());
        BigDecimal refundAmount = null;
        Optional<Payment> paid = paymentRepository
                .findFirstByOrder_Registration_IdAndStatusOrderByIdDesc(registrationId, "SUCCESS");
        if (paid.isPresent()) {
            BigDecimal amount = SportClassService
                    .classCancelRefund(paid.get().getAmount(), true, total, Math.max(remaining, 0))
                    .min(refundService.refundable(paid.get()));
            if (amount.signum() > 0) {
                refundAmount = refundService.createRefund(paid.get(), amount,
                        "Left the class after sessions were canceled by the center").getAmount();
            }
        }

        ClassRegistrationResponse response = toResponse(classRegistrationRepository.save(registration));
        response.setRefundAmount(refundAmount);
        return response;
    }

    @Transactional(readOnly = true)
    public List<ClassRegistrationResponse> getMyRegistrations(String memberEmail) {
        User member = getUserByEmail(memberEmail);
        return classRegistrationRepository.findByUser_IdOrderByRegisteredAtDesc(member.getId())
                .stream()
                .map(this::toResponse)
                .toList();
    }

    // ====================== RECEPTIONIST ======================

    // BR-ENR-06: lễ tân ghi danh hộ member theo đúng các luật như member tự ghi danh
    @Transactional
    public ClassRegistrationResponse registerMemberByReceptionist(String receptionistEmail,
            ClassRegistrationRequest request) {
        User receptionist = getUserByEmail(receptionistEmail);
        User member = userRepository.findById(request.getUserId())
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "No member found!"));
        return register(member, request.getClassId(), receptionist, request.isAcknowledgeAdvancedWarning());
    }

    @Transactional
    public ClassRegistrationResponse cancelRegistrationByReceptionist(Long registrationId) {
        return cancel(registrationId, null, "receptionist");
    }

    @Transactional(readOnly = true)
    public List<ClassRegistrationResponse> getRegistrationsByMemberId(Long memberId) {
        if (!userRepository.existsById(memberId)) {
            throw new ResponseStatusException(HttpStatus.NOT_FOUND, "No member found with ID: " + memberId);
        }
        return classRegistrationRepository.findByUser_IdOrderByRegisteredAtDesc(memberId)
                .stream()
                .map(this::toResponse)
                .toList();
    }

    // ====================== FLOW 3 / SCHEDULER ======================

    // Gọi khi thanh toán thành công: PENDING_PAYMENT -> REGISTERED. Gọi lại thì không làm gì thêm
    @Transactional
    public ClassRegistrationResponse confirmPaid(Long registrationId) {
        ClassRegistration registration = getRegistrationForUpdate(registrationId);

        if (REGISTERED.equals(registration.getStatus())) {
            return toResponse(registration);
        }
        if (!PENDING_PAYMENT.equals(registration.getStatus())) {
            throw new BusinessRuleException("Registration cannot be confirmed (status: "
                    + registration.getStatus() + ")");
        }

        registration.setStatus(REGISTERED);
        registration.setHoldExpiresAt(null);
        return toResponse(classRegistrationRepository.save(registration));
    }

    // Gọi khi PayOS xác nhận đã nhận tiền. Đăng ký còn giữ chỗ thì kích hoạt như bình thường. Nếu đăng ký đã bị
    // hủy vì hết hạn giữ chỗ mà tiền về muộn (BR-QR-04) thì kích hoạt lại khi class còn mở, còn chỗ và member
    // chưa ghi danh lại. Trả false nếu không kích hoạt được: khi đó khoản tiền phải được hoàn
    @Transactional
    public boolean activatePaidOnline(Long registrationId) {
        ClassRegistration registration = getRegistrationForUpdate(registrationId);

        if (REGISTERED.equals(registration.getStatus())) {
            return true;
        }
        if (PENDING_PAYMENT.equals(registration.getStatus())) {
            registration.setStatus(REGISTERED);
            registration.setHoldExpiresAt(null);
            classRegistrationRepository.save(registration);
            return true;
        }
        if (!CANCELED.equals(registration.getStatus())) {
            return false;
        }

        SportClass sportClass = sportClassRepository.findByIdForUpdate(registration.getSportClass().getId())
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "Class not found"));
        Long memberId = registration.getUser().getId();
        boolean registrationOpen = "OPEN".equals(sportClass.getStatus())
                && LocalDate.now(SportClassService.CENTER_ZONE).isBefore(sportClass.getStartDate());
        boolean hasSeat = classRegistrationRepository.countBySportClass_IdAndStatusIn(sportClass.getId(), SEAT_STATUSES)
                < sportClass.getMaxCapacity();
        boolean registeredAgain = classRegistrationRepository.existsBySportClass_IdAndUser_IdAndStatusIn(
                sportClass.getId(), memberId, ACTIVE_STATUSES);
        if (!registrationOpen || !hasSeat || registeredAgain) {
            return false;
        }

        registration.setStatus(REGISTERED);
        registration.setCanceledAt(null);
        registration.setHoldExpiresAt(null);
        classRegistrationRepository.save(registration);
        return true;
    }

    // BR-ENR-03: nhả chỗ các đăng ký quá hạn giữ chỗ và chưa có thanh toán nào đang xử lý.
    // Được gọi từ đợt đối soát thanh toán trong SubscriptionScheduler
    @Transactional
    public int expireHolds() {
        return classRegistrationRepository.expireHolds(OffsetDateTime.now());
    }

    // PayOS báo khách đã hủy QR: nhả chỗ ngay, không chờ hết hạn giữ chỗ. Bỏ qua nếu không còn PENDING_PAYMENT
    // hoặc còn thanh toán chưa ngã ngũ (vd. QR mới)
    @Transactional
    public void releaseHeld(Long registrationId) {
        ClassRegistration registration = classRegistrationRepository.findByIdForUpdate(registrationId).orElse(null);
        if (registration == null || !PENDING_PAYMENT.equals(registration.getStatus())
                || paymentRepository.existsByRegistration_IdAndStatusIn(registrationId,
                        List.of("CREATING", "PENDING", "SUCCESS"))) {
            return;
        }
        registration.setStatus(CANCELED);
        registration.setCanceledAt(OffsetDateTime.now());
        classRegistrationRepository.save(registration);
        waitlistService.promote(registration.getSportClass().getId());
    }

    // ====================== BOOKING ======================

    private ClassRegistrationResponse register(User member, Long classId, User registeredBy,
            boolean acknowledgeAdvancedWarning) {
        SportClass sportClass = lockClassForSignup(member, classId, acknowledgeAdvancedWarning);

        if (classRegistrationRepository.existsBySportClass_IdAndUser_IdAndStatusIn(
                classId, member.getId(), ACTIVE_STATUSES)) {
            throw new BusinessRuleException("This member has already registered for this class");
        }

        long taken = classRegistrationRepository.countBySportClass_IdAndStatusIn(classId, SEAT_STATUSES);
        if (taken >= sportClass.getMaxCapacity()) {
            throw new BusinessRuleException("This class is full, you can join the waitlist");
        }

        checkNoScheduleConflict(member, classId);

        ClassRegistration registration = new ClassRegistration();
        registration.setSportClass(sportClass);
        registration.setUser(member);
        registration.setRegisteredBy(registeredBy); // null nếu hội viên tự ghi danh
        if (sportClass.getTuition().signum() == 0) {
            registration.setStatus(REGISTERED); // lớp miễn phí, không cần thanh toán
        } else {
            registration.setStatus(PENDING_PAYMENT);
            registration.setHoldExpiresAt(OffsetDateTime.now()
                    .plusMinutes(systemSettingService.getInt("payment.hold_minutes")));
        }

        return toResponse(classRegistrationRepository.save(registration));
    }

    // BR-WL-01: class hết chỗ thì vào danh sách chờ miễn phí. Cùng điều kiện mở ghi danh như đăng ký thường.
    // Thứ tự chờ (BR-WL-02) chốt theo hạng gói tại lúc vào chờ
    @Transactional
    public ClassRegistrationResponse joinWaitlist(String memberEmail, Long classId, boolean acknowledgeAdvancedWarning) {
        User member = getUserByEmail(memberEmail);
        SportClass sportClass = lockClassForSignup(member, classId, acknowledgeAdvancedWarning);

        if (classRegistrationRepository.existsBySportClass_IdAndUser_IdAndStatusIn(
                classId, member.getId(), ACTIVE_STATUSES)) {
            throw new BusinessRuleException("This member has already registered or joined the waitlist for this class");
        }
        long taken = classRegistrationRepository.countBySportClass_IdAndStatusIn(classId, SEAT_STATUSES);
        if (taken < sportClass.getMaxCapacity()) {
            throw new BusinessRuleException("This class still has free seats, register directly");
        }
        checkNoScheduleConflict(member, classId);

        ClassRegistration registration = new ClassRegistration();
        registration.setSportClass(sportClass);
        registration.setUser(member);
        registration.setStatus(WAITLISTED);
        registration.setWaitlistRank(classRegistrationRepository
                .findWaitlistPriority(member.getId(), LocalDate.now(SportClassService.CENTER_ZONE)).shortValue());
        return toResponse(classRegistrationRepository.save(registration));
    }

    // Danh sách chờ của một class theo thứ tự được mời (manager, lễ tân xem)
    @Transactional(readOnly = true)
    public List<ClassRegistrationResponse> getWaitlist(Long classId) {
        if (!sportClassRepository.existsById(classId)) {
            throw new ResponseStatusException(HttpStatus.NOT_FOUND, "Class not found");
        }
        return classRegistrationRepository.findWaitlist(classId).stream().map(this::toResponse).toList();
    }

    // Khóa dòng class rồi kiểm các điều kiện chung của đăng ký và vào chờ
    private SportClass lockClassForSignup(User member, Long classId, boolean acknowledgeAdvancedWarning) {
        // BR-ENR-01: không cần có gói, chỉ cần là MEMBER đang hoạt động
        if (member.getRole() == null || !"MEMBER".equals(member.getRole().getCode())) {
            throw new BusinessRuleException("This user is not a member");
        }
        if (!Boolean.TRUE.equals(member.getIsActive())) {
            throw new BusinessRuleException("This member account is deactivated");
        }

        // Khóa dòng class: các lần ghi danh cùng class chạy lần lượt, nên việc đếm chỗ bên dưới
        // luôn thấy dữ liệu mới nhất và hai người không thể cùng lấy chỗ cuối
        SportClass sportClass = sportClassRepository.findByIdForUpdate(classId)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "Class not found"));
        if (!"OPEN".equals(sportClass.getStatus())) {
            throw new BusinessRuleException("This class is not open for registration");
        }

        // BR-ENR-02: hạn ghi danh kết thúc vào ngày khai giảng. BR-BEN-04: đến ngày mở (trừ ngày đăng ký sớm)
        LocalDate today = LocalDate.now(SportClassService.CENTER_ZONE);
        if (!today.isBefore(sportClass.getStartDate())) {
            throw new BusinessRuleException("Registration is closed, the class starts on " + sportClass.getStartDate());
        }
        LocalDate opensOn = sportClass.getRegistrationOpensOn().minusDays(earlyRegisterDays(member));
        if (today.isBefore(opensOn)) {
            throw new BusinessRuleException("Registration opens on " + opensOn);
        }

        // BR-CLS-07: khóa nâng cao thì mọi lần đăng ký đều phải xác nhận đã đọc cảnh báo
        if ("ADVANCED".equals(sportClass.getCourse().getLevel()) && !acknowledgeAdvancedWarning) {
            throw new BusinessRuleException("This is an advanced course for higher-level students, "
                    + "send acknowledgeAdvancedWarning=true to confirm you want to register");
        }
        return sportClass;
    }

    private void checkNoScheduleConflict(User member, Long classId) {
        // BR-SCH-03: báo trùng với class nào
        classRegistrationRepository
                .findConflictingClassName(member.getId(), classId, systemSettingService.getInt("schedule.buffer_minutes"))
                .ifPresent(name -> {
                    throw new BusinessRuleException("Schedule conflicts with class: " + name);
                });
        // BR-SCH-03 áp dụng cả cho buổi 1-1 đã đặt hoặc đang giữ chỗ của member
        coachSlotRepository
                .findMemberSlotConflictForClass(member.getId(), classId, systemSettingService.getInt("schedule.buffer_minutes"))
                .ifPresent(when -> {
                    throw new BusinessRuleException("Schedule conflicts with your 1-1 session at " + when);
                });
    }

    // shortcut: chưa có Flow 1 nên mọi member đăng ký đúng ngày mở, nối với quyền lợi của gói (BR-BEN-04) khi Flow 1 có
    private int earlyRegisterDays(User member) {
        return 0;
    }

    // ====================== CANCEL ======================

    // requiredOwnerId: member tự hủy thì phải là chủ đăng ký, lễ tân hủy hộ thì truyền null.
    // canceledBy: "member" hoặc "receptionist", ghi vào lý do hoàn tiền
    private ClassRegistrationResponse cancel(Long registrationId, Long requiredOwnerId, String canceledBy) {
        ClassRegistration registration = getRegistrationForUpdate(registrationId);

        if (requiredOwnerId != null && !registration.getUser().getId().equals(requiredOwnerId)) {
            throw new ResponseStatusException(HttpStatus.FORBIDDEN, "You can only cancel your own registrations");
        }
        if (WAITLISTED.equals(registration.getStatus())) {
            // Rời danh sách chờ: không giữ chỗ, không có tiền, không cần mời ai khác
            registration.setStatus(CANCELED);
            registration.setCanceledAt(OffsetDateTime.now());
            return toResponse(classRegistrationRepository.save(registration));
        }
        if (REGISTERED.equals(registration.getStatus())) {
            ClassRegistrationResponse response = cancelPaid(registration, canceledBy);
            waitlistService.promote(registration.getSportClass().getId());
            return response;
        }
        if (!PENDING_PAYMENT.equals(registration.getStatus())) {
            throw new BusinessRuleException("Only a registration on the waitlist, waiting for payment or already registered can be canceled");
        }

        // Còn thanh toán chưa ngã ngũ (QR đang mở, hoặc đã thu tiền nhưng chưa kích hoạt xong) thì chưa hủy được:
        // nếu hủy mà khách vẫn trả tiền, đăng ký sẽ tự kích hoạt lại trái ý khách.
        // OnlinePaymentService hỏi PayOS trước nên khách đã hủy QR trên PayOS thì không bị chặn ở đây
        if (paymentRepository.existsByRegistration_IdAndStatusIn(registrationId, List.of("CREATING", "PENDING", "SUCCESS"))) {
            throw new BusinessRuleException("A payment is still being processed. Cancel the QR on the payment page "
                    + "or wait for it to expire (up to 15 minutes), then cancel again");
        }

        registration.setStatus(CANCELED);
        registration.setCanceledAt(OffsetDateTime.now());
        ClassRegistrationResponse response = toResponse(classRegistrationRepository.save(registration));
        waitlistService.promote(registration.getSportClass().getId());
        return response;
    }

    // BR-CAN-01: hủy trước ngày khai giảng thì hoàn đủ; từ ngày khai giảng là rời lớp (LEFT), không hoàn.
    // shortcut: quyền lợi "hoàn đủ nếu rời ở những buổi đầu" theo tier (BR-CAN-01) chờ Flow 1, hiện coi như 0 buổi
    private ClassRegistrationResponse cancelPaid(ClassRegistration registration, String canceledBy) {
        SportClass sportClass = registration.getSportClass();
        boolean classStarted = !LocalDate.now(SportClassService.CENTER_ZONE).isBefore(sportClass.getStartDate());

        registration.setStatus(classStarted ? LEFT : CANCELED);
        registration.setCanceledAt(OffsetDateTime.now());

        BigDecimal refundAmount = null;
        if (!classStarted) {
            // Lớp miễn phí hoặc chưa có thanh toán thì không có gì để hoàn
            Optional<Payment> paid = paymentRepository
                    .findFirstByOrder_Registration_IdAndStatusOrderByIdDesc(registration.getId(), "SUCCESS");
            if (paid.isPresent()) {
                refundAmount = refundService.createRefund(paid.get(), paid.get().getAmount(),
                        "Registration canceled by " + canceledBy + " before class start").getAmount();
            }
        }

        ClassRegistrationResponse response = toResponse(classRegistrationRepository.save(registration));
        response.setRefundAmount(refundAmount);
        return response;
    }

    // ====================== HELPERS ======================

    private User getUserByEmail(String email) {
        return userRepository.findByEmail(email)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "User not found"));
    }

    private ClassRegistration getRegistrationForUpdate(Long registrationId) {
        return classRegistrationRepository.findByIdForUpdate(registrationId)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "Registration not found"));
    }

    private ClassRegistrationResponse toResponse(ClassRegistration r) {
        // Vị trí trong hàng chờ = số người đứng trước + 1 (hạng gói cao hơn, hoặc cùng hạng mà vào trước)
        Integer position = WAITLISTED.equals(r.getStatus())
                ? (int) classRegistrationRepository.countWaitlistAhead(r.getSportClass().getId(),
                        r.getWaitlistRank(), r.getRegisteredAt(), r.getId()) + 1
                : null;
        return new ClassRegistrationResponse(r.getId(), r.getSportClass().getId(), r.getSportClass().getName(),
                r.getUser().getId(), r.getUser().getFullName(), r.getRegisteredAt(), r.getStatus(),
                r.getCanceledAt(), r.getHoldExpiresAt(), r.getSportClass().getTuition(), null,
                r.getOfferExpiresAt(), position);
    }
}
