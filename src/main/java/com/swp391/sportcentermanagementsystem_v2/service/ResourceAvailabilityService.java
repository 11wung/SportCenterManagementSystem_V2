package com.swp391.sportcentermanagementsystem_v2.service;

import com.swp391.sportcentermanagementsystem_v2.dto.responseDTO.ResourceAvailabilityResponse;
import com.swp391.sportcentermanagementsystem_v2.dto.responseDTO.ResourceAvailabilityResponse.Ref;
import com.swp391.sportcentermanagementsystem_v2.entity.ClassSession;
import com.swp391.sportcentermanagementsystem_v2.entity.CoachProfile;
import com.swp391.sportcentermanagementsystem_v2.entity.CoachSlot;
import com.swp391.sportcentermanagementsystem_v2.entity.FacilityRoom;
import com.swp391.sportcentermanagementsystem_v2.entity.SportClass;
import com.swp391.sportcentermanagementsystem_v2.entity.TimeSlot;
import com.swp391.sportcentermanagementsystem_v2.entity.User;
import com.swp391.sportcentermanagementsystem_v2.exception.BusinessRuleException;
import com.swp391.sportcentermanagementsystem_v2.repository.ClassSessionRepository;
import com.swp391.sportcentermanagementsystem_v2.repository.CoachProfileRepository;
import com.swp391.sportcentermanagementsystem_v2.repository.CoachSlotRepository;
import com.swp391.sportcentermanagementsystem_v2.repository.FacilityRoomRepository;
import com.swp391.sportcentermanagementsystem_v2.repository.SportClassRepository;
import com.swp391.sportcentermanagementsystem_v2.repository.UserRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;

import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.function.Predicate;
import java.util.stream.Collectors;

// Lịch rảnh để manager xếp lịch lớp: với mỗi ngày x slot giờ, phòng nào và coach nào chưa bị buổi lớp hay suất 1-1 chiếm
// (cùng luật trùng với BR-SCH-01: chồng giờ hoặc cách nhau dưới khoảng nghỉ)
@Service
@RequiredArgsConstructor
public class ResourceAvailabilityService {

    private static final int DEFAULT_DAYS = 7;
    private static final int MAX_DAYS = 14;

    private final FacilityRoomRepository facilityRoomRepository;
    private final UserRepository userRepository;
    private final CoachProfileRepository coachProfileRepository;
    private final ClassSessionRepository classSessionRepository;
    private final CoachSlotRepository coachSlotRepository;
    private final SportClassRepository sportClassRepository;
    private final TimeSlotService timeSlotService;
    private final SystemSettingService settings;

    @Transactional(readOnly = true)
    public List<ResourceAvailabilityResponse> availability(LocalDate from, LocalDate to, Long classId) {
        LocalDate start = from != null ? from : LocalDate.now(SportClassService.CENTER_ZONE);
        LocalDate end = to != null ? to : start.plusDays(DEFAULT_DAYS - 1);
        if (end.isBefore(start)) {
            throw new BusinessRuleException("The end date must not be before the start date");
        }
        if (ChronoUnit.DAYS.between(start, end) >= MAX_DAYS) {
            throw new BusinessRuleException("The date range must not exceed " + MAX_DAYS + " days");
        }

        SportClass sportClass = classId == null ? null : sportClassRepository.findById(classId)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "Sport class not found with ID: " + classId));
        Long categoryId = sportClass == null ? null : sportClass.getCourse().getCategory().getId();

        List<FacilityRoom> rooms = facilityRoomRepository.findAvailableWithSports().stream()
                .filter(r -> sportClass == null || (r.getCapacity() >= sportClass.getMaxCapacity()
                        && r.getSports().stream().anyMatch(s -> s.getId().equals(categoryId))))
                .toList();
        List<User> coaches = teachingCoaches(categoryId);

        int buffer = settings.getInt("schedule.buffer_minutes");
        Map<LocalDate, List<ClassSession>> sessionsByDate = classSessionRepository.findBusySessionsBetween(start, end).stream()
                .collect(Collectors.groupingBy(ClassSession::getSessionDate));
        List<CoachSlot> coachSlots = coachSlotRepository.findActiveBetween(
                start.atStartOfDay(SportClassService.CENTER_ZONE).toOffsetDateTime().minusMinutes(buffer),
                end.plusDays(1).atStartOfDay(SportClassService.CENTER_ZONE).toOffsetDateTime().plusMinutes(buffer));

        OffsetDateTime now = OffsetDateTime.now();
        List<TimeSlot> timeSlots = timeSlotService.activeSlots();
        List<ResourceAvailabilityResponse> result = new ArrayList<>();
        for (LocalDate date = start; !date.isAfter(end); date = date.plusDays(1)) {
            List<ClassSession> sessions = sessionsByDate.getOrDefault(date, List.of());
            for (TimeSlot timeSlot : timeSlots) {
                OffsetDateTime slotStart = TimeSlotService.startAt(timeSlot, date);
                OffsetDateTime slotEnd = TimeSlotService.endAt(timeSlot, date);
                if (!slotStart.isAfter(now)) {
                    continue;
                }
                Predicate<ClassSession> clashes = s -> s.getStartTime().isBefore(timeSlot.getEndTime().plusMinutes(buffer))
                        && timeSlot.getStartTime().isBefore(s.getEndTime().plusMinutes(buffer));
                List<Ref> freeRooms = rooms.stream()
                        .filter(r -> sessions.stream().noneMatch(s -> s.getRoom().getId().equals(r.getId()) && clashes.test(s)))
                        .map(r -> new Ref(r.getId(), r.getRoomName()))
                        .toList();
                List<Ref> freeCoaches = coaches.stream()
                        .filter(c -> sessions.stream().noneMatch(s -> s.getCoach().getId().equals(c.getId()) && clashes.test(s)))
                        .filter(c -> coachSlots.stream().noneMatch(s -> s.getCoach().getId().equals(c.getId())
                                && s.getStartAt().isBefore(slotEnd.plusMinutes(buffer))
                                && slotStart.isBefore(s.getEndAt().plusMinutes(buffer))))
                        .map(c -> new Ref(c.getId(), c.getFullName()))
                        .toList();
                result.add(new ResourceAvailabilityResponse(date, timeSlot.getId(), timeSlot.getSlotNo(),
                        timeSlot.getStartTime(), timeSlot.getEndTime(), freeRooms, freeCoaches));
            }
        }
        return result;
    }

    // Coach đang hoạt động; có môn thì chỉ lấy coach dạy đúng môn đó (BR-SCH-04)
    private List<User> teachingCoaches(Long categoryId) {
        List<User> coaches = userRepository.findByRole_CodeAndIsActiveTrue("COACH");
        if (categoryId == null || coaches.isEmpty()) {
            return coaches;
        }
        Map<Long, CoachProfile> profiles = coachProfileRepository
                .findWithSportsByUserIds(coaches.stream().map(User::getId).toList()).stream()
                .collect(Collectors.toMap(CoachProfile::getUserId, p -> p));
        return coaches.stream()
                .filter(c -> profiles.containsKey(c.getId())
                        && profiles.get(c.getId()).getSports().stream().anyMatch(s -> s.getId().equals(categoryId)))
                .toList();
    }
}
