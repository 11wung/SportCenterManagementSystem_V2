package com.swp391.sportcentermanagementsystem_v2.service;

import com.swp391.sportcentermanagementsystem_v2.dto.requestDTO.SubmitSlotRequest;
import com.swp391.sportcentermanagementsystem_v2.dto.responseDTO.CoachOptionResponse;
import com.swp391.sportcentermanagementsystem_v2.dto.responseDTO.CoachSlotRequestResponse;
import com.swp391.sportcentermanagementsystem_v2.dto.responseDTO.CoachSlotResponse;
import com.swp391.sportcentermanagementsystem_v2.dto.responseDTO.SportCategoryResponse;
import com.swp391.sportcentermanagementsystem_v2.entity.CoachProfile;
import com.swp391.sportcentermanagementsystem_v2.entity.CoachSlot;
import com.swp391.sportcentermanagementsystem_v2.entity.CoachSlotRequest;
import com.swp391.sportcentermanagementsystem_v2.entity.FacilityRoom;
import com.swp391.sportcentermanagementsystem_v2.entity.SportCategory;
import com.swp391.sportcentermanagementsystem_v2.entity.TimeSlot;
import com.swp391.sportcentermanagementsystem_v2.entity.User;
import com.swp391.sportcentermanagementsystem_v2.exception.BusinessRuleException;
import com.swp391.sportcentermanagementsystem_v2.repository.CoachProfileRepository;
import com.swp391.sportcentermanagementsystem_v2.repository.CoachSlotRepository;
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
import java.util.stream.Collectors;

// Yêu cầu buổi 1-1 nhiều buổi: member xin N buổi cùng khung giờ mỗi tuần, manager duyệt cả chuỗi hoặc từ chối.
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

    // ====================== MEMBER ======================

    @Transactional
    public CoachSlotRequestResponse submit(String memberEmail, SubmitSlotRequest in) {
        User member = coachSlotService.activeUser(memberEmail, "MEMBER", "Only active Members can request a 1-1 session");
        User coach = userRepository.findById(in.getCoachId())
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "Coach not found"));
        if (coach.getRole() == null || !"COACH".equals(coach.getRole().getCode()) || !Boolean.TRUE.equals(coach.getIsActive())) {
            throw new BusinessRuleException("Choose an active coach");
        }
        SportCategory sport = sportCategoryRepository.findById(in.getSportCategoryId())
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "Sport category not found"));
        if (!coachSlotService.coachTeaches(coach.getId(), sport.getId())) {
            throw new BusinessRuleException(coach.getFullName() + " does not teach " + sport.getName());
        }
        TimeSlot timeSlot = timeSlotService.requireActive(in.getTimeSlotId());

        int max = settings.getInt("one_on_one.max_sessions_per_request");
        if (in.getSessions() > max) {
            throw new BusinessRuleException("A request can have at most " + max + " sessions");
        }
        int leadHours = settings.getInt("one_on_one.request_min_lead_hours");
        OffsetDateTime now = OffsetDateTime.now();
        if (TimeSlotService.startAt(timeSlot, in.getFirstDate()).isBefore(now.plusHours(leadHours))) {
            throw new BusinessRuleException("The first session must start at least " + leadHours + " hours from now");
        }

        CoachSlotRequest request = new CoachSlotRequest();
        request.setMember(member);
        request.setCoach(coach);
        request.setCategory(sport);
        request.setTimeSlot(timeSlot);
        request.setFirstDate(in.getFirstDate());
        request.setSessions(in.getSessions().shortValue());
        request.setExpiresAt(now.plusHours(settings.getInt("one_on_one.request_expire_hours")));
        requestRepository.save(request);

        for (User manager : userRepository.findByRole_CodeAndIsActiveTrue("MANAGER")) {
            notificationService.send(manager.getId(), "BOOKING", "New 1-1 request", member.getFullName() + " requested "
                    + request.getSessions() + " 1-1 session(s) with " + coach.getFullName() + ".");
        }
        return toResponse(request, List.of());
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
        User member = coachSlotService.activeUser(memberEmail, "MEMBER", "Only active Members can view their requests");
        return toResponses(requestRepository.findByMember_IdOrderByIdDesc(member.getId()));
    }

    // Member hủy yêu cầu chưa được duyệt, hoặc đã duyệt nhưng chưa trả tiền (các suất giữ chỗ được nhả)
    @Transactional
    public CoachSlotRequestResponse cancel(String memberEmail, Long id) {
        User member = coachSlotService.activeUser(memberEmail, "MEMBER", "Only active Members can cancel their requests");
        CoachSlotRequest request = lock(id);
        if (!request.getMember().getId().equals(member.getId())) {
            throw new ResponseStatusException(HttpStatus.FORBIDDEN, "You can only cancel your own requests");
        }
        if ("APPROVED".equals(request.getStatus())) {
            if (paymentRepository.existsByCoachSlot_IdAndStatusIn(anchorSlotId(id), IN_FLIGHT_PAYMENT)) {
                throw new BusinessRuleException("A payment is still being processed. Cancel the QR on the payment page "
                        + "or wait for it to expire, then cancel the request again");
            }
            releaseHeldSlots(request, member);
        } else if (!"PENDING".equals(request.getStatus())) {
            throw new BusinessRuleException("Only a pending or unpaid request can be canceled (status: " + request.getStatus() + ")");
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
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "Request not found with ID: " + requestId));
        if (!List.of("APPROVED", "PAID").contains(request.getStatus())) {
            throw new BusinessRuleException("The request is not waiting for payment (status: " + request.getStatus() + ")");
        }
        return slotRepository.findByRequest_IdInOrderByStartAt(List.of(requestId)).stream()
                .filter(s -> !"CANCELED".equals(s.getStatus()))
                .map(CoachSlot::getId)
                .findFirst()
                .orElseThrow(() -> new BusinessRuleException("The request has no session left to pay"));
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
        User manager = coachSlotService.activeUser(managerEmail, "MANAGER", "Only active Managers can approve requests");
        CoachSlotRequest request = lock(id);
        OffsetDateTime now = OffsetDateTime.now();
        requirePending(request, now);

        FacilityRoom fixedRoom = roomId == null ? null : facilityRoomRepository.findById(roomId)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "Room not found"));
        User member = request.getMember();
        OffsetDateTime payUntil = now.plusHours(settings.getInt("one_on_one.request_pay_hours"));

        List<String> problems = new ArrayList<>();
        for (int i = 0; i < request.getSessions(); i++) {
            LocalDate date = request.getFirstDate().plusWeeks(i);
            try {
                CoachSlot slot = coachSlotService.createSlot(request.getCoach(), request.getCategory(), fixedRoom, date,
                        request.getTimeSlot());
                coachSlotService.checkMemberFree(member.getId(), slot);
                slot.setStatus("HELD");
                slot.setMember(member);
                slot.setHeldUntil(payUntil);
                slot.setPrice(coachSlotService.priceFor(coachSlotService.slotMinutes(request.getTimeSlot(), date)));
                slot.setRequest(request);
                slotRepository.saveAndFlush(slot);
            } catch (BusinessRuleException e) {
                problems.add(date + ": " + e.getMessage());
            }
        }
        if (!problems.isEmpty()) {
            throw new BusinessRuleException("Cannot approve the whole request. " + String.join("; ", problems));
        }

        request.setStatus("APPROVED");
        request.setDecidedBy(manager);
        request.setDecidedAt(now);
        request.setExpiresAt(payUntil);
        requestRepository.save(request);

        auditService.log(manager, "COACH_REQUEST_APPROVED", "coach_slot_request", id, Map.of("sessions", request.getSessions()));
        notificationService.sendWithEmail(member.getId(), "BOOKING", "1-1 request approved",
                "Your request for " + request.getSessions() + " session(s) was approved. Pay before " + WHEN.format(payUntil)
                        + " or the sessions will be released.",
                "Sport Center - Yêu cầu buổi 1-1 đã được duyệt",
                "Yêu cầu " + request.getSessions() + " buổi 1-1 của bạn đã được duyệt. Vui lòng thanh toán trước "
                        + WHEN.format(payUntil) + ", quá hạn các buổi sẽ được nhả.");
        return toResponse(request, slotsOf(request));
    }

    @Transactional
    public CoachSlotRequestResponse reject(String managerEmail, Long id, String reason) {
        User manager = coachSlotService.activeUser(managerEmail, "MANAGER", "Only active Managers can reject requests");
        CoachSlotRequest request = lock(id);
        OffsetDateTime now = OffsetDateTime.now();
        requirePending(request, now);

        request.setStatus("REJECTED");
        request.setDecidedBy(manager);
        request.setDecidedAt(now);
        request.setRejectReason(reason);
        requestRepository.save(request);

        auditService.log(manager, "COACH_REQUEST_REJECTED", "coach_slot_request", id, Map.of("reason", reason));
        notificationService.sendWithEmail(request.getMember().getId(), "BOOKING", "1-1 request rejected",
                "Your 1-1 request was rejected: " + reason,
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
                expire(request, "was not approved in time");
                expired++;
            }
        }
        for (Long id : requestRepository.findApprovedOverdueIds(now)) {
            CoachSlotRequest request = lock(id);
            if ("APPROVED".equals(request.getStatus()) && request.getExpiresAt().isBefore(now)
                    && !slotRepository.existsByRequest_IdAndStatusIn(id, LIVE_SLOT_STATUSES)) {
                expire(request, "was not paid in time");
                expired++;
            }
        }
        return expired;
    }

    // ====================== HELPERS ======================

    private void expire(CoachSlotRequest request, String why) {
        request.setStatus("EXPIRED");
        requestRepository.save(request);
        notificationService.send(request.getMember().getId(), "BOOKING", "1-1 request expired",
                "Your 1-1 request " + why + " and has expired.");
    }

    private void requirePending(CoachSlotRequest request, OffsetDateTime now) {
        if (!"PENDING".equals(request.getStatus())) {
            throw new BusinessRuleException("Only a pending request can be decided (status: " + request.getStatus() + ")");
        }
        if (request.getExpiresAt().isBefore(now)) {
            throw new BusinessRuleException("This request has expired");
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
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "Request not found with ID: " + id));
    }

    private List<CoachSlotResponse> slotsOf(CoachSlotRequest request) {
        return coachSlotService.toResponses(slotRepository.findByRequest_IdInOrderByStartAt(List.of(request.getId())));
    }

    private List<CoachSlotRequestResponse> toResponses(List<CoachSlotRequest> requests) {
        if (requests.isEmpty()) {
            return List.of();
        }
        Map<Long, List<CoachSlot>> slotsByRequest = slotRepository
                .findByRequest_IdInOrderByStartAt(requests.stream().map(CoachSlotRequest::getId).toList()).stream()
                .collect(Collectors.groupingBy(s -> s.getRequest().getId()));
        return requests.stream()
                .map(r -> toResponse(r, coachSlotService.toResponses(slotsByRequest.getOrDefault(r.getId(), List.of()))))
                .toList();
    }

    // Giá gốc cả chuỗi: đúng giá đã chụp ở các suất còn sống, chưa có suất (chờ duyệt, bị từ chối...) thì theo giá hiện tại.
    // Ưu đãi theo hạng thành viên tính một lần trên tổng, giống lúc tạo thanh toán
    private CoachSlotRequestResponse toResponse(CoachSlotRequest request, List<CoachSlotResponse> slots) {
        List<CoachSlotResponse> live = slots.stream().filter(s -> !"CANCELED".equals(s.getStatus())).toList();
        BigDecimal listTotal = live.isEmpty()
                ? coachSlotService.priceFor(coachSlotService.slotMinutes(request.getTimeSlot(), request.getFirstDate()))
                        .multiply(BigDecimal.valueOf(request.getSessions()))
                : live.stream().map(CoachSlotResponse::getPrice).reduce(BigDecimal.ZERO, BigDecimal::add);
        BigDecimal discount = pricingService.quote(request.getMember().getId(), PricingService.ONE_ON_ONE, listTotal,
                LocalDate.now(SportClassService.CENTER_ZONE)).discountAmount();
        return new CoachSlotRequestResponse(request, listTotal, discount, slots);
    }
}
