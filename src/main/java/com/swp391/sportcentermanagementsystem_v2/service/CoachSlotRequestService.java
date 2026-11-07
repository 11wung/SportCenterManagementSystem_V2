package com.swp391.sportcentermanagementsystem_v2.service;

import com.swp391.sportcentermanagementsystem_v2.dto.requestDTO.SubmitSlotRequest;
import com.swp391.sportcentermanagementsystem_v2.dto.responseDTO.CoachOptionResponse;
import com.swp391.sportcentermanagementsystem_v2.dto.responseDTO.CoachSlotRequestResponse;
import com.swp391.sportcentermanagementsystem_v2.dto.responseDTO.CoachSlotResponse;
import com.swp391.sportcentermanagementsystem_v2.dto.responseDTO.SportCategoryResponse;
import com.swp391.sportcentermanagementsystem_v2.entity.CoachProfile;
import com.swp391.sportcentermanagementsystem_v2.entity.CoachSlot;
import com.swp391.sportcentermanagementsystem_v2.entity.CoachSlotRequest;
import com.swp391.sportcentermanagementsystem_v2.entity.CoachSlotRequestDay;
import com.swp391.sportcentermanagementsystem_v2.entity.FacilityRoom;
import com.swp391.sportcentermanagementsystem_v2.entity.SportCategory;
import com.swp391.sportcentermanagementsystem_v2.entity.TimeSlot;
import com.swp391.sportcentermanagementsystem_v2.entity.User;
import com.swp391.sportcentermanagementsystem_v2.exception.BusinessRuleException;
import com.swp391.sportcentermanagementsystem_v2.repository.CoachProfileRepository;
import com.swp391.sportcentermanagementsystem_v2.repository.CoachSlotRepository;
import com.swp391.sportcentermanagementsystem_v2.repository.CoachSlotRequestDayRepository;
import com.swp391.sportcentermanagementsystem_v2.repository.CoachSlotRequestRepository;
import com.swp391.sportcentermanagementsystem_v2.repository.FacilityRoomRepository;
import com.swp391.sportcentermanagementsystem_v2.repository.PaymentRepository;
import com.swp391.sportcentermanagementsystem_v2.repository.SportCategoryRepository;
import com.swp391.sportcentermanagementsystem_v2.repository.UserRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;
import java.util.stream.Collectors;

// Yêu cầu buổi 1-1 nhiều buổi: member xin N buổi vào các thứ đã chọn trong tuần, mỗi thứ một khung giờ, manager duyệt cả chuỗi hoặc từ chối.
// Duyệt xong các suất được tạo và giữ chỗ cho member; trả tiền một lần cho cả chuỗi
@Slf4j
@Service
@RequiredArgsConstructor
public class CoachSlotRequestService {

    private static final List<String> LIVE_SLOT_STATUSES = List.of("HELD", "BOOKED");
    private static final List<String> IN_FLIGHT_PAYMENT = List.of("CREATING", "PENDING", "SUCCESS");
    private static final DateTimeFormatter WHEN = DateTimeFormatter.ofPattern("dd/MM HH:mm")
            .withZone(SportClassService.CENTER_ZONE);

    private final CoachSlotRequestRepository requestRepository;
    private final CoachSlotRequestDayRepository dayRepository;
    private final CoachSlotRepository slotRepository;
    private final CoachSlotService coachSlotService;
    private final UserRepository userRepository;
    private final SportCategoryRepository sportCategoryRepository;
    private final FacilityRoomRepository facilityRoomRepository;
    private final TimeSlotService timeSlotService;
    private final SystemSettingService settings;
    private final NotificationService notificationService;
    private final AuditService auditService;
    private final PricingService pricingService;
    private final PaymentRepository paymentRepository;
    private final CoachProfileRepository coachProfileRepository;

    // Một buổi của yêu cầu: ngày và khung giờ của ngày đó
    record PlannedSession(LocalDate date, TimeSlot slot) {}

    // Các buổi của yêu cầu: lấy `sessions` ngày đầu tiên từ `from` trở đi có thứ nằm trong slotByWeekday (1 = thứ 2 đến 7 = chủ nhật),
    // mỗi ngày dùng khung giờ của thứ đó. slotByWeekday phải khác rỗng, nếu không vòng lặp không dừng
    static List<PlannedSession> sessionPlan(LocalDate from, Map<Integer, TimeSlot> slotByWeekday, int sessions) {
        List<PlannedSession> plan = new ArrayList<>();
        for (LocalDate d = from; plan.size() < sessions; d = d.plusDays(1)) {
            TimeSlot slot = slotByWeekday.get(d.getDayOfWeek().getValue());
            if (slot != null) {
                plan.add(new PlannedSession(d, slot));
            }
        }
        return plan;
    }

    // Thứ và khung giờ của một yêu cầu; yêu cầu cũ chưa có dòng nào thì đúng thứ của firstDate mỗi tuần với giờ của yêu cầu
    private static Map<Integer, TimeSlot> slotByWeekday(CoachSlotRequest request, List<CoachSlotRequestDay> days) {
        Map<Integer, TimeSlot> map = new TreeMap<>();
        if (days.isEmpty()) {
            map.put(request.getFirstDate().getDayOfWeek().getValue(), request.getTimeSlot());
        } else {
            days.forEach(d -> map.put((int) d.getWeekday(), d.getTimeSlot()));
        }
        return map;
    }

    // ====================== MEMBER ======================

    @Transactional
    public CoachSlotRequestResponse submit(String memberEmail, SubmitSlotRequest in) {
        User member = coachSlotService.activeUser(memberEmail, "MEMBER", "Chỉ hội viên đang hoạt động mới gửi được yêu cầu 1-1");
        if (member.getEmailVerifiedAt() == null) {
            throw new BusinessRuleException("Hội viên chưa xác thực email, hãy xác thực trước khi đặt buổi 1-1");
        }
        User coach = userRepository.findById(in.getCoachId())
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "Không tìm thấy coach"));
        if (coach.getRole() == null || !"COACH".equals(coach.getRole().getCode()) || !Boolean.TRUE.equals(coach.getIsActive())) {
            throw new BusinessRuleException("Hãy chọn coach đang hoạt động");
        }
        SportCategory sport = sportCategoryRepository.findById(in.getSportCategoryId())
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "Không tìm thấy môn thể thao"));
        if (!coachSlotService.coachTeaches(coach.getId(), sport.getId())) {
            throw new BusinessRuleException(coach.getFullName() + " không dạy môn " + sport.getName());
        }
        // Mỗi thứ một khung giờ. Không gửi days thì giữ cách cũ: đúng thứ của firstDate với timeSlotId
        Map<Integer, TimeSlot> byWeekday = new TreeMap<>();
        if (in.getDays() == null || in.getDays().isEmpty()) {
            if (in.getTimeSlotId() == null) {
                throw new BusinessRuleException("Hãy chọn khung giờ");
            }
            byWeekday.put(in.getFirstDate().getDayOfWeek().getValue(), timeSlotService.requireActive(in.getTimeSlotId()));
        } else {
            for (SubmitSlotRequest.Day day : in.getDays()) {
                if (byWeekday.put(day.getWeekday(), timeSlotService.requireActive(day.getTimeSlotId())) != null) {
                    throw new BusinessRuleException("Mỗi thứ chỉ được chọn một lần");
                }
            }
        }

        int max = settings.getInt("one_on_one.max_sessions_per_request");
        if (in.getSessions() > max) {
            throw new BusinessRuleException("Một yêu cầu có tối đa " + max + " buổi");
        }
        int leadHours = settings.getInt("one_on_one.request_min_lead_hours");
        OffsetDateTime now = OffsetDateTime.now();
        PlannedSession first = sessionPlan(in.getFirstDate(), byWeekday, 1).get(0);
        if (TimeSlotService.startAt(first.slot(), first.date()).isBefore(now.plusHours(leadHours))) {
            throw new BusinessRuleException("Buổi đầu tiên phải bắt đầu sau ít nhất " + leadHours + " giờ kể từ bây giờ");
        }

        CoachSlotRequest request = new CoachSlotRequest();
        request.setMember(member);
        request.setCoach(coach);
        request.setCategory(sport);
        request.setTimeSlot(first.slot());
        request.setFirstDate(first.date());
        request.setSessions(in.getSessions().shortValue());
        request.setExpiresAt(now.plusHours(settings.getInt("one_on_one.request_expire_hours")));
        requestRepository.save(request);
        List<CoachSlotRequestDay> days = byWeekday.entrySet().stream()
                .map(e -> new CoachSlotRequestDay(request, e.getKey(), e.getValue())).toList();
        dayRepository.saveAll(days);

        for (User manager : userRepository.findByRole_CodeAndIsActiveTrue("MANAGER")) {
            notificationService.send(manager.getId(), "BOOKING", "Yêu cầu 1-1 mới", member.getFullName() + " đã gửi yêu cầu "
                    + request.getSessions() + " buổi 1-1 với " + coach.getFullName() + ".");
        }
        return toResponse(request, List.of(), days);
    }

    // Coach đang hoạt động và các môn họ dạy, để member chọn khi gửi yêu cầu
    @Transactional(readOnly = true)
    public List<CoachOptionResponse> coachOptions() {
        List<User> coaches = userRepository.findByRole_CodeAndIsActiveTrue("COACH");
        Map<Long, CoachProfile> profiles = coachProfileRepository
                .findWithSportsByUserIds(coaches.stream().map(User::getId).toList()).stream()
                .collect(Collectors.toMap(CoachProfile::getUserId, p -> p));
        return coaches.stream()
                .filter(c -> profiles.containsKey(c.getId()) && !profiles.get(c.getId()).getSports().isEmpty())
                .map(c -> new CoachOptionResponse(c.getId(), c.getFullName(),
                        profiles.get(c.getId()).getSports().stream().map(SportCategoryResponse::new).toList()))
                .toList();
    }

    // Lễ tân xem yêu cầu của một hội viên để thu tiền mặt
    @Transactional(readOnly = true)
    public List<CoachSlotRequestResponse> requestsOf(Long memberId) {
        return toResponses(requestRepository.findByMember_IdOrderByIdDesc(memberId));
    }

    @Transactional(readOnly = true)
    public List<CoachSlotRequestResponse> myRequests(String memberEmail) {
        User member = coachSlotService.activeUser(memberEmail, "MEMBER", "Chỉ hội viên đang hoạt động mới xem được yêu cầu của mình");
        return toResponses(requestRepository.findByMember_IdOrderByIdDesc(member.getId()));
    }

    // Member hủy yêu cầu chưa được duyệt, hoặc đã duyệt nhưng chưa trả tiền (các suất giữ chỗ được nhả)
    @Transactional
    public CoachSlotRequestResponse cancel(String memberEmail, Long id) {
        User member = coachSlotService.activeUser(memberEmail, "MEMBER", "Chỉ hội viên đang hoạt động mới hủy được yêu cầu của mình");
        CoachSlotRequest request = lock(id);
        if (!request.getMember().getId().equals(member.getId())) {
            throw new ResponseStatusException(HttpStatus.FORBIDDEN, "Bạn chỉ được hủy yêu cầu của chính mình");
        }
        if ("APPROVED".equals(request.getStatus())) {
            if (paymentRepository.existsByCoachSlot_IdAndStatusIn(anchorSlotId(id), IN_FLIGHT_PAYMENT)) {
                throw new BusinessRuleException("Đang có một giao dịch được xử lý. Hãy hủy mã QR ở trang thanh toán "
                        + "hoặc chờ hết hạn rồi hủy lại yêu cầu");
            }
            releaseHeldSlots(request, member);
        } else if (!"PENDING".equals(request.getStatus())) {
            throw new BusinessRuleException("Chỉ hủy được yêu cầu đang chờ duyệt hoặc chưa thanh toán (trạng thái hiện tại: " + request.getStatus() + ")");
        }
        request.setStatus("CANCELED");
        requestRepository.save(request);
        return toResponse(request, slotsOf(request));
    }

    // Trả tiền một lần cho cả chuỗi dùng lại luồng thanh toán của suất lẻ, đặt ở buổi sớm nhất còn sống của yêu cầu
    // (đơn ở buổi đó có tổng tiền của cả chuỗi). Chỉ trả được khi yêu cầu đã duyệt hoặc đã trả
    @Transactional(readOnly = true)
    public Long anchorSlotId(Long requestId) {
        CoachSlotRequest request = requestRepository.findById(requestId)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "Không tìm thấy yêu cầu có mã " + requestId));
        if (!List.of("APPROVED", "PAID").contains(request.getStatus())) {
            throw new BusinessRuleException("Yêu cầu không ở trạng thái chờ thanh toán (trạng thái hiện tại: " + request.getStatus() + ")");
        }
        return slotRepository.findByRequest_IdInOrderByStartAt(List.of(requestId)).stream()
                .filter(s -> !"CANCELED".equals(s.getStatus()))
                .map(CoachSlot::getId)
                .findFirst()
                .orElseThrow(() -> new BusinessRuleException("Yêu cầu không còn buổi nào cần thanh toán"));
    }

    // ====================== MANAGER ======================

    @Transactional(readOnly = true)
    public List<CoachSlotRequestResponse> managerList(String status) {
        return toResponses(requestRepository.search(status));
    }

    // Duyệt cả chuỗi hoặc không buổi nào: một buổi không hợp lệ (coach bận, hết phòng, member trùng lịch...) thì cả yêu cầu
    // không được duyệt, mọi suất đã tạo trong lần này bị hoàn tác, và manager thấy buổi nào vướng để từ chối kèm lý do
    @Transactional
    public CoachSlotRequestResponse approve(String managerEmail, Long id, Long roomId) {
        User manager = coachSlotService.activeUser(managerEmail, "MANAGER", "Chỉ quản lý đang hoạt động mới duyệt được yêu cầu");
        CoachSlotRequest request = lock(id);
        OffsetDateTime now = OffsetDateTime.now();
        requirePending(request, now);

        FacilityRoom fixedRoom = roomId == null ? null : facilityRoomRepository.findById(roomId)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "Không tìm thấy phòng"));
        User member = request.getMember();
        OffsetDateTime payUntil = now.plusHours(settings.getInt("one_on_one.request_pay_hours"));

        List<String> problems = new ArrayList<>();
        List<CoachSlotRequestDay> days = dayRepository.findByRequest_IdInOrderByWeekday(List.of(id));
        for (PlannedSession planned : sessionPlan(request.getFirstDate(), slotByWeekday(request, days), request.getSessions())) {
            LocalDate date = planned.date();
            try {
                CoachSlot slot = coachSlotService.createSlot(request.getCoach(), request.getCategory(), fixedRoom, date,
                        planned.slot());
                coachSlotService.checkMemberFree(member.getId(), slot);
                slot.setStatus("HELD");
                slot.setMember(member);
                slot.setHeldUntil(payUntil);
                slot.setPrice(coachSlotService.priceFor(coachSlotService.slotMinutes(planned.slot(), date)));
                slot.setRequest(request);
                slotRepository.saveAndFlush(slot);
            } catch (BusinessRuleException e) {
                problems.add(date + ": " + e.getMessage());
            }
        }
        if (!problems.isEmpty()) {
            throw new BusinessRuleException("Không thể duyệt cả yêu cầu. " + String.join("; ", problems));
        }

        request.setStatus("APPROVED");
        request.setDecidedBy(manager);
        request.setDecidedAt(now);
        request.setExpiresAt(payUntil);
        requestRepository.save(request);

        auditService.log(manager, "COACH_REQUEST_APPROVED", "coach_slot_request", id, Map.of("sessions", request.getSessions()));
        notificationService.sendWithEmail(member.getId(), "BOOKING", "Yêu cầu 1-1 đã được duyệt",
                "Yêu cầu " + request.getSessions() + " buổi đã được duyệt. Hãy thanh toán trước " + WHEN.format(payUntil)
                        + " nếu không các buổi sẽ được nhả.",
                "Sport Center - Yêu cầu buổi 1-1 đã được duyệt",
                "Yêu cầu " + request.getSessions() + " buổi 1-1 của bạn đã được duyệt. Vui lòng thanh toán trước "
                        + WHEN.format(payUntil) + ", quá hạn các buổi sẽ được nhả.");
        return toResponse(request, slotsOf(request));
    }

    @Transactional
    public CoachSlotRequestResponse reject(String managerEmail, Long id, String reason) {
        User manager = coachSlotService.activeUser(managerEmail, "MANAGER", "Chỉ quản lý đang hoạt động mới từ chối được yêu cầu");
        CoachSlotRequest request = lock(id);
        OffsetDateTime now = OffsetDateTime.now();
        requirePending(request, now);

        request.setStatus("REJECTED");
        request.setDecidedBy(manager);
        request.setDecidedAt(now);
        request.setRejectReason(reason);
        requestRepository.save(request);

        auditService.log(manager, "COACH_REQUEST_REJECTED", "coach_slot_request", id, Map.of("reason", reason));
        notificationService.sendWithEmail(request.getMember().getId(), "BOOKING", "Yêu cầu 1-1 bị từ chối",
                "Yêu cầu 1-1 của bạn bị từ chối: " + reason,
                "Sport Center - Yêu cầu buổi 1-1 chưa được duyệt",
                "Yêu cầu buổi 1-1 của bạn chưa được duyệt. Lý do: " + reason + ". Bạn có thể gửi yêu cầu khác với giờ khác.");
        return toResponse(request, List.of());
    }

    // ====================== SCHEDULER ======================

    // Yêu cầu chờ duyệt quá hạn thì hết hạn. Yêu cầu đã duyệt mà mọi suất đã bị nhả vì quá hạn trả tiền cũng hết hạn
    // (các suất tự nhả ở CoachSlotService.releaseExpiredHolds, không mở lại thành suất trống)
    @Transactional
    public int expireOverdue() {
        OffsetDateTime now = OffsetDateTime.now();
        int expired = 0;
        for (Long id : requestRepository.findPendingOverdueIds(now)) {
            CoachSlotRequest request = lock(id);
            if ("PENDING".equals(request.getStatus()) && request.getExpiresAt().isBefore(now)) {
                expire(request, "chưa được duyệt kịp thời");
                expired++;
            }
        }
        for (Long id : requestRepository.findApprovedOverdueIds(now)) {
            CoachSlotRequest request = lock(id);
            if ("APPROVED".equals(request.getStatus()) && request.getExpiresAt().isBefore(now)
                    && !slotRepository.existsByRequest_IdAndStatusIn(id, LIVE_SLOT_STATUSES)) {
                expire(request, "chưa được thanh toán kịp thời");
                expired++;
            }
        }
        return expired;
    }

    // ====================== HELPERS ======================

    private void expire(CoachSlotRequest request, String why) {
        request.setStatus("EXPIRED");
        requestRepository.save(request);
        notificationService.send(request.getMember().getId(), "BOOKING", "Yêu cầu 1-1 đã hết hạn",
                "Yêu cầu 1-1 của bạn " + why + " nên đã hết hạn.");
    }

    private void requirePending(CoachSlotRequest request, OffsetDateTime now) {
        if (!"PENDING".equals(request.getStatus())) {
            throw new BusinessRuleException("Chỉ duyệt hoặc từ chối được yêu cầu đang chờ (trạng thái hiện tại: " + request.getStatus() + ")");
        }
        if (request.getExpiresAt().isBefore(now)) {
            throw new BusinessRuleException("Yêu cầu này đã hết hạn");
        }
    }

    // Nhả các suất đang giữ chỗ của yêu cầu chưa trả tiền
    private void releaseHeldSlots(CoachSlotRequest request, User actor) {
        OffsetDateTime now = OffsetDateTime.now();
        for (CoachSlot listed : slotRepository.findByRequest_IdInOrderByStartAt(List.of(request.getId()))) {
            CoachSlot slot = coachSlotService.lockSlot(listed.getId());
            if ("HELD".equals(slot.getStatus())) {
                slot.setStatus("CANCELED");
                slot.setCanceledBy(actor);
                slot.setCanceledAt(now);
                slotRepository.saveAndFlush(slot);
            }
        }
    }

    private CoachSlotRequest lock(Long id) {
        return requestRepository.findByIdForUpdate(id)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "Không tìm thấy yêu cầu có mã " + id));
    }

    private List<CoachSlotResponse> slotsOf(CoachSlotRequest request) {
        return coachSlotService.toResponses(slotRepository.findByRequest_IdInOrderByStartAt(List.of(request.getId())));
    }

    private List<CoachSlotRequestResponse> toResponses(List<CoachSlotRequest> requests) {
        if (requests.isEmpty()) {
            return List.of();
        }
        List<Long> ids = requests.stream().map(CoachSlotRequest::getId).toList();
        Map<Long, List<CoachSlot>> slotsByRequest = slotRepository.findByRequest_IdInOrderByStartAt(ids).stream()
                .collect(Collectors.groupingBy(s -> s.getRequest().getId()));
        Map<Long, List<CoachSlotRequestDay>> daysByRequest = dayRepository.findByRequest_IdInOrderByWeekday(ids).stream()
                .collect(Collectors.groupingBy(d -> d.getRequest().getId()));
        return requests.stream()
                .map(r -> toResponse(r, coachSlotService.toResponses(slotsByRequest.getOrDefault(r.getId(), List.of())),
                        daysByRequest.getOrDefault(r.getId(), List.of())))
                .toList();
    }

    private CoachSlotRequestResponse toResponse(CoachSlotRequest request, List<CoachSlotResponse> slots) {
        return toResponse(request, slots, dayRepository.findByRequest_IdInOrderByWeekday(List.of(request.getId())));
    }

    // Giá gốc cả chuỗi: đúng giá đã chụp ở các suất còn sống, chưa có suất (chờ duyệt, bị từ chối...) thì cộng giá hiện tại của từng buổi
    // theo độ dài khung giờ của buổi đó. Ưu đãi theo hạng thành viên tính một lần trên tổng, giống lúc tạo thanh toán
    private CoachSlotRequestResponse toResponse(CoachSlotRequest request, List<CoachSlotResponse> slots, List<CoachSlotRequestDay> days) {
        List<CoachSlotResponse> live = slots.stream().filter(s -> !"CANCELED".equals(s.getStatus())).toList();
        BigDecimal listTotal = live.isEmpty()
                ? sessionPlan(request.getFirstDate(), slotByWeekday(request, days), request.getSessions()).stream()
                        .map(p -> coachSlotService.priceFor(coachSlotService.slotMinutes(p.slot(), p.date())))
                        .reduce(BigDecimal.ZERO, BigDecimal::add)
                : live.stream().map(CoachSlotResponse::getPrice).reduce(BigDecimal.ZERO, BigDecimal::add);
        BigDecimal discount = pricingService.quote(request.getMember().getId(), PricingService.ONE_ON_ONE, listTotal,
                LocalDate.now(SportClassService.CENTER_ZONE)).discountAmount();
        return new CoachSlotRequestResponse(request, days, listTotal, discount, slots);
    }
}
