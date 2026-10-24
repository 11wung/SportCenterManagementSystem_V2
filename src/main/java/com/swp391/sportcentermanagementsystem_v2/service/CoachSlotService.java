package com.swp391.sportcentermanagementsystem_v2.service;

import com.swp391.sportcentermanagementsystem_v2.dto.requestDTO.CreateCoachSlotRequest;
import com.swp391.sportcentermanagementsystem_v2.dto.responseDTO.CoachSlotResponse;
import com.swp391.sportcentermanagementsystem_v2.entity.CoachProfile;
import com.swp391.sportcentermanagementsystem_v2.entity.CoachSlot;
import com.swp391.sportcentermanagementsystem_v2.entity.Payment;
import com.swp391.sportcentermanagementsystem_v2.entity.User;
import com.swp391.sportcentermanagementsystem_v2.exception.BusinessRuleException;
import com.swp391.sportcentermanagementsystem_v2.repository.CoachProfileRepository;
import com.swp391.sportcentermanagementsystem_v2.repository.CoachSlotRepository;
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
import java.util.Collection;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.stream.Collectors;

// Buổi 1-1 (BR-ONE): coach đăng suất cố định 60 đến 180 phút, member chọn cả suất, giá theo hạng coach và thời lượng
@Slf4j
@Service
@RequiredArgsConstructor
public class CoachSlotService {

    private static final int MIN_MINUTES = 60;
    private static final int MAX_MINUTES = 180;
    private static final int STEP_MINUTES = 30;
    private static final int LOOKAHEAD_DAYS = 90;
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

    // ====================== COACH ======================

    // BR-ONE-01: coach công bố suất rảnh. Không được trùng buổi học hoặc suất khác của chính coach (BR-SCH-01)
    @Transactional
    public CoachSlotResponse publish(String coachEmail, CreateCoachSlotRequest request) {
        User coach = activeUser(coachEmail, "COACH", "Only active Coaches can publish 1-1 slots");
        OffsetDateTime start = request.getStartAt();
        OffsetDateTime end = request.getEndAt();

        if (!start.isAfter(OffsetDateTime.now())) {
            throw new BusinessRuleException("A slot must start in the future");
        }
        long minutes = Duration.between(start, end).toMinutes();
        if (minutes < MIN_MINUTES || minutes > MAX_MINUTES || minutes % STEP_MINUTES != 0) {
            throw new BusinessRuleException("A slot must last " + MIN_MINUTES + " to " + MAX_MINUTES
                    + " minutes, in steps of " + STEP_MINUTES + " minutes");
        }
        centerHours.requireWithin(start, end, "A 1-1 slot");
        checkCoachFree(coach.getId(), start, end);

        CoachSlot slot = new CoachSlot();
        slot.setCoach(coach);
        slot.setStartAt(start);
        slot.setEndAt(end);
        // saveAndFlush để lỗi từ database (đăng cùng lúc hai suất trùng) nổ ngay trong hàm này
        return toResponse(slotRepository.saveAndFlush(slot));
    }

    @Transactional(readOnly = true)
    public List<CoachSlotResponse> mySlots(String coachEmail) {
        User coach = activeUser(coachEmail, "COACH", "Only active Coaches can view their slots");
        return toResponses(slotRepository.findByCoach_IdAndStatusInAndEndAtAfterOrderByStartAt(
                coach.getId(), ACTIVE_STATUSES, OffsetDateTime.now()));
    }

    // BR-ONE-01: chỉ xóa được suất chưa có người đặt
    @Transactional
    public void deleteSlot(String coachEmail, Long slotId) {
        User coach = activeUser(coachEmail, "COACH", "Only active Coaches can delete their slots");
        CoachSlot slot = lockSlot(slotId);
        requireCoachOwner(slot, coach);
        if (!"OPEN".equals(slot.getStatus())) {
            throw new BusinessRuleException("A slot that is already booked cannot be deleted");
        }
        slotRepository.delete(slot);
    }

    // BR-ONE-05: coach bận hủy buổi đã đặt: member được hoàn đủ, ghi nhật ký. Suất không mở lại (coach không dạy được)
    @Transactional
    public CoachSlotResponse coachCancel(String coachEmail, Long slotId, String reason) {
        User coach = activeUser(coachEmail, "COACH", "Only active Coaches can cancel their sessions");
        CoachSlot slot = lockSlot(slotId);
        requireCoachOwner(slot, coach);
        if (!List.of("HELD", "BOOKED").contains(slot.getStatus())) {
            throw new BusinessRuleException("Only a held or booked session can be canceled (status: " + slot.getStatus() + ")");
        }
        OffsetDateTime now = OffsetDateTime.now();
        if (!slot.getStartAt().isAfter(now)) {
            throw new BusinessRuleException("The session has already started");
        }

        // Nếu member đang trả dở, tiền về sau sẽ không kích hoạt được (suất đã hủy bởi coach) và được hoàn tự động
        BigDecimal refunded = refundIfPaid(slot, "1-1 session canceled by the coach: " + reason);

        slot.setStatus("CANCELED");
        slot.setCanceledBy(coach);
        slot.setCanceledAt(now);
        slotRepository.saveAndFlush(slot);

        voucherService.issueCompensation(slot.getMember().getId());
        auditService.log(coach, "COACH_SLOT_CANCELED", "coach_slot", slotId, Map.of(
                "reason", reason,
                "memberId", slot.getMember().getId(),
                "refundAmount", refunded == null ? 0 : refunded));
        // shortcut: chỉ ghi log, thay bằng NotificationService và voucher bù lỗi (BR-VCH-02) của Flow 1 khi có
        log.info("NOTIFY user {}: your 1-1 session on {} was canceled by the coach ({})",
                slot.getMember().getId(), WHEN.format(slot.getStartAt()), reason);

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
                refundTotal = refundTotal.add(reassign(slot, replacement, oldRank, reason, manager));
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
                voucherService.issueCompensation(slot.getMember().getId());
                // shortcut: chỉ ghi log, thay bằng NotificationService của Flow 1 khi có (BR-NTF-01)
                log.info("NOTIFY user {}: your 1-1 session on {} was canceled and refunded, the coach has left the center",
                        slot.getMember().getId(), WHEN.format(slot.getStartAt()));
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
        boolean paid = "BOOKED".equals(slot.getStatus());
        return candidates.stream()
                .map(c -> new Replacement(c, ranks.getOrDefault(c.getId(), "COACH")))
                .filter(r -> paid || RANKS.indexOf(r.rank()) >= oldIndex)
                .sorted(Comparator.comparingInt((Replacement r) -> rankDistance(RANKS.indexOf(r.rank()), oldIndex))
                        .thenComparing(r -> r.coach().getId()))
                .filter(r -> isCoachFree(r.coach().getId(), slot.getStartAt(), slot.getEndAt()))
                .findFirst().orElse(null);
    }

    private int rankDistance(int index, int oldIndex) {
        return index >= oldIndex ? index - oldIndex : 10 + (oldIndex - index);
    }

    private boolean isCoachFree(Long coachId, OffsetDateTime start, OffsetDateTime end) {
        int buffer = settings.getInt("schedule.buffer_minutes");
        return slotRepository.findCoachSessionConflict(coachId, start, end, buffer).isEmpty()
                && slotRepository.findCoachOverlapping(coachId, start.minusMinutes(buffer), end.plusMinutes(buffer), null).isEmpty();
    }

    // Database cấm đổi coach trên dòng suất đã có người đặt (BR-ONE-01), nên lượt đặt được chuyển sang một dòng suất mới của
    // coach thay thế (cùng giờ, cùng người đặt, cùng trạng thái), đơn và thanh toán đi theo dòng mới, còn dòng cũ bị hủy
    // và không mở lại. Giữ giá đã chụp; riêng đổi xuống hạng thấp thì giá giảm theo hạng mới và phần chênh lệch được hoàn.
    // Trả số tiền hoàn chênh lệch (0 nếu không có)
    private BigDecimal reassign(CoachSlot slot, Replacement replacement, String oldRank, String reason, User manager) {
        long minutes = Duration.between(slot.getStartAt(), slot.getEndAt()).toMinutes();
        BigDecimal paidPrice = slot.getPrice() != null ? slot.getPrice() : priceFor(oldRank, minutes);
        BigDecimal newPrice = priceFor(replacement.rank(), minutes);
        BigDecimal movedPrice = paidPrice;
        BigDecimal refunded = BigDecimal.ZERO;

        if ("BOOKED".equals(slot.getStatus()) && newPrice.compareTo(paidPrice) < 0) {
            Optional<Payment> paid = paymentRepository.findFirstByOrder_CoachSlot_IdAndStatusOrderByIdDesc(slot.getId(), "SUCCESS");
            if (paid.isPresent()) {
                BigDecimal amount = paidPrice.subtract(newPrice).min(refundService.refundable(paid.get()));
                if (amount.signum() > 0) {
                    refunded = refundService.createPartialRefund(paid.get(), amount,
                            "1-1 session moved to a lower-rank coach: " + reason).getAmount();
                }
            }
            movedPrice = newPrice;
        }

        CoachSlot moved = new CoachSlot();
        moved.setCoach(replacement.coach());
        moved.setStartAt(slot.getStartAt());
        moved.setEndAt(slot.getEndAt());
        moved.setKind(slot.getKind());
        moved.setStatus(slot.getStatus());
        moved.setMember(slot.getMember());
        moved.setHeldUntil(slot.getHeldUntil());
        moved.setPrice(movedPrice);
        slotRepository.saveAndFlush(moved);

        orderRepository.findByCoachSlot_Id(slot.getId()).ifPresent(order -> {
            order.setCoachSlot(moved);
            orderRepository.saveAndFlush(order);
        });

        User previous = slot.getCoach();
        slot.setStatus("CANCELED");
        slot.setCanceledBy(manager);
        slot.setCanceledAt(OffsetDateTime.now());
        slotRepository.saveAndFlush(slot);

        auditService.log(manager, "COACH_SLOT_REASSIGNED", "coach_slot", moved.getId(), Map.of(
                "fromSlotId", slot.getId(), "fromCoachId", previous.getId(), "toCoachId", replacement.coach().getId(),
                "differenceRefund", refunded));
        // shortcut: chỉ ghi log, thay bằng NotificationService của Flow 1 khi có (BR-NTF-01)
        log.info("NOTIFY user {}: your 1-1 session on {} moved to coach {} because the previous coach left the center",
                slot.getMember().getId(), WHEN.format(slot.getStartAt()), replacement.coach().getFullName());
        return refunded;
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
        return true;
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
        // shortcut: chỉ ghi log, thay bằng NotificationService của Flow 1 khi có (BR-NTF-01)
        log.info("NOTIFY coach {}: {} of your open 1-1 slot(s) were removed because a class was scheduled at that time",
                coachId, slotIds.size());
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
    private void reopen(CoachSlot old) {
        CoachSlot open = new CoachSlot();
        open.setCoach(old.getCoach());
        open.setStartAt(old.getStartAt());
        open.setEndAt(old.getEndAt());
        open.setKind(old.getKind());
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
    private void checkCoachFree(Long coachId, OffsetDateTime start, OffsetDateTime end) {
        int buffer = settings.getInt("schedule.buffer_minutes");
        slotRepository.findCoachSessionConflict(coachId, start, end, buffer).ifPresent(session -> {
            throw new BusinessRuleException("The slot overlaps your class session " + session
                    + " (a " + buffer + "-minute gap is required)");
        });
        List<CoachSlot> overlapping = slotRepository.findCoachOverlapping(
                coachId, start.minusMinutes(buffer), end.plusMinutes(buffer), null);
        if (!overlapping.isEmpty()) {
            throw new BusinessRuleException("The slot overlaps your 1-1 slot at "
                    + WHEN.format(overlapping.get(0).getStartAt()) + " (a " + buffer + "-minute gap is required)");
        }
    }

    // BR-SCH-03: member không được trùng buổi lớp hoặc suất 1-1 khác
    private void checkMemberFree(Long memberId, CoachSlot slot) {
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

    private User activeUser(String email, String roleCode, String message) {
        User user = userRepository.findByEmail(email)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "User not found"));
        if (!Boolean.TRUE.equals(user.getIsActive()) || !roleCode.equals(user.getRole().getCode())) {
            throw new BusinessRuleException(message);
        }
        return user;
    }

    private CoachSlot lockSlot(Long slotId) {
        return slotRepository.findByIdForUpdate(slotId)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "Slot not found with ID: " + slotId));
    }

    private void requireCoachOwner(CoachSlot slot, User coach) {
        if (!slot.getCoach().getId().equals(coach.getId())) {
            throw new ResponseStatusException(HttpStatus.FORBIDDEN, "This slot belongs to another coach");
        }
    }

    private String rankOf(Long coachId) {
        return coachProfileRepository.findById(coachId).map(CoachProfile::getCoachRank).orElse("COACH");
    }

    // BR-ONE-02: giá một giờ theo hạng nhân số phút chia 60, làm tròn đến đồng.
    // shortcut: ưu đãi tier 10% / 20% cho buổi 1-1 (bảng 4.6) chờ Flow 1, hiện chưa giảm
    private BigDecimal priceFor(String rank, long minutes) {
        return BigDecimal.valueOf(settings.getIntField("coach.rank_prices", rank))
                .multiply(BigDecimal.valueOf(minutes))
                .divide(BigDecimal.valueOf(60), 0, RoundingMode.HALF_UP);
    }

    private CoachSlotResponse toResponse(CoachSlot slot) {
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
