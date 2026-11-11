package com.swp391.sportcentermanagementsystem_v2.service;

import com.swp391.sportcentermanagementsystem_v2.dto.responseDTO.CoachSlotResponse;
import com.swp391.sportcentermanagementsystem_v2.entity.CoachSlot;
import com.swp391.sportcentermanagementsystem_v2.entity.CoachSlotRequest;
import com.swp391.sportcentermanagementsystem_v2.entity.FacilityRoom;
import com.swp391.sportcentermanagementsystem_v2.entity.SportCategory;
import com.swp391.sportcentermanagementsystem_v2.entity.Payment;
import com.swp391.sportcentermanagementsystem_v2.entity.TimeSlot;
import com.swp391.sportcentermanagementsystem_v2.entity.User;
import com.swp391.sportcentermanagementsystem_v2.exception.BusinessRuleException;
import com.swp391.sportcentermanagementsystem_v2.repository.ClassSessionRepository;
import com.swp391.sportcentermanagementsystem_v2.repository.CoachProfileRepository;
import com.swp391.sportcentermanagementsystem_v2.repository.CoachSlotRepository;
import com.swp391.sportcentermanagementsystem_v2.repository.CoachSlotRequestRepository;
import com.swp391.sportcentermanagementsystem_v2.repository.FacilityRoomRepository;
import com.swp391.sportcentermanagementsystem_v2.repository.SportCategoryRepository;
import com.swp391.sportcentermanagementsystem_v2.repository.OrderRepository;
import com.swp391.sportcentermanagementsystem_v2.repository.PaymentRepository;
import com.swp391.sportcentermanagementsystem_v2.repository.UserRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.Duration;
import java.time.LocalDate;
import java.time.LocalTime;
import java.time.OffsetDateTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.Optional;

// Buổi 1-1 (BR-ONE): buổi chỉ sinh ra từ yêu cầu đã duyệt (CoachSlotRequestService), giá chung cho mọi coach theo thời lượng
@Slf4j
@Service
@RequiredArgsConstructor
public class CoachSlotService {

    private static final int LOOKAHEAD_DAYS = 90;
    private static final List<String> ACTIVE_STATUSES = List.of("OPEN", "HELD", "BOOKED");
    private static final List<String> IN_FLIGHT_PAYMENT = List.of("CREATING", "PENDING", "SUCCESS");
    private static final DateTimeFormatter WHEN = DateTimeFormatter.ofPattern("dd/MM HH:mm")
            .withZone(SportClassService.CENTER_ZONE);

    private final CoachSlotRepository slotRepository;
    private final CoachSlotRequestRepository requestRepository;
    private final UserRepository userRepository;
    private final CoachProfileRepository coachProfileRepository;
    private final PaymentRepository paymentRepository;
    private final OrderRepository orderRepository;
    private final RefundService refundService;
    private final AuditService auditService;
    private final SystemSettingService settings;
    private final CenterHoursService centerHours;
    private final NotificationService notificationService;
    private final TimeSlotService timeSlotService;
    private final ClassSessionRepository classSessionRepository;
    private final FacilityRoomRepository facilityRoomRepository;
    private final SportCategoryRepository sportCategoryRepository;

    // ====================== MANAGER (trung tâm quyết định lịch 1-1) ======================

    // Một buổi tại ngày x khung giờ, dùng khi duyệt yêu cầu. saveAndFlush để lỗi từ database (hai suất trùng cùng lúc) nổ ngay tại đây
    CoachSlot createSlot(User coach, SportCategory sport, FacilityRoom fixedRoom, LocalDate date, TimeSlot timeSlot) {
        OffsetDateTime start = TimeSlotService.startAt(timeSlot, date);
        OffsetDateTime end = start.plusMinutes(slotMinutes(timeSlot, date));
        if (!start.isAfter(OffsetDateTime.now())) {
            throw new BusinessRuleException("Khung giờ phải bắt đầu trong tương lai");
        }
        centerHours.requireWithin(start, end, "A 1-1 slot");
        checkCoachFree(coach.getId(), start, end);

        FacilityRoom room = fixedRoom;
        if (room == null) {
            room = facilityRoomRepository.findAvailableWithSports().stream()
                    .filter(r -> roomUsable(r, sport.getId(), date, timeSlot.getStartTime(), timeSlot.getEndTime(), start, end))
                    .findFirst()
                    .orElseThrow(() -> new BusinessRuleException("Không còn phòng trống cho " + sport.getName()));
        } else if (!roomUsable(room, sport.getId(), date, timeSlot.getStartTime(), timeSlot.getEndTime(), start, end)) {
            throw new BusinessRuleException("Room " + room.getRoomName() + " không dùng được cho " + sport.getName());
        }

        CoachSlot slot = new CoachSlot();
        slot.setCoach(coach);
        slot.setCategory(sport);
        slot.setRoom(room);
        slot.setStartAt(start);
        slot.setEndAt(end);
        return slotRepository.saveAndFlush(slot);
    }

    // Suất 1-1 chỉ lấy phần đầu của khung giờ (mặc định 60 phút), không chiếm cả khung dài của lớp
    long slotMinutes(TimeSlot timeSlot, LocalDate date) {
        long frame = Duration.between(TimeSlotService.startAt(timeSlot, date), TimeSlotService.endAt(timeSlot, date)).toMinutes();
        return Math.min(settings.getInt("one_on_one.slot_minutes", 60), frame);
    }

    // Danh sách suất 1-1 (mọi coach) cho bảng quản lý; bỏ trống bộ lọc nào thì không lọc theo đó. Mặc định từ hôm nay
    @Transactional(readOnly = true)
    public List<CoachSlotResponse> managerList(OffsetDateTime from, OffsetDateTime to, Long coachId, String status) {
        OffsetDateTime effectiveFrom = from != null ? from : OffsetDateTime.now().minusDays(1);
        OffsetDateTime effectiveTo = to != null ? to : effectiveFrom.plusDays(LOOKAHEAD_DAYS);
        return toResponses(slotRepository.searchForManager(coachId, status, effectiveFrom, effectiveTo));
    }

    boolean coachTeaches(Long coachId, Long sportId) {
        return coachProfileRepository.findWithSportsByUserIds(List.of(coachId)).stream()
                .anyMatch(p -> p.getSports().stream().anyMatch(sp -> sp.getId().equals(sportId)));
    }

    // Phòng dùng được cho môn, đang AVAILABLE và không bị buổi học hay suất khác chiếm (kể cả khoảng nghỉ)
    boolean roomUsable(FacilityRoom room, Long sportId, LocalDate date, LocalTime from, LocalTime to,
                       OffsetDateTime start, OffsetDateTime end) {
        if (!"AVAILABLE".equalsIgnoreCase(room.getMaintenanceStatus())
                || room.getSports().stream().noneMatch(sp -> sp.getId().equals(sportId))) {
            return false;
        }
        int buffer = settings.getScheduleBufferMinutes();
        return !slotRepository.existsRoomOverlapping(room.getId(), start.minusMinutes(buffer), end.plusMinutes(buffer))
                && !classSessionRepository.existsRoomConflict(room.getId(), date,
                        from.minusMinutes(buffer), to.plusMinutes(buffer), null, null);
    }

    // ====================== COACH (chỉ xem) ======================

    @Transactional(readOnly = true)
    public List<CoachSlotResponse> mySlots(String coachEmail) {
        User coach = activeUser(coachEmail, "COACH", "Chỉ coach đang hoạt động mới xem được lịch của mình");
        return toResponses(slotRepository.findCoachSchedule(coach.getId(), OffsetDateTime.now()));
    }

    // Coach ghi chú riêng cho buổi 1-1 của chính mình. Có thể cập nhật cả sau khi buổi đã diễn ra.
    @Transactional
    public CoachSlotResponse updateOwnSlotNote(String coachEmail, Long slotId, String note) {
        User coach = activeUser(coachEmail, "COACH", "Chỉ coach đang hoạt động mới cập nhật được ghi chú");
        CoachSlot slot = lockSlot(slotId);
        if (!slot.getCoach().getId().equals(coach.getId())) {
            throw new BusinessRuleException("Bạn không có quyền ghi chú buổi 1-1 này");
        }
        if (!"BOOKED".equals(slot.getStatus())) {
            throw new BusinessRuleException("Chỉ buổi 1-1 đã được đặt mới có thể ghi chú");
        }
        slot.setCoachNote(note == null || note.isBlank() ? null : note.trim());
        return toResponse(slotRepository.saveAndFlush(slot));
    }

    // BR-ONE-05: trung tâm hủy buổi đã đặt (coach bận, phòng hỏng): member được hoàn đủ, coach được báo.
    // Suất không mở lại
    @Transactional
    public CoachSlotResponse managerCancel(String managerEmail, Long slotId, String reason) {
        User manager = activeUser(managerEmail, "MANAGER", "Chỉ quản lý đang hoạt động mới hủy được buổi đã đặt");
        CoachSlot slot = lockSlot(slotId);
        if (!List.of("HELD", "BOOKED").contains(slot.getStatus())) {
            throw new BusinessRuleException("Chỉ hủy được buổi đang giữ chỗ hoặc đã đặt (trạng thái hiện tại: " + slot.getStatus() + ")");
        }
        OffsetDateTime now = OffsetDateTime.now();
        if (!slot.getStartAt().isAfter(now)) {
            throw new BusinessRuleException("Buổi học đã bắt đầu");
        }

        // Nếu member đang trả dở, tiền về sau sẽ không kích hoạt được (suất đã hủy) và được hoàn tự động
        BigDecimal refunded = refundIfPaid(slot, "Buổi 1-1 bị trung tâm hủy: " + reason);

        slot.setStatus("CANCELED");
        slot.setCanceledBy(manager);
        slot.setCanceledAt(now);
        slotRepository.saveAndFlush(slot);

        if ("ONE_ON_ONE".equals(slot.getKind())) {
        }
        auditService.log(manager, "COACH_SLOT_CANCELED", "coach_slot", slotId, Map.of(
                "reason", reason,
                "memberId", slot.getMember().getId(),
                "refundAmount", refunded == null ? 0 : refunded));
        notificationService.sendWithEmail(slot.getMember().getId(), "BOOKING", "Trung tâm đã hủy buổi học",
                "Buổi của bạn ngày " + WHEN.format(slot.getStartAt()) + " đã bị trung tâm hủy (" + reason + ").",
                "Sport Center - Buổi 1-1 của bạn đã bị hủy",
                "Trung tâm đã hủy buổi 1-1 lúc " + WHEN.format(slot.getStartAt()) + ". Lý do: " + reason
                        + ". Bạn được hoàn đủ tiền.");
        notificationService.send(slot.getCoach().getId(), "BOOKING", "Trung tâm đã hủy buổi học",
                "Buổi 1-1 của bạn ngày " + WHEN.format(slot.getStartAt()) + " đã bị trung tâm hủy (" + reason + ").");

        CoachSlotResponse response = toResponse(slot);
        response.setRefundAmount(refunded);
        return response;
    }

    // BR-MKP-05: coach nghỉ việc hẳn. Với mỗi suất 1-1 sắp tới của coach: suất trống thì đóng; suất đã đặt thì tìm coach
    // khác dạy đúng môn và rảnh đúng giờ để thay (giá chung nên không đổi), không có ai thay được thì hủy và hoàn đủ.
    // Suất đang diễn ra không đổi giữa buổi nên hủy và hoàn đủ. Như khi coach tự hủy, suất bị hủy không mở lại
    @Transactional
    public Map<String, Object> cancelAllForResignedCoach(Long coachId, String managerEmail, String reason) {
        User manager = userRepository.findByEmail(managerEmail)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "Không tìm thấy người dùng"));
        User coach = userRepository.findById(coachId)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "Không tìm thấy coach có mã " + coachId));
        if (coach.getRole() == null || !"COACH".equals(coach.getRole().getCode())) {
            throw new BusinessRuleException("Người dùng này không phải coach");
        }

        List<User> candidates = userRepository.findByRole_CodeAndIsActiveTrue("COACH").stream()
                .filter(c -> !c.getId().equals(coachId)).toList();

        OffsetDateTime now = OffsetDateTime.now();
        int closed = 0;
        int reassigned = 0;
        int canceled = 0;
        int pendingReschedule = 0;
        BigDecimal refundTotal = BigDecimal.ZERO;
        for (CoachSlot listed : slotRepository.findByCoach_IdAndStatusInAndEndAtAfterOrderByStartAt(
                coachId, ACTIVE_STATUSES, now)) {
            // Khóa rồi đọc lại: suất có thể vừa được đặt, thanh toán hoặc hủy
            CoachSlot slot = lockSlot(listed.getId());
            if (!ACTIVE_STATUSES.contains(slot.getStatus())) {
                continue;
            }
            boolean booked = !"OPEN".equals(slot.getStatus());
            User replacement = booked && slot.getStartAt().isAfter(now) ? findReplacement(slot, candidates) : null;

            if (replacement != null) {
                reassign(slot, replacement, reason, manager);
                reassigned++;
                continue;
            }
            // Buổi đã trả tiền của yêu cầu mà không coach nào thay được: giữ buổi và để hội viên chọn coach và giờ mới,
            // hoàn tiền chỉ là bước cuối do manager quyết định (BR-ONE-05)
            if ("BOOKED".equals(slot.getStatus()) && slot.getRequest() != null && slot.getStartAt().isAfter(now)) {
                slot.setNeedsReschedule(true);
                slotRepository.saveAndFlush(slot);
                pendingReschedule++;
                notificationService.send(slot.getMember().getId(), "BOOKING", "Hãy chọn giờ mới cho buổi của bạn",
                        "Buổi của bạn ngày " + WHEN.format(slot.getStartAt())
                                + " không diễn ra được vì coach đã nghỉ việc. Hãy chọn coach và giờ mới.");
                continue;
            }
            if (booked) {
                BigDecimal refunded = refundIfPaid(slot, "Buổi 1-1 đã bị hủy, coach đã nghỉ việc và "
                        + "chưa có coach thay thế: " + reason);
                if (refunded != null) {
                    refundTotal = refundTotal.add(refunded);
                }
                canceled++;
                if ("ONE_ON_ONE".equals(slot.getKind())) {
                }
                notificationService.send(slot.getMember().getId(), "BOOKING", "Buổi học đã bị hủy",
                        "Buổi của bạn ngày " + WHEN.format(slot.getStartAt())
                                + " đã bị hủy và hoàn tiền vì coach đã nghỉ việc.");
            } else {
                closed++;
            }
            slot.setStatus("CANCELED");
            slot.setCanceledBy(manager);
            slot.setCanceledAt(now);
            slotRepository.saveAndFlush(slot);
        }

        auditService.log(manager, "COACH_SLOTS_HANDLED_RESIGNED", "user", coachId, Map.of(
                "reason", reason, "closedOpenSlots", closed, "reassignedBookings", reassigned,
                "canceledBookings", canceled, "pendingReschedule", pendingReschedule, "refundTotal", refundTotal));
        return Map.of("coachId", coachId, "closedOpenSlots", closed, "reassignedBookings", reassigned,
                "canceledBookings", canceled, "pendingReschedule", pendingReschedule, "refundTotal", refundTotal);
    }

    // Coach thay thế: đang hoạt động, dạy đúng môn của suất và rảnh đúng giờ; lấy người có id nhỏ nhất cho kết quả ổn định
    User findReplacement(CoachSlot slot, List<User> candidates) {
        return candidates.stream()
                .filter(c -> slot.getCategory() == null || coachTeaches(c.getId(), slot.getCategory().getId()))
                .sorted(Comparator.comparing(User::getId))
                .filter(c -> isCoachFree(c.getId(), slot.getStartAt(), slot.getEndAt()))
                .findFirst().orElse(null);
    }

    boolean isCoachFree(Long coachId, OffsetDateTime start, OffsetDateTime end) {
        int buffer = settings.getScheduleBufferMinutes();
        return slotRepository.findCoachSessionConflict(coachId, start, end, buffer).isEmpty()
                && slotRepository.findCoachOverlapping(coachId, start.minusMinutes(buffer), end.plusMinutes(buffer), null).isEmpty();
    }

    // Database cấm đổi coach, giờ, phòng hay môn trên dòng suất đã có người đặt (BR-ONE-01, trigger trg_check_coach_slot), nên
    // lượt đặt được chuyển sang một dòng suất mới (cùng giờ, cùng người đặt, cùng trạng thái), đơn và thanh toán đi theo
    // dòng mới, còn dòng cũ bị hủy và không mở lại. Giữ giá đã chụp. Trả dòng mới
    CoachSlot rebook(CoachSlot slot, User coach, FacilityRoom room, User actor) {
        // Hủy dòng cũ trước: phòng và coach chỉ được giữ bởi một dòng chưa hủy tại một thời điểm
        String status = slot.getStatus();
        slot.setStatus("CANCELED");
        slot.setCanceledBy(actor);
        slot.setCanceledAt(OffsetDateTime.now());
        slotRepository.saveAndFlush(slot);

        CoachSlot moved = new CoachSlot();
        moved.setCoach(coach);
        moved.setRoom(room);
        moved.setCategory(slot.getCategory());
        moved.setStartAt(slot.getStartAt());
        moved.setEndAt(slot.getEndAt());
        moved.setKind(slot.getKind());
        moved.setStatus(status);
        moved.setMember(slot.getMember());
        moved.setHeldUntil(slot.getHeldUntil());
        moved.setPrice(slot.getPrice());
        moved.setRequest(slot.getRequest());
        moved.setNeedsReschedule(slot.isNeedsReschedule());
        slotRepository.saveAndFlush(moved);

        orderRepository.findByCoachSlot_Id(slot.getId()).ifPresent(order -> {
            order.setCoachSlot(moved);
            orderRepository.saveAndFlush(order);
        });
        return moved;
    }

    // Đổi coach giữa chừng (coach nghỉ hoặc bận): giá chung nên giá và đơn không đổi, không có chênh lệch để hoàn
    CoachSlot reassign(CoachSlot slot, User replacement, String reason, User manager) {
        User previous = slot.getCoach();
        CoachSlot moved = rebook(slot, replacement, slot.getRoom(), manager);

        auditService.log(manager, "COACH_SLOT_REASSIGNED", "coach_slot", moved.getId(), Map.of(
                "fromSlotId", slot.getId(), "fromCoachId", previous.getId(), "toCoachId", replacement.getId(),
                "reason", reason));
        notificationService.send(slot.getMember().getId(), "BOOKING", "Buổi học được chuyển sang coach khác",
                "Buổi của bạn ngày " + WHEN.format(slot.getStartAt()) + " được chuyển cho coach "
                        + replacement.getFullName() + " vì coach trước không dạy được.");
        return moved;
    }

    // ====================== MEMBER ======================

    @Transactional(readOnly = true)
    public List<CoachSlotResponse> myBookings(String memberEmail) {
        User member = activeUser(memberEmail, "MEMBER", "Chỉ hội viên đang hoạt động mới xem được lượt đặt của mình");
        return toResponses(slotRepository.findByMember_IdAndStatusInAndEndAtAfterOrderByStartAt(
                member.getId(), List.of("HELD", "BOOKED"), OffsetDateTime.now()));
    }

    // ====================== PAYMENT HOOKS ======================

    // Thu tiền mặt thành công: HELD -> BOOKED
    @Transactional
    public void confirmPaid(Long slotId) {
        CoachSlot slot = lockSlot(slotId);
        if (!"HELD".equals(slot.getStatus())) {
            throw new BusinessRuleException("Không thể xác nhận buổi này (trạng thái hiện tại: " + slot.getStatus() + ")");
        }
        slot.setStatus("BOOKED");
        slot.setHeldUntil(null);
        slotRepository.save(slot);
        notifyCoachBooked(slot);
        bookRequestSiblings(slot);
    }

    // PayOS xác nhận đã nhận tiền. Suất còn giữ chỗ thì chốt. Nếu suất đã hết hạn giữ chỗ mà tiền về muộn (BR-QR-04)
    // thì lấy lại khi suất thay thế còn trống và member không bị trùng lịch. Trả false nếu không kích hoạt được:
    // khi đó khoản tiền phải được hoàn (cũng áp dụng khi coach đã hủy suất)
    @Transactional
    public boolean activatePaidOnline(Long slotId, Long memberId) {
        CoachSlot slot = lockSlot(slotId);
        if (slot.getMember() == null || !slot.getMember().getId().equals(memberId)) {
            return false;
        }
        if ("BOOKED".equals(slot.getStatus())) {
            return true;
        }
        if ("HELD".equals(slot.getStatus())) {
            slot.setStatus("BOOKED");
            slot.setHeldUntil(null);
            slotRepository.save(slot);
            notifyCoachBooked(slot);
            bookRequestSiblings(slot);
            return true;
        }
            // Cả chuỗi đã nhả vì quá hạn: tiền về muộn không kích hoạt lại được, nơi gọi hoàn đủ cho member
        return false;
    }

    // Trả tiền một lần cho cả yêu cầu: các buổi còn lại cùng chuyển sang đã đặt và yêu cầu thành PAID.
    // shortcut: buổi đã bị hủy riêng trước khi trả thì bỏ qua (đơn đã tính theo các buổi còn giữ chỗ lúc tạo), nâng cấp khi cần hoàn riêng
    private void bookRequestSiblings(CoachSlot paid) {
        if (paid.getRequest() == null) {
            return;
        }
        Long requestId = paid.getRequest().getId();
        for (CoachSlot listed : slotRepository.findByRequest_IdInOrderByStartAt(List.of(requestId))) {
            if (listed.getId().equals(paid.getId())) {
                continue;
            }
            CoachSlot sibling = lockSlot(listed.getId());
            if ("HELD".equals(sibling.getStatus())) {
                sibling.setStatus("BOOKED");
                sibling.setHeldUntil(null);
                slotRepository.save(sibling);
                notifyCoachBooked(sibling);
            }
        }
        requestRepository.findById(requestId).ifPresent(request -> {
            request.setStatus("PAID");
            requestRepository.save(request);
        });
    }

    private void notifyCoachBooked(CoachSlot slot) {
        notificationService.send(slot.getCoach().getId(), "BOOKING", "Có lượt đặt 1-1 mới",
                "Có hội viên đặt buổi 1-1 của bạn ngày " + WHEN.format(slot.getStartAt()) + ".");
    }

    // BR-ONE-03: nhả các suất quá hạn giữ chỗ và chưa có thanh toán nào đang xử lý. Gọi từ đợt đối soát 5 phút
    @Transactional
    public int releaseExpiredHolds() {
        OffsetDateTime now = OffsetDateTime.now();
        int released = 0;
        for (Long id : slotRepository.findExpiredHoldIds(now)) {
            CoachSlot slot = slotRepository.findByIdForUpdate(id).orElse(null);
            // Đọc lại sau khi khóa: có thể vừa được thanh toán, hủy, hoặc vừa có QR mới
            if (slot == null || !"HELD".equals(slot.getStatus()) || !slot.getHeldUntil().isBefore(now)
                    || paymentRepository.existsByCoachSlot_IdAndStatusIn(id, IN_FLIGHT_PAYMENT)) {
                continue;
            }
            slot.setStatus("CANCELED");
            slot.setCanceledAt(now);
            slotRepository.saveAndFlush(slot);
            released++;
        }
        return released;
    }

    // ====================== LỚP HỌC THẮNG SUẤT TRỐNG ======================

    // Suất đã có người đặt (HELD/BOOKED) của coach trùng giờ buổi học: không tự hủy được vì đã có tiền của khách.
    // Trả mô tả để báo manager, hoặc null nếu không có
    @Transactional(readOnly = true)
    public String findBookedSlotBlocker(Long coachId, LocalDate date, LocalTime start, LocalTime end) {
        return overlappingSlots(coachId, date, start, end).stream()
                .filter(s -> !"OPEN".equals(s.getStatus()))
                .findFirst()
                .map(s -> "coach đã có buổi 1-1 được đặt lúc " + WHEN.format(s.getStartAt())
                        + " (nhờ coach hủy buổi này, hội viên sẽ được hoàn tiền)")
                .orElse(null);
    }

    // ====================== HELPERS ======================

    private List<CoachSlot> overlappingSlots(Long coachId, LocalDate date, LocalTime start, LocalTime end) {
        int buffer = settings.getScheduleBufferMinutes();
        OffsetDateTime from = date.atTime(start).atZone(SportClassService.CENTER_ZONE).toOffsetDateTime();
        OffsetDateTime to = date.atTime(end).atZone(SportClassService.CENTER_ZONE).toOffsetDateTime();
        return slotRepository.findCoachOverlapping(coachId, from.minusMinutes(buffer), to.plusMinutes(buffer), null);
    }

    // Hoàn đủ khoản đã thu cho suất này (nếu có). Trả số tiền hoàn, hoặc null nếu chưa thu tiền
    private BigDecimal refundIfPaid(CoachSlot slot, String reason) {
        CoachSlotRequest request = slot.getRequest();
        Optional<Payment> paid = request == null
                ? paymentRepository.findFirstByOrder_CoachSlot_IdAndStatusOrderByIdDesc(slot.getId(), "SUCCESS")
                : paymentRepository.findFirstByOrder_CoachSlot_Request_IdAndStatusOrderByIdDesc(request.getId(), "SUCCESS");
        if (paid.isEmpty()) {
            return null;
        }
        if (request == null) {
            return refundService.createRefund(paid.get(), paid.get().getAmount(), reason).getAmount();
        }
        // Trả một lần cho cả chuỗi nên mỗi buổi hoàn phần chia đều (làm tròn xuống đồng), không quá số còn hoàn được
        BigDecimal share = paid.get().getAmount()
                .divide(BigDecimal.valueOf(request.getSessions()), 0, RoundingMode.DOWN)
                .min(refundService.refundable(paid.get()));
        return share.signum() > 0 ? refundService.createPartialRefund(paid.get(), share, reason).getAmount() : null;
    }

    // BR-SCH-01: suất không được trùng buổi học hay suất khác của coach (chồng giờ hoặc cách dưới khoảng nghỉ)
    void checkCoachFree(Long coachId, OffsetDateTime start, OffsetDateTime end) {
        int buffer = settings.getScheduleBufferMinutes();
        if (slotRepository.findCoachSessionConflict(coachId, start, end, buffer).isPresent()) {
            throw new BusinessRuleException("Coach đã có lịch dạy trong khung giờ này (cần cách tối thiểu "
                    + buffer + " phút giữa hai buổi)");
        }
        List<CoachSlot> overlapping = slotRepository.findCoachOverlapping(
                coachId, start.minusMinutes(buffer), end.plusMinutes(buffer), null);
        if (!overlapping.isEmpty()) {
            throw new BusinessRuleException("Khung giờ trùng buổi 1-1 của coach lúc "
                    + WHEN.format(overlapping.get(0).getStartAt()) + " (cần cách tối thiểu " + buffer + " phút giữa hai buổi)");
        }
    }

    // BR-SCH-03: member không được trùng buổi lớp hoặc suất 1-1 khác
    void checkMemberFree(Long memberId, CoachSlot slot) {
        int buffer = settings.getScheduleBufferMinutes();
        slotRepository.findMemberClassConflict(memberId, slot.getStartAt(), slot.getEndAt(), buffer).ifPresent(name -> {
            throw new BusinessRuleException("Trùng lịch với lớp: " + name);
        });
        List<CoachSlot> overlapping = slotRepository.findMemberOverlapping(memberId, slot.getId(),
                slot.getStartAt().minusMinutes(buffer), slot.getEndAt().plusMinutes(buffer));
        if (!overlapping.isEmpty()) {
            throw new BusinessRuleException("Trùng lịch với buổi 1-1 của bạn lúc "
                    + WHEN.format(overlapping.get(0).getStartAt()));
        }
    }

    private boolean memberHasConflict(Long memberId, CoachSlot slot) {
        int buffer = settings.getScheduleBufferMinutes();
        return slotRepository.findMemberClassConflict(memberId, slot.getStartAt(), slot.getEndAt(), buffer).isPresent()
                || !slotRepository.findMemberOverlapping(memberId, slot.getId(),
                        slot.getStartAt().minusMinutes(buffer), slot.getEndAt().plusMinutes(buffer)).isEmpty();
    }

    User activeUser(String email, String roleCode, String message) {
        User user = userRepository.findByEmail(email)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "Không tìm thấy người dùng"));
        if (!Boolean.TRUE.equals(user.getIsActive()) || !roleCode.equals(user.getRole().getCode())) {
            throw new BusinessRuleException(message);
        }
        return user;
    }

    CoachSlot lockSlot(Long slotId) {
        return slotRepository.findByIdForUpdate(slotId)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "Không tìm thấy khung giờ có mã " + slotId));
    }

    // BR-ONE-02: giá một giờ chung cho mọi coach nhân số phút chia 60, làm tròn đến đồng.
    // Đây là giá gốc của suất; ưu đãi tier 10% / 20% (bảng 4.6) được áp lúc tạo thanh toán (PricingService)
    BigDecimal priceFor(long minutes) {
        return BigDecimal.valueOf(settings.getInt("one_on_one.hourly_price"))
                .multiply(BigDecimal.valueOf(minutes))
                .divide(BigDecimal.valueOf(60), 0, RoundingMode.HALF_UP);
    }

    CoachSlotResponse toResponse(CoachSlot slot) {
        return toResponses(List.of(slot)).get(0);
    }

    List<CoachSlotResponse> toResponses(List<CoachSlot> slots) {
        return slots.stream().map(s -> {
            BigDecimal price = s.getPrice() != null
                    ? s.getPrice()
                    : priceFor(Duration.between(s.getStartAt(), s.getEndAt()).toMinutes());
            return new CoachSlotResponse(s, price);
        }).toList();
    }
}
