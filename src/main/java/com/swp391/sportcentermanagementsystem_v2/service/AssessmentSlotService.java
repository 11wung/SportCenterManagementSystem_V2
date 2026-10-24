package com.swp391.sportcentermanagementsystem_v2.service;

import com.swp391.sportcentermanagementsystem_v2.dto.requestDTO.BulkAssessmentSlotsRequest;
import com.swp391.sportcentermanagementsystem_v2.dto.responseDTO.AssessmentCapacityResponse;
import com.swp391.sportcentermanagementsystem_v2.dto.responseDTO.AssessmentSlotResponse;
import com.swp391.sportcentermanagementsystem_v2.entity.CoachSlot;
import com.swp391.sportcentermanagementsystem_v2.entity.User;
import com.swp391.sportcentermanagementsystem_v2.exception.BusinessRuleException;
import com.swp391.sportcentermanagementsystem_v2.repository.CoachSlotRepository;
import com.swp391.sportcentermanagementsystem_v2.repository.UserRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;

import java.math.RoundingMode;
import java.time.Duration;
import java.time.LocalDate;
import java.time.LocalTime;
import java.time.OffsetDateTime;
import java.time.ZonedDateTime;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

// BR-BEN-03: đánh giá thể lực. Manager tạo khung và gán coach (giờ nằm trong giờ mở cửa), member của gói có quyền lợi
// đặt miễn phí khi còn lượt trong kỳ, coach được gán sẽ đo. Khung dùng chung bảng coach_slots (kind = ASSESSMENT)
@Slf4j
@Service
@RequiredArgsConstructor
public class AssessmentSlotService {

    private static final int MAX_DAYS_PER_BATCH = 28;
    private static final int MAX_SLOTS_PER_BATCH = 200;
    private static final int MAX_REPORTED = 10;
    private static final int MAX_CAPACITY_DAYS = 90;
    private static final int DEFAULT_SLOT_MINUTES = 60;

    private final CoachSlotRepository slotRepository;
    private final UserRepository userRepository;
    private final CoachSlotService coachSlotService;
    private final CenterHoursService centerHours;
    private final AuditService auditService;

    // ====================== MANAGER ======================

    @Transactional
    public AssessmentSlotResponse createSlot(String managerEmail, Long coachId, OffsetDateTime start, OffsetDateTime end) {
        User manager = findUser(managerEmail);
        User coach = lockActiveCoach(coachId);
        checkSlotShape(start, end);
        coachSlotService.checkCoachFree(coachId, start, end);

        CoachSlot slot = newSlot(coach, start, end);
        // saveAndFlush để lỗi từ database (tạo cùng lúc hai khung trùng) nổ ngay trong hàm này
        CoachSlot saved = slotRepository.saveAndFlush(slot);
        auditService.log(manager, "ASSESSMENT_SLOT_CREATED", "coach_slot", saved.getId(), Map.of("coachId", coachId));
        // shortcut: chỉ ghi log, thay bằng NotificationService của Flow 1 khi có (BR-NTF-01)
        log.info("NOTIFY user {}: an assessment slot was assigned to you on {}", coachId, start);
        return new AssessmentSlotResponse(saved);
    }

    // Tạo nhiều khung cho một coach. Làm hết hoặc không làm gì: có khung nào vi phạm thì không tạo khung nào và báo rõ
    @Transactional
    public Map<String, Object> createBulk(String managerEmail, BulkAssessmentSlotsRequest request) {
        User manager = findUser(managerEmail);
        LocalDate today = LocalDate.now(SportClassService.CENTER_ZONE);
        if (request.getFrom().isBefore(today)) {
            throw new BusinessRuleException("The start date cannot be in the past");
        }
        if (request.getTo().isBefore(request.getFrom())) {
            throw new BusinessRuleException("The end date must not be before the start date");
        }
        if (ChronoUnit.DAYS.between(request.getFrom(), request.getTo()) + 1 > MAX_DAYS_PER_BATCH) {
            throw new BusinessRuleException("The date range must not exceed " + MAX_DAYS_PER_BATCH + " days (4 weeks)");
        }
        if (!request.getWindowEnd().isAfter(request.getWindowStart())) {
            throw new BusinessRuleException("The window end must be after the window start");
        }
        int slotMinutes = request.getSlotMinutes() == null ? DEFAULT_SLOT_MINUTES : request.getSlotMinutes();
        checkMinutes(slotMinutes);

        User coach = lockActiveCoach(request.getCoachId());
        List<CoachSlot> slots = new ArrayList<>();
        List<String> problems = new ArrayList<>();
        OffsetDateTime now = OffsetDateTime.now();
        for (LocalDate date = request.getFrom(); !date.isAfter(request.getTo()); date = date.plusDays(1)) {
            if (!request.getDaysOfWeek().contains(date.getDayOfWeek().getValue())) {
                continue;
            }
            for (LocalTime t = request.getWindowStart();
                    !t.plusMinutes(slotMinutes).isAfter(request.getWindowEnd()) && !t.plusMinutes(slotMinutes).isBefore(t);
                    t = t.plusMinutes(slotMinutes + ClassScheduleService.GAP_MINUTES)) {
                OffsetDateTime start = ZonedDateTime.of(date, t, SportClassService.CENTER_ZONE).toOffsetDateTime();
                OffsetDateTime end = start.plusMinutes(slotMinutes);
                if (!start.isAfter(now)) {
                    continue; // hôm nay, giờ đã qua
                }
                try {
                    centerHours.requireWithin(start, end, "An assessment slot");
                    coachSlotService.checkCoachFree(coach.getId(), start, end);
                    slots.add(newSlot(coach, start, end));
                } catch (BusinessRuleException ex) {
                    problems.add(date + " " + t + ": " + ex.getMessage());
                }
                if (t.plusMinutes(slotMinutes + ClassScheduleService.GAP_MINUTES).isBefore(t)) {
                    break; // vòng qua nửa đêm
                }
            }
        }

        if (!problems.isEmpty()) {
            throw new BusinessRuleException("No slot was created because some slots are not possible: "
                    + String.join("; ", problems.stream().limit(MAX_REPORTED).toList())
                    + (problems.size() > MAX_REPORTED ? " (and " + (problems.size() - MAX_REPORTED) + " more)" : ""));
        }
        if (slots.isEmpty()) {
            throw new BusinessRuleException("No slot fits the chosen days, window and dates");
        }
        if (slots.size() > MAX_SLOTS_PER_BATCH) {
            throw new BusinessRuleException("At most " + MAX_SLOTS_PER_BATCH + " slots can be created at once (this would create "
                    + slots.size() + ")");
        }

        slotRepository.saveAllAndFlush(slots);
        auditService.log(manager, "ASSESSMENT_SLOTS_CREATED", "user", coach.getId(), Map.of(
                "count", slots.size(), "from", request.getFrom().toString(), "to", request.getTo().toString()));
        // shortcut: chỉ ghi log, thay bằng NotificationService của Flow 1 khi có (BR-NTF-01)
        log.info("NOTIFY user {}: {} assessment slots were assigned to you", coach.getId(), slots.size());
        return Map.of("coachId", coach.getId(), "created", slots.size(),
                "firstSlot", slots.get(0).getStartAt(), "lastSlot", slots.get(slots.size() - 1).getStartAt());
    }

    // Thu bớt khi dư: xóa các khung còn trống trong khoảng ngày (có thể chỉ của một coach). Khung đã có người đặt giữ nguyên
    @Transactional
    public Map<String, Object> deleteOpen(String managerEmail, LocalDate from, LocalDate to, Long coachId) {
        User manager = findUser(managerEmail);
        if (to.isBefore(from)) {
            throw new BusinessRuleException("The end date must not be before the start date");
        }
        OffsetDateTime start = from.atStartOfDay(SportClassService.CENTER_ZONE).toOffsetDateTime();
        OffsetDateTime end = to.plusDays(1).atStartOfDay(SportClassService.CENTER_ZONE).toOffsetDateTime();
        int deleted = slotRepository.deleteOpenAssessments(start, end, coachId);
        auditService.log(manager, "ASSESSMENT_SLOTS_DELETED", "coach_slot", null, Map.of(
                "from", from.toString(), "to", to.toString(), "deleted", deleted));
        return Map.of("deleted", deleted);
    }

    @Transactional(readOnly = true)
    public AssessmentCapacityResponse capacity(int days) {
        if (days < 1 || days > MAX_CAPACITY_DAYS) {
            throw new BusinessRuleException("Days must be between 1 and " + MAX_CAPACITY_DAYS);
        }
        OffsetDateTime from = OffsetDateTime.now();
        OffsetDateTime to = from.plusDays(days);
        long open = slotRepository.countByKindAndStatusAndStartAtGreaterThanEqualAndStartAtLessThan("ASSESSMENT", "OPEN", from, to);
        long booked = slotRepository.countByKindAndStatusAndStartAtGreaterThanEqualAndStartAtLessThan("ASSESSMENT", "BOOKED", from, to);
        long demand = slotRepository.estimateAssessmentDemand(days).setScale(0, RoundingMode.CEILING).longValue();
        return new AssessmentCapacityResponse(from, to, open, booked, slotRepository.countAssessmentEntitledMembers(),
                demand, Math.max(0, demand - booked - open));
    }

    // ====================== MEMBER ======================

    @Transactional(readOnly = true)
    public List<AssessmentSlotResponse> openSlots(OffsetDateTime from, OffsetDateTime to) {
        OffsetDateTime now = OffsetDateTime.now();
        OffsetDateTime effectiveFrom = (from == null || from.isBefore(now)) ? now : from;
        OffsetDateTime effectiveTo = to == null ? effectiveFrom.plusDays(30) : to;
        if (!effectiveTo.isAfter(effectiveFrom)) {
            throw new BusinessRuleException("The end of the range must be after its start");
        }
        return slotRepository.searchOpenAssessments(effectiveFrom, effectiveTo).stream()
                .map(AssessmentSlotResponse::new).toList();
    }

    // Đặt khung miễn phí khi gói có quyền lợi và còn lượt trong kỳ (mỗi kỳ N tháng tính từ ngày bắt đầu gói, một lượt mỗi
    // kỳ, lượt không dùng thì mất). Khóa dòng member để hai yêu cầu đặt cùng lúc không cùng qua kiểm tra lượt
    @Transactional
    public AssessmentSlotResponse book(String memberEmail, Long slotId) {
        User member = coachSlotService.activeUser(memberEmail, "MEMBER", "Only active Members can book an assessment");
        userRepository.findByIdForUpdate(member.getId())
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "Member not found"));
        CoachSlot slot = slotRepository.findWithDetailsById(slotId)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "Slot not found with ID: " + slotId));

        if (!"ASSESSMENT".equals(slot.getKind())) {
            throw new BusinessRuleException("This slot is not an assessment slot");
        }
        OffsetDateTime now = OffsetDateTime.now();
        if (!"OPEN".equals(slot.getStatus()) || !slot.getStartAt().isAfter(now)) {
            throw new BusinessRuleException("This slot is no longer available");
        }

        LocalDate date = slot.getStartAt().atZoneSameInstant(SportClassService.CENTER_ZONE).toLocalDate();
        List<Object[]> entitlement = slotRepository.findAssessmentEntitlement(member.getId(), date);
        if (entitlement.isEmpty()) {
            throw new BusinessRuleException("Your package does not include fitness assessments on this date");
        }
        int everyMonths = ((Number) entitlement.get(0)[0]).intValue();
        LocalDate subscriptionStart = ((java.sql.Date) entitlement.get(0)[1]).toLocalDate();
        long periods = ChronoUnit.MONTHS.between(subscriptionStart, date) / everyMonths;
        LocalDate periodStart = subscriptionStart.plusMonths(periods * everyMonths);
        LocalDate periodEnd = subscriptionStart.plusMonths((periods + 1) * everyMonths);
        if (slotRepository.countMemberAssessments(member.getId(),
                periodStart.atStartOfDay(SportClassService.CENTER_ZONE).toOffsetDateTime(),
                periodEnd.atStartOfDay(SportClassService.CENTER_ZONE).toOffsetDateTime()) >= 1) {
            throw new BusinessRuleException("You have already used your assessment for the period "
                    + periodStart + " to " + periodEnd.minusDays(1));
        }

        coachSlotService.checkMemberFree(member.getId(), slot);
        if (slotRepository.bookAssessment(slotId, member, now) == 0) {
            throw new BusinessRuleException("This slot has just been taken");
        }
        return new AssessmentSlotResponse(slotRepository.findWithDetailsById(slotId).orElseThrow());
    }

    @Transactional(readOnly = true)
    public List<AssessmentSlotResponse> myAssessments(String memberEmail) {
        User member = coachSlotService.activeUser(memberEmail, "MEMBER", "Only active Members can view their assessments");
        return slotRepository.findByMember_IdAndStatusInAndEndAtAfterOrderByStartAt(
                        member.getId(), List.of("BOOKED"), OffsetDateTime.now()).stream()
                .filter(s -> "ASSESSMENT".equals(s.getKind()))
                .map(AssessmentSlotResponse::new).toList();
    }

    // Hủy trước giờ hẹn: khung được mở lại cho người khác, lượt tự được trả lại vì chỉ đếm khung còn đặt
    @Transactional
    public AssessmentSlotResponse cancel(String memberEmail, Long slotId) {
        User member = coachSlotService.activeUser(memberEmail, "MEMBER", "Only active Members can cancel an assessment");
        CoachSlot slot = coachSlotService.lockSlot(slotId);
        if (!"ASSESSMENT".equals(slot.getKind()) || slot.getMember() == null
                || !slot.getMember().getId().equals(member.getId())) {
            throw new ResponseStatusException(HttpStatus.FORBIDDEN, "You can only cancel your own assessment");
        }
        OffsetDateTime now = OffsetDateTime.now();
        if (!"BOOKED".equals(slot.getStatus()) || !slot.getStartAt().isAfter(now)) {
            throw new BusinessRuleException("Only an upcoming booked assessment can be canceled");
        }
        slot.setStatus("CANCELED");
        slot.setCanceledBy(member);
        slot.setCanceledAt(now);
        slotRepository.saveAndFlush(slot);
        coachSlotService.reopen(slot);
        return new AssessmentSlotResponse(slot);
    }

    // ====================== HELPERS ======================

    private void checkSlotShape(OffsetDateTime start, OffsetDateTime end) {
        if (!start.isAfter(OffsetDateTime.now())) {
            throw new BusinessRuleException("A slot must start in the future");
        }
        checkMinutes(Duration.between(start, end).toMinutes());
        centerHours.requireWithin(start, end, "An assessment slot");
    }

    private void checkMinutes(long minutes) {
        if (minutes < CoachSlotService.MIN_MINUTES || minutes > CoachSlotService.MAX_MINUTES
                || minutes % CoachSlotService.STEP_MINUTES != 0) {
            throw new BusinessRuleException("A slot must last " + CoachSlotService.MIN_MINUTES + " to "
                    + CoachSlotService.MAX_MINUTES + " minutes, in steps of " + CoachSlotService.STEP_MINUTES + " minutes");
        }
    }

    private CoachSlot newSlot(User coach, OffsetDateTime start, OffsetDateTime end) {
        CoachSlot slot = new CoachSlot();
        slot.setCoach(coach);
        slot.setStartAt(start);
        slot.setEndAt(end);
        slot.setKind("ASSESSMENT");
        return slot;
    }

    // Khóa dòng coach để hai lần tạo khung cùng lúc cho một coach chạy lần lượt
    private User lockActiveCoach(Long coachId) {
        User coach = userRepository.findByIdForUpdate(coachId)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "Coach not found with ID: " + coachId));
        if (coach.getRole() == null || !"COACH".equals(coach.getRole().getCode())
                || !Boolean.TRUE.equals(coach.getIsActive())) {
            throw new BusinessRuleException("The assigned user must be an active coach");
        }
        return coach;
    }

    private User findUser(String email) {
        return userRepository.findByEmail(email)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "User not found"));
    }
}
