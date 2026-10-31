package com.swp391.sportcentermanagementsystem_v2.service;

import com.swp391.sportcentermanagementsystem_v2.dto.requestDTO.CreateCoachSlotRequest;
import com.swp391.sportcentermanagementsystem_v2.dto.responseDTO.CoachSlotResponse;
import com.swp391.sportcentermanagementsystem_v2.dto.responseDTO.FacilityRoomResponse;
import com.swp391.sportcentermanagementsystem_v2.entity.CoachProfile;
import com.swp391.sportcentermanagementsystem_v2.entity.CoachSlot;
import com.swp391.sportcentermanagementsystem_v2.entity.FacilityRoom;
import com.swp391.sportcentermanagementsystem_v2.entity.SportCategory;
import com.swp391.sportcentermanagementsystem_v2.entity.Payment;
import com.swp391.sportcentermanagementsystem_v2.entity.TimeSlot;
import com.swp391.sportcentermanagementsystem_v2.entity.User;
import com.swp391.sportcentermanagementsystem_v2.exception.BusinessRuleException;
import com.swp391.sportcentermanagementsystem_v2.repository.ClassSessionRepository;
import com.swp391.sportcentermanagementsystem_v2.repository.CoachProfileRepository;
import com.swp391.sportcentermanagementsystem_v2.repository.CoachSlotRepository;
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
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.stream.Collectors;

// Buổi 1-1 (BR-ONE): coach đăng suất theo slot giờ cố định của trung tâm, member chọn cả suất, giá theo hạng coach và thời lượng
@Slf4j
@Service
@RequiredArgsConstructor
public class CoachSlotService {

    private static final int LOOKAHEAD_DAYS = 90;
    private static final int MAX_PUBLISH_DAYS = 62;
    private static final List<String> ACTIVE_STATUSES = List.of("OPEN", "HELD", "BOOKED");
    private static final List<String> RANKS = List.of("COACH", "SENIOR", "MASTER");
    private static final List<String> IN_FLIGHT_PAYMENT = List.of("CREATING", "PENDING", "SUCCESS");
    private static final DateTimeFormatter WHEN = DateTimeFormatter.ofPattern("dd/MM HH:mm")
            .withZone(SportClassService.CENTER_ZONE);

    private final CoachSlotRepository slotRepository;
    private final UserRepository userRepository;
    private final CoachProfileRepository coachProfileRepository;
    private final PaymentRepository paymentRepository;
    private final OrderRepository orderRepository;
    private final RefundService refundService;
    private final AuditService auditService;
    private final SystemSettingService settings;
    private final CenterHoursService centerHours;
    private final VoucherService voucherService;
    private final NotificationService notificationService;
    private final TimeSlotService timeSlotService;
    private final ClassSessionRepository classSessionRepository;
    private final FacilityRoomRepository facilityRoomRepository;
    private final SportCategoryRepository sportCategoryRepository;

    // ====================== MANAGER (trung tâm quyết định lịch 1-1) ======================

    // Manager mở suất 1-1 hàng loạt cho một coach. Mỗi ngày x slot giờ được kiểm tra riêng (coach dạy môn, giờ mở cửa,
    // không trùng buổi học hay suất khác, phòng đúng môn và còn trống); ô nào không hợp lệ thì bỏ qua và báo lý do
    @Transactional
    public Map<String, Object> publish(String managerEmail, CreateCoachSlotRequest request) {
        User manager = activeUser(managerEmail, "MANAGER", "Only active Managers can schedule 1-1 slots");
        User coach = userRepository.findById(request.getCoachId())
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "Coach not found"));
        if (coach.getRole() == null || !"COACH".equals(coach.getRole().getCode()) || !Boolean.TRUE.equals(coach.getIsActive())) {
            throw new BusinessRuleException("Slots can only be scheduled for an active coach");
        }
        SportCategory sport = sportCategoryRepository.findById(request.getSportCategoryId())
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "Sport category not found"));
        if (!coachTeaches(coach.getId(), sport.getId())) {
            throw new BusinessRuleException(coach.getFullName() + " does not teach " + sport.getName());
        }
        FacilityRoom fixedRoom = request.getRoomId() == null ? null : facilityRoomRepository.findById(request.getRoomId())
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "Room not found"));
        if (request.getToDate().isBefore(request.getFromDate())) {
            throw new BusinessRuleException("The end date must not be before the start date");
        }
        if (ChronoUnit.DAYS.between(request.getFromDate(), request.getToDate()) >= MAX_PUBLISH_DAYS) {
            throw new BusinessRuleException("The date range must not exceed " + MAX_PUBLISH_DAYS + " days");
        }
        List<TimeSlot> timeSlots = request.getTimeSlotIds().stream().map(timeSlotService::requireActive).toList();

        List<CoachSlot> created = new ArrayList<>();
        List<Map<String, Object>> skipped = new ArrayList<>();
        for (LocalDate date = request.getFromDate(); !date.isAfter(request.getToDate()); date = date.plusDays(1)) {
            if (!request.getDaysOfWeek().contains(date.getDayOfWeek().getValue())) {
                continue;
            }
            for (TimeSlot timeSlot : timeSlots) {
                try {
                    created.add(createSlot(coach, sport, fixedRoom, date, timeSlot));
                } catch (BusinessRuleException e) {
                    skipped.add(Map.of("date", date.toString(), "timeSlotId", timeSlot.getId(), "reason", e.getMessage()));
                }
            }
        }
        if (!created.isEmpty()) {
            auditService.log(manager, "COACH_SLOTS_SCHEDULED", "user", coach.getId(), Map.of(
                    "sportCategoryId", sport.getId(), "created", created.size(), "skipped", skipped.size()));
            notificationService.send(coach.getId(), "BOOKING", "New 1-1 sessions scheduled", created.size()
                    + " new " + sport.getName() + " 1-1 session(s) were added to your schedule by the center.");
        }
        return Map.of("created", created.size(), "slots", toResponses(created), "skipped", skipped);
    }

    // Một ô ngày x slot giờ. saveAndFlush để lỗi từ database (hai suất trùng cùng lúc) nổ ngay tại đây
    private CoachSlot createSlot(User coach, SportCategory sport, FacilityRoom fixedRoom, LocalDate date, TimeSlot timeSlot) {
        OffsetDateTime start = TimeSlotService.startAt(timeSlot, date);
        OffsetDateTime end = TimeSlotService.endAt(timeSlot, date);
        if (!start.isAfter(OffsetDateTime.now())) {
            throw new BusinessRuleException("A slot must start in the future");
        }
        centerHours.requireWithin(start, end, "A 1-1 slot");
        checkCoachFree(coach.getId(), start, end);

        FacilityRoom room = fixedRoom;
        if (room == null) {
            room = facilityRoomRepository.findAvailableWithSports().stream()
                    .filter(r -> roomUsable(r, sport.getId(), timeSlot, date, start, end))
                    .findFirst()
                    .orElseThrow(() -> new BusinessRuleException("No free room for " + sport.getName()));
        } else if (!roomUsable(room, sport.getId(), timeSlot, date, start, end)) {
            throw new BusinessRuleException("Room " + room.getRoomName() + " is not available for " + sport.getName());
        }

        CoachSlot slot = new CoachSlot();
        slot.setCoach(coach);
        slot.setCategory(sport);
        slot.setRoom(room);
        slot.setStartAt(start);
        slot.setEndAt(end);
        return slotRepository.saveAndFlush(slot);
    }

    // Danh sách suất 1-1 (mọi coach) cho bảng quản lý; bỏ trống bộ lọc nào thì không lọc theo đó. Mặc định từ hôm nay
    @Transactional(readOnly = true)
    public List<CoachSlotResponse> managerList(OffsetDateTime from, OffsetDateTime to, Long coachId, String status) {
        OffsetDateTime effectiveFrom = from != null ? from : OffsetDateTime.now().minusDays(1);
        OffsetDateTime effectiveTo = to != null ? to : effectiveFrom.plusDays(LOOKAHEAD_DAYS);
        return toResponses(slotRepository.searchForManager(coachId, status, effectiveFrom, effectiveTo));
    }

    // Phòng manager chọn được cho một suất: dạy được môn này, đang sẵn sàng và không trùng buổi học hay suất 1-1 khác
    @Transactional(readOnly = true)
    public List<FacilityRoomResponse> freeRooms(LocalDate date, Long timeSlotId, Long sportCategoryId) {
        TimeSlot timeSlot = timeSlotService.requireActive(timeSlotId);
        OffsetDateTime start = TimeSlotService.startAt(timeSlot, date);
        OffsetDateTime end = TimeSlotService.endAt(timeSlot, date);
        return facilityRoomRepository.findAvailableWithSports().stream()
                .filter(r -> roomUsable(r, sportCategoryId, timeSlot, date, start, end))
                .map(FacilityRoomResponse::new).toList();
    }

    private boolean coachTeaches(Long coachId, Long sportId) {
        return coachProfileRepository.findWithSportsByUserIds(List.of(coachId)).stream()
                .anyMatch(p -> p.getSports().stream().anyMatch(sp -> sp.getId().equals(sportId)));
    }

    // Phòng dùng được cho môn, đang AVAILABLE và không bị buổi học hay suất khác chiếm (kể cả khoảng nghỉ)
    private boolean roomUsable(FacilityRoom room, Long sportId, TimeSlot timeSlot, LocalDate date,
                               OffsetDateTime start, OffsetDateTime end) {
        if (!"AVAILABLE".equalsIgnoreCase(room.getMaintenanceStatus())
                || room.getSports().stream().noneMatch(sp -> sp.getId().equals(sportId))) {
            return false;
        }
        int buffer = settings.getInt("schedule.buffer_minutes");
        return !slotRepository.existsRoomOverlapping(room.getId(), start.minusMinutes(buffer), end.plusMinutes(buffer))
                && !classSessionRepository.existsRoomConflict(room.getId(), date,
                        timeSlot.getStartTime().minusMinutes(buffer), timeSlot.getEndTime().plusMinutes(buffer), null, null);
    }

    // ====================== COACH (chỉ xem) ======================

    @Transactional(readOnly = true)
    public List<CoachSlotResponse> mySlots(String coachEmail) {
        User coach = activeUser(coachEmail, "COACH", "Only active Coaches can view their slots");
        return toResponses(slotRepository.findByCoach_IdAndStatusInAndEndAtAfterOrderByStartAt(
                coach.getId(), ACTIVE_STATUSES, OffsetDateTime.now()));
    }

    // Manager chỉ xóa được suất chưa có người đặt
    @Transactional
    public void deleteSlot(Long slotId) {
        CoachSlot slot = lockSlot(slotId);
        if (!"OPEN".equals(slot.getStatus())) {
            throw new BusinessRuleException("A slot that is already booked cannot be deleted, cancel it instead");
        }
        slotRepository.delete(slot);
    }

    // BR-ONE-05: trung tâm hủy buổi đã đặt (coach bận, phòng hỏng): member được hoàn đủ kèm voucher đền bù, coach được báo.
    // Suất không mở lại
    @Transactional
    public CoachSlotResponse managerCancel(String managerEmail, Long slotId, String reason) {
        User manager = activeUser(managerEmail, "MANAGER", "Only active Managers can cancel a booked session");
        CoachSlot slot = lockSlot(slotId);
        if (!List.of("HELD", "BOOKED").contains(slot.getStatus())) {
            throw new BusinessRuleException("Only a held or booked session can be canceled (status: " + slot.getStatus() + ")");
        }
        OffsetDateTime now = OffsetDateTime.now();
        if (!slot.getStartAt().isAfter(now)) {
            throw new BusinessRuleException("The session has already started");
        }

        // Nếu member đang trả dở, tiền về sau sẽ không kích hoạt được (suất đã hủy) và được hoàn tự động
        BigDecimal refunded = refundIfPaid(slot, "1-1 session canceled by the center: " + reason);

        slot.setStatus("CANCELED");
        slot.setCanceledBy(manager);
        slot.setCanceledAt(now);
        slotRepository.saveAndFlush(slot);

        if ("ONE_ON_ONE".equals(slot.getKind())) {
            voucherService.issueCompensation(slot.getMember().getId());
        }
        auditService.log(manager, "COACH_SLOT_CANCELED", "coach_slot", slotId, Map.of(
                "reason", reason,
                "memberId", slot.getMember().getId(),
                "refundAmount", refunded == null ? 0 : refunded));
        notificationService.send(slot.getMember().getId(), "BOOKING", "Session canceled by the center",
                "Your session on " + WHEN.format(slot.getStartAt()) + " was canceled by the center (" + reason + ").");
        notificationService.send(slot.getCoach().getId(), "BOOKING", "Session canceled by the center",
                "Your 1-1 session on " + WHEN.format(slot.getStartAt()) + " was canceled by the center (" + reason + ").");

        CoachSlotResponse response = toResponse(slot);
        response.setRefundAmount(refunded);
        return response;
    }

    // BR-MKP-05: coach nghỉ việc hẳn. Với mỗi suất 1-1 sắp tới của coach: suất trống thì đóng; suất đã đặt thì tìm coach
    // khác rảnh đúng giờ để thay (cùng hạng trước, rồi hạng cao hơn, cuối cùng hạng thấp hơn và hoàn chênh lệch giá),
    // không có ai thay được thì hủy và hoàn đủ. Suất đang diễn ra không đổi giữa buổi nên hủy và hoàn đủ.
    // Như khi coach tự hủy, suất bị hủy không mở lại
    @Transactional
    public Map<String, Object> cancelAllForResignedCoach(Long coachId, String managerEmail, String reason) {
        User manager = userRepository.findByEmail(managerEmail)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "User not found"));
        User coach = userRepository.findById(coachId)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "Coach not found with ID: " + coachId));
        if (coach.getRole() == null || !"COACH".equals(coach.getRole().getCode())) {
            throw new BusinessRuleException("This user is not a coach");
        }

        List<User> candidates = userRepository.findByRole_CodeAndIsActiveTrue("COACH").stream()
                .filter(c -> !c.getId().equals(coachId)).toList();
        Map<Long, String> ranks = coachProfileRepository.findAllById(candidates.stream().map(User::getId).toList()).stream()
                .collect(Collectors.toMap(CoachProfile::getUserId, CoachProfile::getCoachRank));
        String oldRank = rankOf(coachId);

        OffsetDateTime now = OffsetDateTime.now();
        int closed = 0;
        int reassigned = 0;
        int canceled = 0;
        BigDecimal refundTotal = BigDecimal.ZERO;
        for (CoachSlot listed : slotRepository.findByCoach_IdAndStatusInAndEndAtAfterOrderByStartAt(
                coachId, ACTIVE_STATUSES, now)) {
            // Khóa rồi đọc lại: suất có thể vừa được đặt, thanh toán hoặc hủy
            CoachSlot slot = lockSlot(listed.getId());
            if (!ACTIVE_STATUSES.contains(slot.getStatus())) {
                continue;
            }
            boolean booked = !"OPEN".equals(slot.getStatus());
            Replacement replacement = booked && slot.getStartAt().isAfter(now)
                    ? findReplacement(slot, oldRank, candidates, ranks) : null;

            if (replacement != null) {
                refundTotal = refundTotal.add(reassign(slot, replacement, oldRank, reason, manager).refunded());
                reassigned++;
                continue;
            }
            if (booked) {
                BigDecimal refunded = refundIfPaid(slot, "1-1 session canceled, the coach has left the center and no "
                        + "replacement coach is available: " + reason);
                if (refunded != null) {
                    refundTotal = refundTotal.add(refunded);
                }
                canceled++;
                if ("ONE_ON_ONE".equals(slot.getKind())) {
                    voucherService.issueCompensation(slot.getMember().getId());
                }
                notificationService.send(slot.getMember().getId(), "BOOKING", "Session canceled",
                        "Your session on " + WHEN.format(slot.getStartAt())
                                + " was canceled and refunded because the coach has left the center.");
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
                "canceledBookings", canceled, "refundTotal", refundTotal));
        return Map.of("coachId", coachId, "closedOpenSlots", closed, "reassignedBookings", reassigned,
                "canceledBookings", canceled, "refundTotal", refundTotal);
    }

    private record Replacement(User coach, String rank) {
    }

    // Coach thay thế: đang hoạt động, rảnh đúng giờ suất. Ưu tiên cùng hạng, rồi hạng cao hơn (gần nhất trước), cuối cùng hạng
    // thấp hơn. Suất chưa thanh toán (HELD) không đổi xuống hạng thấp vì chưa có khoản nào để hoàn chênh lệch
    private Replacement findReplacement(CoachSlot slot, String oldRank, List<User> candidates, Map<Long, String> ranks) {
        int oldIndex = RANKS.indexOf(oldRank);
        boolean anyRank = "BOOKED".equals(slot.getStatus());
        return candidates.stream()
                .map(c -> new Replacement(c, ranks.getOrDefault(c.getId(), "COACH")))
                .filter(r -> slot.getCategory() == null || coachTeaches(r.coach().getId(), slot.getCategory().getId()))
                .filter(r -> anyRank || RANKS.indexOf(r.rank()) >= oldIndex)
                .sorted(Comparator.comparingInt((Replacement r) -> rankDistance(RANKS.indexOf(r.rank()), oldIndex))
                        .thenComparing(r -> r.coach().getId()))
                .filter(r -> isCoachFree(r.coach().getId(), slot.getStartAt(), slot.getEndAt()))
                .findFirst().orElse(null);
    }

    private int rankDistance(int index, int oldIndex) {
        return index >= oldIndex ? index - oldIndex : 10 + (oldIndex - index);
    }

    boolean isCoachFree(Long coachId, OffsetDateTime start, OffsetDateTime end) {
        int buffer = settings.getInt("schedule.buffer_minutes");
        return slotRepository.findCoachSessionConflict(coachId, start, end, buffer).isEmpty()
                && slotRepository.findCoachOverlapping(coachId, start.minusMinutes(buffer), end.plusMinutes(buffer), null).isEmpty();
    }

    // Database cấm đổi coach trên dòng suất đã có người đặt (BR-ONE-01), nên lượt đặt được chuyển sang một dòng suất mới của
    // coach thay thế (cùng giờ, cùng người đặt, cùng trạng thái), đơn và thanh toán đi theo dòng mới, còn dòng cũ bị hủy
    // và không mở lại. Giữ giá đã chụp; riêng đổi xuống hạng thấp thì giá giảm theo hạng mới và phần chênh lệch được hoàn.
    // Member đã được giảm giá (tier, voucher) thì số phải trả ở hạng mới giữ nguyên tỷ lệ giảm đó: hoàn = số đã trả x (1 - giá mới / giá cũ),
    // làm tròn về đồng. Đơn của suất cũng được tính lại theo tỷ lệ này
    // Trả số tiền hoàn chênh lệch (0 nếu không có)
    private Reassigned reassign(CoachSlot slot, Replacement replacement, String oldRank, String reason, User manager) {
        long minutes = Duration.between(slot.getStartAt(), slot.getEndAt()).toMinutes();
        BigDecimal paidPrice = slot.getPrice() != null ? slot.getPrice() : priceFor(oldRank, minutes);
        BigDecimal newPrice = priceFor(replacement.rank(), minutes);
        BigDecimal movedPrice = paidPrice;
        BigDecimal refunded = BigDecimal.ZERO;

        if ("BOOKED".equals(slot.getStatus()) && newPrice.compareTo(paidPrice) < 0) {
            Optional<Payment> paid = paymentRepository.findFirstByOrder_CoachSlot_IdAndStatusOrderByIdDesc(slot.getId(), "SUCCESS");
            if (paid.isPresent()) {
                BigDecimal payable = scaledByPrice(paid.get().getAmount(), newPrice, paidPrice);
                BigDecimal amount = paid.get().getAmount().subtract(payable).min(refundService.refundable(paid.get()));
                if (amount.signum() > 0) {
                    refunded = refundService.createPartialRefund(paid.get(), amount,
                            "1-1 session moved to a lower-rank coach: " + reason).getAmount();
                }
            }
            movedPrice = newPrice;
        }

        // Hủy dòng cũ trước: phòng và coach chỉ được giữ bởi một dòng chưa hủy tại một thời điểm
        String status = slot.getStatus();
        User previous = slot.getCoach();
        slot.setStatus("CANCELED");
        slot.setCanceledBy(manager);
        slot.setCanceledAt(OffsetDateTime.now());
        slotRepository.saveAndFlush(slot);

        CoachSlot moved = new CoachSlot();
        moved.setCoach(replacement.coach());
        moved.setRoom(slot.getRoom());
        moved.setCategory(slot.getCategory());
        moved.setStartAt(slot.getStartAt());
        moved.setEndAt(slot.getEndAt());
        moved.setKind(slot.getKind());
        moved.setStatus(status);
        moved.setMember(slot.getMember());
        moved.setHeldUntil(slot.getHeldUntil());
        moved.setPrice(movedPrice);
        slotRepository.saveAndFlush(moved);

        boolean cheaper = movedPrice != null && movedPrice.compareTo(paidPrice) < 0;
        orderRepository.findByCoachSlot_Id(slot.getId()).ifPresent(order -> {
            order.setCoachSlot(moved);
            if (cheaper) {
                BigDecimal payable = scaledByPrice(order.getTotalAmount(), newPrice, paidPrice);
                order.setListPrice(newPrice);
                order.setDiscountAmount(newPrice.subtract(payable));
                order.setTotalAmount(payable);
            }
            orderRepository.saveAndFlush(order);
        });

        auditService.log(manager, "COACH_SLOT_REASSIGNED", "coach_slot", moved.getId(), Map.of(
                "fromSlotId", slot.getId(), "fromCoachId", previous.getId(), "toCoachId", replacement.coach().getId(),
                "differenceRefund", refunded));
        notificationService.send(slot.getMember().getId(), "BOOKING", "Session moved to another coach",
                "Your session on " + WHEN.format(slot.getStartAt()) + " moved to coach "
                        + replacement.coach().getFullName() + " because the previous coach is unavailable.");
        return new Reassigned(moved, refunded);
    }

    private record Reassigned(CoachSlot moved, BigDecimal refunded) {
    }

    // amount x newPrice / oldPrice, làm tròn HALF_UP về đồng (cùng quy tắc làm tròn với PricingService)
    private static BigDecimal scaledByPrice(BigDecimal amount, BigDecimal newPrice, BigDecimal oldPrice) {
        return amount.multiply(newPrice).divide(oldPrice, 0, RoundingMode.HALF_UP);
    }

    // BR-ONE-02: manager gán hạng coach. Giá của các lần đặt sau theo hạng mới, suất đã đặt giữ giá cũ
    @Transactional
    public Map<String, Object> setRank(String managerEmail, Long coachId, String rank) {
        User manager = userRepository.findByEmail(managerEmail)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "User not found"));
        CoachProfile profile = coachProfileRepository.findById(coachId)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "Coach not found with ID: " + coachId));

        String previous = profile.getCoachRank();
        profile.setCoachRank(rank);
        coachProfileRepository.save(profile);

        auditService.log(manager, "COACH_RANK_CHANGED", "coach_profile", coachId, Map.of("from", previous, "to", rank));
        return Map.of("coachId", coachId, "rank", rank);
    }

    // ====================== MEMBER ======================

    // Xem công khai: suất 1-1 còn trống ở tương lai, mặc định trong 90 ngày tới
    @Transactional(readOnly = true)
    public List<CoachSlotResponse> openSlots(Long coachId, OffsetDateTime from, OffsetDateTime to) {
        OffsetDateTime now = OffsetDateTime.now();
        OffsetDateTime effectiveFrom = (from == null || from.isBefore(now)) ? now : from;
        OffsetDateTime effectiveTo = to == null ? effectiveFrom.plusDays(LOOKAHEAD_DAYS) : to;
        if (!effectiveTo.isAfter(effectiveFrom)) {
            throw new BusinessRuleException("The end of the range must be after its start");
        }
        return toResponses(slotRepository.searchOpen(coachId, effectiveFrom, effectiveTo));
    }

    // BR-ONE-03: member đặt suất, giữ chỗ trong lúc thanh toán. Câu cập nhật có điều kiện bảo đảm chỉ một người đặt được
    @Transactional
    public CoachSlotResponse book(String memberEmail, Long slotId) {
        User member = activeUser(memberEmail, "MEMBER", "Only active Members can book a 1-1 session");
        CoachSlot slot = slotRepository.findWithDetailsById(slotId)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "Slot not found with ID: " + slotId));

        if (!"ONE_ON_ONE".equals(slot.getKind())) {
            throw new BusinessRuleException("This slot is not a 1-1 session");
        }
        if (!"OPEN".equals(slot.getStatus())) {
            throw new BusinessRuleException("This slot has just been taken");
        }
        OffsetDateTime now = OffsetDateTime.now();
        if (!slot.getStartAt().isAfter(now)) {
            throw new BusinessRuleException("This slot has already started");
        }
        checkMemberFree(member.getId(), slot);

        long minutes = Duration.between(slot.getStartAt(), slot.getEndAt()).toMinutes();
        BigDecimal price = priceFor(rankOf(slot.getCoach().getId()), minutes);
        OffsetDateTime heldUntil = now.plusMinutes(settings.getInt("payment.hold_minutes"));

        if (slotRepository.hold(slotId, member, heldUntil, price, now) == 0) {
            throw new BusinessRuleException("This slot has just been taken");
        }
        return toResponse(slotRepository.findWithDetailsById(slotId).orElseThrow());
    }

    @Transactional(readOnly = true)
    public List<CoachSlotResponse> myBookings(String memberEmail) {
        User member = activeUser(memberEmail, "MEMBER", "Only active Members can view their bookings");
        return toResponses(slotRepository.findByMember_IdAndStatusInAndEndAtAfterOrderByStartAt(
                member.getId(), List.of("HELD", "BOOKED"), OffsetDateTime.now()));
    }

    // Lễ tân xem các buổi 1-1 sắp tới của một hội viên để thu tiền mặt hoặc hủy giúp
    @Transactional(readOnly = true)
    public List<CoachSlotResponse> memberBookings(Long memberId) {
        return toResponses(slotRepository.findByMember_IdAndStatusInAndEndAtAfterOrderByStartAt(
                memberId, List.of("HELD", "BOOKED"), OffsetDateTime.now()));
    }

    // BR-ONE-04: member (hoặc lễ tân hủy hộ) hủy. Còn từ N giờ trở lên trước giờ học thì hoàn đủ, muộn hơn không hoàn.
    // Dù thế nào suất cũng được mở lại cho người khác, coach không phải làm gì
    @Transactional
    public CoachSlotResponse cancelBooking(String actorEmail, Long slotId) {
        User actor = userRepository.findByEmail(actorEmail)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "User not found"));
        boolean staff = !"MEMBER".equals(actor.getRole().getCode());
        CoachSlot slot = lockSlot(slotId);

        if (!staff && (slot.getMember() == null || !slot.getMember().getId().equals(actor.getId()))) {
            throw new ResponseStatusException(HttpStatus.FORBIDDEN, "You can only cancel your own bookings");
        }
        if (!List.of("HELD", "BOOKED").contains(slot.getStatus())) {
            throw new BusinessRuleException("Only a held or booked session can be canceled (status: " + slot.getStatus() + ")");
        }
        OffsetDateTime now = OffsetDateTime.now();
        if (!slot.getStartAt().isAfter(now)) {
            throw new BusinessRuleException("The session has already started");
        }

        BigDecimal refunded = null;
        if ("HELD".equals(slot.getStatus())) {
            // Còn thanh toán chưa ngã ngũ thì chưa hủy được (OnlinePaymentService hỏi PayOS trước khi vào đây)
            if (paymentRepository.existsByCoachSlot_IdAndStatusIn(slotId, IN_FLIGHT_PAYMENT)) {
                throw new BusinessRuleException("A payment is still being processed. Cancel the QR on the payment page "
                        + "or wait for it to expire (up to 15 minutes), then cancel again");
            }
        } else {
            long refundableMinutes = settings.getInt("one_on_one.refund_before_hours") * 60L;
            if (Duration.between(now, slot.getStartAt()).toMinutes() >= refundableMinutes) {
                refunded = refundIfPaid(slot, "1-1 session canceled by "
                        + (staff ? "the receptionist" : "the member") + " before the refund deadline");
            }
        }

        slot.setStatus("CANCELED");
        slot.setCanceledBy(actor);
        slot.setCanceledAt(now);
        slotRepository.saveAndFlush(slot);
        reopen(slot);

        CoachSlotResponse response = toResponse(slot);
        response.setRefundAmount(refunded);
        return response;
    }

    // ====================== PAYMENT HOOKS ======================

    // Thu tiền mặt thành công: HELD -> BOOKED
    @Transactional
    public void confirmPaid(Long slotId) {
        CoachSlot slot = lockSlot(slotId);
        if (!"HELD".equals(slot.getStatus())) {
            throw new BusinessRuleException("This session cannot be confirmed (status: " + slot.getStatus() + ")");
        }
        slot.setStatus("BOOKED");
        slot.setHeldUntil(null);
        slotRepository.save(slot);
        notifyCoachBooked(slot);
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
            return true;
        }
        OffsetDateTime now = OffsetDateTime.now();
        // Chỉ lấy lại suất bị hệ thống thu hồi do hết hạn giữ chỗ (canceled_by rỗng), không lấy lại suất coach hay member đã hủy
        if (!"CANCELED".equals(slot.getStatus()) || slot.getCanceledBy() != null || !slot.getStartAt().isAfter(now)) {
            return false;
        }
        Optional<CoachSlot> found = slotRepository.findFirstByCoach_IdAndStartAtAndEndAtAndKindAndStatus(
                slot.getCoach().getId(), slot.getStartAt(), slot.getEndAt(), slot.getKind(), "OPEN");
        if (found.isEmpty() || memberHasConflict(memberId, slot)) {
            return false;
        }
        CoachSlot replacement = slotRepository.findByIdForUpdate(found.get().getId()).orElse(null);
        if (replacement == null || !"OPEN".equals(replacement.getStatus())) {
            return false;
        }

        replacement.setStatus("CANCELED");
        replacement.setCanceledAt(now);
        slotRepository.saveAndFlush(replacement);
        slot.setStatus("BOOKED");
        slot.setCanceledAt(null);
        slot.setHeldUntil(null);
        slotRepository.save(slot);
        notifyCoachBooked(slot);
        return true;
    }

    private void notifyCoachBooked(CoachSlot slot) {
        notificationService.send(slot.getCoach().getId(), "BOOKING", "New 1-1 booking",
                "A member booked your 1-1 session on " + WHEN.format(slot.getStartAt()) + ".");
    }

    // PayOS báo khách đã hủy QR: nhả suất ngay, không chờ hết hạn giữ chỗ. Bỏ qua nếu suất không còn HELD
    // hoặc còn thanh toán chưa ngã ngũ (vd. QR mới)
    @Transactional
    public void releaseHeld(Long slotId) {
        CoachSlot slot = slotRepository.findByIdForUpdate(slotId).orElse(null);
        if (slot == null || !"HELD".equals(slot.getStatus())
                || paymentRepository.existsByCoachSlot_IdAndStatusIn(slotId, IN_FLIGHT_PAYMENT)) {
            return;
        }
        slot.setStatus("CANCELED");
        slot.setCanceledAt(OffsetDateTime.now());
        slotRepository.saveAndFlush(slot);
        reopen(slot);
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
            reopen(slot);
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
                .map(s -> "the coach already has a booked 1-1 session at " + WHEN.format(s.getStartAt())
                        + " (ask the coach to cancel it, the member is refunded)")
                .orElse(null);
    }

    @Transactional(readOnly = true)
    public List<Long> findOpenSlotIds(Long coachId, LocalDate date, LocalTime start, LocalTime end) {
        return overlappingSlots(coachId, date, start, end).stream()
                .filter(s -> "OPEN".equals(s.getStatus()))
                .map(CoachSlot::getId)
                .toList();
    }

    // Cách A: lớp học được ưu tiên, các suất còn trống bị hủy (đánh dấu CANCELED, giữ lịch sử), coach được báo và có nhật ký
    @Transactional
    public void cancelOpenSlotsForClass(Collection<Long> slotIds, Long coachId, User manager, Long classId) {
        if (slotIds.isEmpty()) {
            return;
        }
        slotRepository.cancelOpen(slotIds, manager, OffsetDateTime.now());
        auditService.log(manager, "COACH_SLOTS_REMOVED_FOR_CLASS", "sport_class", classId,
                Map.of("coachId", coachId, "slotIds", slotIds));
        notificationService.send(coachId, "BOOKING", "Open slots removed", slotIds.size()
                + " of your open slot(s) were removed because a class was scheduled at that time.");
    }

    // ====================== HELPERS ======================

    private List<CoachSlot> overlappingSlots(Long coachId, LocalDate date, LocalTime start, LocalTime end) {
        int buffer = settings.getInt("schedule.buffer_minutes");
        OffsetDateTime from = date.atTime(start).atZone(SportClassService.CENTER_ZONE).toOffsetDateTime();
        OffsetDateTime to = date.atTime(end).atZone(SportClassService.CENTER_ZONE).toOffsetDateTime();
        return slotRepository.findCoachOverlapping(coachId, from.minusMinutes(buffer), to.plusMinutes(buffer), null);
    }

    // Suất đã đặt bị hủy thì tạo một dòng OPEN mới cùng giờ. Dòng cũ phải đã ghi CANCELED xuống DB trước
    // (ràng buộc chống trùng của coach bỏ qua dòng CANCELED)
    void reopen(CoachSlot old) {
        CoachSlot open = new CoachSlot();
        open.setCoach(old.getCoach());
        open.setStartAt(old.getStartAt());
        open.setEndAt(old.getEndAt());
        open.setKind(old.getKind());
        open.setRoom(old.getRoom());
        open.setCategory(old.getCategory());
        slotRepository.save(open);
    }

    // Hoàn đủ khoản đã thu cho suất này (nếu có). Trả số tiền hoàn, hoặc null nếu chưa thu tiền
    private BigDecimal refundIfPaid(CoachSlot slot, String reason) {
        Optional<Payment> paid = paymentRepository.findFirstByOrder_CoachSlot_IdAndStatusOrderByIdDesc(slot.getId(), "SUCCESS");
        if (paid.isEmpty()) {
            return null;
        }
        return refundService.createRefund(paid.get(), paid.get().getAmount(), reason).getAmount();
    }

    // BR-SCH-01: suất không được trùng buổi học hay suất khác của coach (chồng giờ hoặc cách dưới khoảng nghỉ)
    void checkCoachFree(Long coachId, OffsetDateTime start, OffsetDateTime end) {
        int buffer = settings.getInt("schedule.buffer_minutes");
        slotRepository.findCoachSessionConflict(coachId, start, end, buffer).ifPresent(session -> {
            throw new BusinessRuleException("The slot overlaps the coach's class session " + session
                    + " (a " + buffer + "-minute gap is required)");
        });
        List<CoachSlot> overlapping = slotRepository.findCoachOverlapping(
                coachId, start.minusMinutes(buffer), end.plusMinutes(buffer), null);
        if (!overlapping.isEmpty()) {
            throw new BusinessRuleException("The slot overlaps the coach's 1-1 slot at "
                    + WHEN.format(overlapping.get(0).getStartAt()) + " (a " + buffer + "-minute gap is required)");
        }
    }

    // BR-SCH-03: member không được trùng buổi lớp hoặc suất 1-1 khác
    void checkMemberFree(Long memberId, CoachSlot slot) {
        int buffer = settings.getInt("schedule.buffer_minutes");
        slotRepository.findMemberClassConflict(memberId, slot.getStartAt(), slot.getEndAt(), buffer).ifPresent(name -> {
            throw new BusinessRuleException("Schedule conflicts with class: " + name);
        });
        List<CoachSlot> overlapping = slotRepository.findMemberOverlapping(memberId, slot.getId(),
                slot.getStartAt().minusMinutes(buffer), slot.getEndAt().plusMinutes(buffer));
        if (!overlapping.isEmpty()) {
            throw new BusinessRuleException("Schedule conflicts with your 1-1 session at "
                    + WHEN.format(overlapping.get(0).getStartAt()));
        }
    }

    private boolean memberHasConflict(Long memberId, CoachSlot slot) {
        int buffer = settings.getInt("schedule.buffer_minutes");
        return slotRepository.findMemberClassConflict(memberId, slot.getStartAt(), slot.getEndAt(), buffer).isPresent()
                || !slotRepository.findMemberOverlapping(memberId, slot.getId(),
                        slot.getStartAt().minusMinutes(buffer), slot.getEndAt().plusMinutes(buffer)).isEmpty();
    }

    User activeUser(String email, String roleCode, String message) {
        User user = userRepository.findByEmail(email)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "User not found"));
        if (!Boolean.TRUE.equals(user.getIsActive()) || !roleCode.equals(user.getRole().getCode())) {
            throw new BusinessRuleException(message);
        }
        return user;
    }

    CoachSlot lockSlot(Long slotId) {
        return slotRepository.findByIdForUpdate(slotId)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "Slot not found with ID: " + slotId));
    }

    private String rankOf(Long coachId) {
        return coachProfileRepository.findById(coachId).map(CoachProfile::getCoachRank).orElse("COACH");
    }

    // BR-ONE-02: giá một giờ theo hạng nhân số phút chia 60, làm tròn đến đồng.
    // Đây là giá gốc của suất; ưu đãi tier 10% / 20% (bảng 4.6) và voucher được áp lúc tạo thanh toán (PricingService)
    private BigDecimal priceFor(String rank, long minutes) {
        return BigDecimal.valueOf(settings.getIntField("coach.rank_prices", rank))
                .multiply(BigDecimal.valueOf(minutes))
                .divide(BigDecimal.valueOf(60), 0, RoundingMode.HALF_UP);
    }

    CoachSlotResponse toResponse(CoachSlot slot) {
        return toResponses(List.of(slot)).get(0);
    }

    // Một query lấy hạng của mọi coach trong danh sách, tránh N+1
    private List<CoachSlotResponse> toResponses(List<CoachSlot> slots) {
        Map<Long, String> ranks = coachProfileRepository
                .findAllById(slots.stream().map(s -> s.getCoach().getId()).distinct().toList()).stream()
                .collect(Collectors.toMap(CoachProfile::getUserId, CoachProfile::getCoachRank));
        return slots.stream().map(s -> {
            String rank = ranks.getOrDefault(s.getCoach().getId(), "COACH");
            BigDecimal price = s.getPrice() != null
                    ? s.getPrice()
                    : priceFor(rank, Duration.between(s.getStartAt(), s.getEndAt()).toMinutes());
            return new CoachSlotResponse(s, rank, price);
        }).toList();
    }
}
