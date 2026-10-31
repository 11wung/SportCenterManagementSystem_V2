package com.swp391.sportcentermanagementsystem_v2.service;

import com.swp391.sportcentermanagementsystem_v2.dto.responseDTO.TimetableItemResponse;
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
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;

// Thời khóa biểu của member: gộp buổi lớp đang tham gia và buổi 1-1 đã đặt, sắp theo ngày giờ
@Service
@RequiredArgsConstructor
public class MemberTimetableService {

    private static final int DEFAULT_RANGE_DAYS = 7;
    private static final int MAX_RANGE_DAYS = 31;

    private final ClassSessionRepository sessionRepository;
    private final CoachSlotRepository slotRepository;
    private final UserRepository userRepository;

    @Transactional(readOnly = true)
    public List<TimetableItemResponse> myTimetable(String memberEmail, LocalDate from, LocalDate to) {
        User member = userRepository.findByEmail(memberEmail)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "User not found"));
        LocalDate start = from != null ? from : LocalDate.now(SportClassService.CENTER_ZONE);
        LocalDate end = to != null ? to : start.plusDays(DEFAULT_RANGE_DAYS - 1);
        if (end.isBefore(start)) {
            throw new BusinessRuleException("The end date must not be before the start date");
        }
        if (ChronoUnit.DAYS.between(start, end) >= MAX_RANGE_DAYS) {
            throw new BusinessRuleException("The date range must not exceed " + MAX_RANGE_DAYS + " days");
        }

        List<TimetableItemResponse> items = new ArrayList<>();
        sessionRepository.findMemberSessions(member.getId(), start, end).forEach(s -> items.add(
                new TimetableItemResponse("CLASS", s.getId(), s.getSportClass().getId(), s.getSportClass().getName(),
                        s.getSessionDate(), s.getStartTime(), s.getEndTime(), s.getRoom().getRoomName(),
                        s.getCoach().getFullName(), s.getStatus(), s.isMakeup(),
                        s.isMakeup() && "SCHEDULED".equals(s.getStatus())
                                && sessionRepository.existsAttendance(s.getId(), member.getId()))));
        slotRepository.findMemberBooked(member.getId(),
                start.atStartOfDay(SportClassService.CENTER_ZONE).toOffsetDateTime(),
                end.plusDays(1).atStartOfDay(SportClassService.CENTER_ZONE).toOffsetDateTime()).forEach(s -> {
            var startAt = s.getStartAt().atZoneSameInstant(SportClassService.CENTER_ZONE);
            var endAt = s.getEndAt().atZoneSameInstant(SportClassService.CENTER_ZONE);
            items.add(new TimetableItemResponse("ONE_ON_ONE", s.getId(), null, (s.getCategory() != null ? s.getCategory().getName() : "1-1") + " with " + s.getCoach().getFullName(),
                    startAt.toLocalDate(), startAt.toLocalTime(), endAt.toLocalTime(),
                    s.getRoom() != null ? s.getRoom().getRoomName() : null,
                    s.getCoach().getFullName(), s.getStatus(), false, false));
        });
        items.sort(Comparator.comparing(TimetableItemResponse::date).thenComparing(TimetableItemResponse::startTime));
        return items;
    }
}
