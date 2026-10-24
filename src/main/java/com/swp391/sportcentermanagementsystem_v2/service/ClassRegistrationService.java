package com.swp391.sportcentermanagementsystem_v2.service;

import com.swp391.sportcentermanagementsystem_v2.dto.requestDTO.ClassRegistrationRequest;
import com.swp391.sportcentermanagementsystem_v2.dto.responseDTO.ClassRegistrationResponse;
import com.swp391.sportcentermanagementsystem_v2.entity.ClassRegistration;
import com.swp391.sportcentermanagementsystem_v2.entity.Payment;
import com.swp391.sportcentermanagementsystem_v2.entity.SportClass;
import com.swp391.sportcentermanagementsystem_v2.entity.User;
import com.swp391.sportcentermanagementsystem_v2.exception.BusinessRuleException;
import com.swp391.sportcentermanagementsystem_v2.repository.ClassRegistrationRepository;
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

    // ====================== BOOKING ======================

    private ClassRegistrationResponse register(User member, Long classId, User registeredBy,
            boolean acknowledgeAdvancedWarning) {
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

        if (classRegistrationRepository.existsBySportClass_IdAndUser_IdAndStatusIn(
                classId, member.getId(), ACTIVE_STATUSES)) {
            throw new BusinessRuleException("This member has already registered for this class");
        }

        long taken = classRegistrationRepository.countBySportClass_IdAndStatusIn(classId, SEAT_STATUSES);
        if (taken >= sportClass.getMaxCapacity()) {
            throw new BusinessRuleException("This class is full");
        }

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
        if (REGISTERED.equals(registration.getStatus())) {
            return cancelPaid(registration, canceledBy);
        }
        if (!PENDING_PAYMENT.equals(registration.getStatus())) {
            throw new BusinessRuleException("Only a registration waiting for payment or already registered can be canceled");
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
        return toResponse(classRegistrationRepository.save(registration));
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
        return new ClassRegistrationResponse(r.getId(), r.getSportClass().getId(), r.getSportClass().getName(),
                r.getUser().getId(), r.getUser().getFullName(), r.getRegisteredAt(), r.getStatus(),
                r.getCanceledAt(), r.getHoldExpiresAt(), r.getSportClass().getTuition(), null);
    }
}
