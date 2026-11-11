package com.swp391.sportcentermanagementsystem_v2.service;

import com.swp391.sportcentermanagementsystem_v2.dto.requestDTO.CoachAvailabilityPlanRequest;
import com.swp391.sportcentermanagementsystem_v2.dto.responseDTO.CoachAvailabilityPlanResponse;
import com.swp391.sportcentermanagementsystem_v2.dto.responseDTO.CoachAvailabilityPlanSessionResponse;
import com.swp391.sportcentermanagementsystem_v2.dto.responseDTO.CoachAvailabilityResponse;
import com.swp391.sportcentermanagementsystem_v2.dto.responseDTO.CoachBusySlotResponse;
import com.swp391.sportcentermanagementsystem_v2.entity.ClassSession;
import com.swp391.sportcentermanagementsystem_v2.entity.CoachSlot;
import com.swp391.sportcentermanagementsystem_v2.entity.TimeSlot;
import com.swp391.sportcentermanagementsystem_v2.entity.User;
import com.swp391.sportcentermanagementsystem_v2.exception.BusinessRuleException;
import com.swp391.sportcentermanagementsystem_v2.repository.ClassSessionRepository;
import com.swp391.sportcentermanagementsystem_v2.repository.CoachSlotRepository;
import com.swp391.sportcentermanagementsystem_v2.repository.UserRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;

import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;
import java.util.Set;

// Trả dữ liệu bận và khả dụng cho Member, sử dụng cùng luật với submit/approve.
@Service
@RequiredArgsConstructor
public class CoachAvailabilityService {

    private final UserRepository userRepository;
    private final ClassSessionRepository classSessionRepository;
    private final CoachSlotRepository coachSlotRepository;
    private final CoachSlotService coachSlotService;
    private final TimeSlotService timeSlotService;
    private final SystemSettingService settings;

    @Transactional(readOnly = true)
    public CoachAvailabilityResponse getBusySlots(Long coachId, LocalDate date) {
        requireActiveCoach(coachId);
        int buffer = settings.getScheduleBufferMinutes();
        OffsetDateTime dayStart = date.atStartOfDay(SportClassService.CENTER_ZONE).toOffsetDateTime();
        OffsetDateTime nextDayStart = date.plusDays(1).atStartOfDay(SportClassService.CENTER_ZONE).toOffsetDateTime();
        List<CoachBusySlotResponse> busySlots = new ArrayList<>();

        // Không đọc sportClass.getName(): lớp DRAFT vẫn chiếm lịch nhưng tên lớp không được lộ cho Member.
        List<ClassSession> classSessions = classSessionRepository.findCoachBusySessions(coachId, date, date);
        for (ClassSession session : classSessions) {
            OffsetDateTime start = session.getSessionDate().atTime(session.getStartTime())
                    .atZone(SportClassService.CENTER_ZONE).toOffsetDateTime();
            OffsetDateTime end = session.getSessionDate().atTime(session.getEndTime())
                    .atZone(SportClassService.CENTER_ZONE).toOffsetDateTime();
            busySlots.add(new CoachBusySlotResponse(
                    "CLASS", "Lịch bận", "Coach đã có lịch dạy trong thời gian này", session.getStatus(),
                    start, end, start.minusMinutes(buffer), end.plusMinutes(buffer)));
        }

        List<CoachSlot> oneOnOneSlots = coachSlotRepository.findCoachOverlapping(
                coachId, dayStart.minusMinutes(buffer), nextDayStart.plusMinutes(buffer), null);
        for (CoachSlot slot : oneOnOneSlots) {
            String message = "OPEN".equals(slot.getStatus())
                    ? "Khung giờ này đã được Trung tâm dành cho lịch 1-1 của Coach"
                    : "Coach đã có lịch 1-1 trong thời gian này";
            busySlots.add(new CoachBusySlotResponse(
                    "ONE_ON_ONE", "Lịch 1-1", message, slot.getStatus(),
                    slot.getStartAt(), slot.getEndAt(),
                    slot.getStartAt().minusMinutes(buffer), slot.getEndAt().plusMinutes(buffer)));
        }

        busySlots.sort(Comparator.comparing(CoachBusySlotResponse::blockedFrom));
        return new CoachAvailabilityResponse(coachId, date, buffer, busySlots);
    }

    // Trả toàn bộ ngày dự kiến và available=true/false cho từng buổi trong kế hoạch 1-1.
    // Kiểm tra này gọi đúng helper dùng ở submit và approve; khoảng thời gian là [start, end).
    @Transactional(readOnly = true)
    public CoachAvailabilityPlanResponse getPlanAvailability(Long coachId, CoachAvailabilityPlanRequest request) {
        requireActiveCoach(coachId);
        if (request.getFirstDate() == null) {
            throw new BusinessRuleException("Ngày bắt đầu là bắt buộc");
        }
        int sessions = request.getSessions() == null ? 0 : request.getSessions();
        int max = settings.getInt("one_on_one.max_sessions_per_request");
        if (sessions < 1 || sessions > max) {
            throw new BusinessRuleException("Một kế hoạch có từ 1 đến " + max + " buổi");
        }
        if (request.getDays() == null || request.getDays().isEmpty() || request.getDays().size() != sessions) {
            throw new BusinessRuleException("Số ngày/slot đã chọn phải bằng đúng số buổi đăng ký (" + sessions + " buổi)");
        }

        Map<Integer, List<TimeSlot>> slotsByWeekday = new TreeMap<>();
        Set<String> selectedSlots = new HashSet<>();
        for (CoachAvailabilityPlanRequest.Day day : request.getDays()) {
            if (day == null || day.getWeekday() == null || day.getTimeSlotId() == null) {
                throw new BusinessRuleException("Mỗi buổi phải có thứ và khung giờ hợp lệ");
            }
            if (day.getWeekday() < 1 || day.getWeekday() > 7) {
                throw new BusinessRuleException("Bạn được chọn lịch từ thứ 2 đến Chủ Nhật");
            }
            String unique = day.getWeekday() + "-" + day.getTimeSlotId();
            if (!selectedSlots.add(unique)) {
                throw new BusinessRuleException("Không được chọn trùng thứ và khung giờ");
            }
            TimeSlot slot = timeSlotService.requireActive(day.getTimeSlotId());
            slotsByWeekday.computeIfAbsent(day.getWeekday(), key -> new ArrayList<>()).add(slot);
        }
        slotsByWeekday.values().forEach(slots -> slots.sort(Comparator.comparing(TimeSlot::getStartTime)));

        List<CoachSlotRequestService.PlannedSession> plan = CoachSlotRequestService.sessionPlanForSelectedSlots(
                request.getFirstDate(), slotsByWeekday, sessions);
        List<CoachAvailabilityPlanSessionResponse> results = new ArrayList<>();
        int sessionNo = 1;
        for (CoachSlotRequestService.PlannedSession planned : plan) {
            OffsetDateTime start = TimeSlotService.startAt(planned.slot(), planned.date());
            long minutes = coachSlotService.slotMinutes(planned.slot(), planned.date());
            OffsetDateTime end = start.plusMinutes(minutes);
            boolean available = coachSlotService.isCoachFree(coachId, start, end);
            results.add(new CoachAvailabilityPlanSessionResponse(
                    sessionNo++, planned.date(), planned.date().getDayOfWeek().getValue(),
                    planned.slot().getId(), planned.slot().getStartTime(),
                    end.atZoneSameInstant(SportClassService.CENTER_ZONE).toLocalTime(),
                    available,
                    available ? "Có thể đặt" : "Coach đã có lịch bận trong thời gian này"));
        }
        return new CoachAvailabilityPlanResponse(coachId, request.getFirstDate(), sessions,
                settings.getScheduleBufferMinutes(), results);
    }

    private User requireActiveCoach(Long coachId) {
        User coach = userRepository.findById(coachId)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "Không tìm thấy Coach"));
        if (coach.getRole() == null || !"COACH".equals(coach.getRole().getCode())
                || !Boolean.TRUE.equals(coach.getIsActive())) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Coach không hoạt động hoặc không hợp lệ");
        }
        return coach;
    }

}
