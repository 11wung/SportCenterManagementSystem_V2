package com.swp391.sportcentermanagementsystem_v2.service;

import com.swp391.sportcentermanagementsystem_v2.dto.requestDTO.ReplaceSchedulesRequest;
import com.swp391.sportcentermanagementsystem_v2.dto.requestDTO.ScheduleItemRequest;
import com.swp391.sportcentermanagementsystem_v2.dto.responseDTO.ClassScheduleResponse;
import com.swp391.sportcentermanagementsystem_v2.entity.ClassSchedule;
import com.swp391.sportcentermanagementsystem_v2.entity.FacilityRoom;
import com.swp391.sportcentermanagementsystem_v2.entity.SportClass;
import com.swp391.sportcentermanagementsystem_v2.entity.User;
import com.swp391.sportcentermanagementsystem_v2.exception.BusinessRuleException;
import com.swp391.sportcentermanagementsystem_v2.repository.ClassScheduleRepository;
import com.swp391.sportcentermanagementsystem_v2.repository.ClassSessionRepository;
import com.swp391.sportcentermanagementsystem_v2.repository.FacilityRoomRepository;
import com.swp391.sportcentermanagementsystem_v2.repository.SportClassRepository;
import com.swp391.sportcentermanagementsystem_v2.repository.UserRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;

import java.time.DayOfWeek;
import java.time.LocalTime;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;

@Service
@RequiredArgsConstructor
public class ClassScheduleService {

    // BR-SCH-01: khoảng nghỉ tối thiểu giữa hai buổi
    static final int GAP_MINUTES = 15;

    private final ClassScheduleRepository scheduleRepository;
    private final ClassSessionRepository sessionRepository;
    private final SportClassRepository sportClassRepository;
    private final FacilityRoomRepository facilityRoomRepository;
    private final UserRepository userRepository;

    // Đặt toàn bộ lịch hằng tuần của class nháp (thay thế lịch cũ). Các buổi đã sinh bị xóa vì không còn đúng
    @Transactional
    public List<ClassScheduleResponse> replaceSchedules(Long classId, ReplaceSchedulesRequest request) {
        SportClass sportClass = sportClassRepository.findByIdForUpdate(classId)
                .orElseThrow(() -> new ResponseStatusException(
                        HttpStatus.NOT_FOUND, "Sport class not found with ID: " + classId));
        requireDraft(sportClass);

        List<ScheduleItemRequest> items = request.getSchedules();
        List<ClassSchedule> schedules = new ArrayList<>();
        for (ScheduleItemRequest item : items) {
            if (!item.getEndTime().isAfter(item.getStartTime())) {
                throw new BusinessRuleException("End time must be after start time");
            }
            FacilityRoom room = facilityRoomRepository.findById(item.getRoomId())
                    .orElseThrow(() -> new ResponseStatusException(
                            HttpStatus.BAD_REQUEST, "Facility room not found with ID: " + item.getRoomId()));
            checkRoomFitsClass(room, sportClass);

            ClassSchedule schedule = new ClassSchedule();
            schedule.setSportClass(sportClass);
            schedule.setRoom(room);
            schedule.setDayOfWeek(item.getDayOfWeek());
            schedule.setStartTime(item.getStartTime());
            schedule.setEndTime(item.getEndTime());
            schedules.add(schedule);
        }
        checkItemsDoNotOverlap(items);

        sessionRepository.deleteByClassId(classId);
        scheduleRepository.deleteByClassId(classId);
        return scheduleRepository.saveAll(schedules).stream().map(this::toResponse).toList();
    }

    // Xem toàn bộ lịch hoạt động của trung tâm (Manager / Member / Coach / Receptionist)
    @Transactional(readOnly = true)
    public List<ClassScheduleResponse> getAllSchedules() {
        return scheduleRepository.findAllByOrderByDayOfWeekAscStartTimeAsc()
                .stream()
                .map(this::toResponse)
                .toList();
    }

    @Transactional(readOnly = true)
    public ClassScheduleResponse getScheduleById(Long id) {
        ClassSchedule schedule = scheduleRepository.findWithDetailsById(id)
                .orElseThrow(() -> new ResponseStatusException(
                        HttpStatus.NOT_FOUND, "Class schedule not found with ID: " + id));
        return toResponse(schedule);
    }

    @Transactional(readOnly = true)
    public List<ClassScheduleResponse> getSchedulesByClassId(Long classId) {
        if (!sportClassRepository.existsById(classId)) {
            throw new ResponseStatusException(
                    HttpStatus.NOT_FOUND, "Sport class not found with ID: " + classId);
        }
        return scheduleRepository.findBySportClassIdOrderByDayOfWeekAscStartTimeAsc(classId)
                .stream()
                .map(this::toResponse)
                .toList();
    }

    // Xem lịch hoạt động theo Thứ trong tuần (1 = Monday ... 7 = Sunday)
    @Transactional(readOnly = true)
    public List<ClassScheduleResponse> getSchedulesByDayOfWeek(Short dayOfWeek) {
        if (dayOfWeek == null || dayOfWeek < 1 || dayOfWeek > 7) {
            throw new BusinessRuleException("Day of week must be between 1 (Monday) and 7 (Sunday)!");
        }
        return scheduleRepository.findByDayOfWeekOrderByStartTimeAsc(dayOfWeek)
                .stream()
                .map(this::toResponse)
                .toList();
    }

    // Coach xem lịch dạy của chính mình
    @Transactional(readOnly = true)
    public List<ClassScheduleResponse> getMyCoachSchedules(String email) {
        User coach = userRepository.findByEmail(email)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "Coach not found!"));

        return scheduleRepository.findBySportClassCoachIdOrderByDayOfWeekAscStartTimeAsc(coach.getId())
                .stream()
                .map(this::toResponse)
                .toList();
    }

    // ===================== QUY TẮC DÙNG CHUNG VỚI ClassSessionService =====================

    // Lịch và buổi học chỉ sửa được khi class còn nháp (BR-CLS-03)
    static void requireDraft(SportClass sportClass) {
        if (!"DRAFT".equals(sportClass.getStatus())) {
            throw new BusinessRuleException("Only a DRAFT class can be changed here (status: "
                    + sportClass.getStatus() + ")");
        }
    }

    // BR-SCH-02, BR-SCH-04, BR-CLS-02: phòng đang hoạt động, đúng môn, đủ sức chứa
    static void checkRoomFitsClass(FacilityRoom room, SportClass sportClass) {
        if (!"AVAILABLE".equalsIgnoreCase(room.getMaintenanceStatus())) {
            throw new BusinessRuleException("Room " + room.getRoomName() + " is not available (status: "
                    + room.getMaintenanceStatus() + ")");
        }
        Long categoryId = sportClass.getCourse().getCategory().getId();
        if (room.getSports().stream().noneMatch(s -> s.getId().equals(categoryId))) {
            throw new BusinessRuleException("Room " + room.getRoomName() + " is not set up for this sport");
        }
        if (sportClass.getMaxCapacity() > room.getCapacity()) {
            throw new BusinessRuleException(String.format(
                    "Class max capacity (%d) exceeds capacity (%d) of room %s",
                    sportClass.getMaxCapacity(), room.getCapacity(), room.getRoomName()));
        }
    }

    // Hai dòng lịch cùng ngày không được chồng giờ hoặc cách nhau dưới 15 phút (cùng một coach dạy cả hai).
    // shortcut: LocalTime không vòng qua nửa đêm nên buổi kết thúc sau 23:45 có thể lọt, nâng cấp nếu có lớp giờ khuya
    private void checkItemsDoNotOverlap(List<ScheduleItemRequest> items) {
        List<ScheduleItemRequest> sorted = items.stream()
                .sorted(Comparator.comparing(ScheduleItemRequest::getDayOfWeek)
                        .thenComparing(ScheduleItemRequest::getStartTime))
                .toList();
        for (int i = 1; i < sorted.size(); i++) {
            ScheduleItemRequest prev = sorted.get(i - 1);
            ScheduleItemRequest next = sorted.get(i);
            LocalTime earliestNext = prev.getEndTime().plusMinutes(GAP_MINUTES);
            if (prev.getDayOfWeek().equals(next.getDayOfWeek()) && next.getStartTime().isBefore(earliestNext)) {
                throw new BusinessRuleException("Two schedule rows on "
                        + DayOfWeek.of(next.getDayOfWeek()) + " overlap or are less than "
                        + GAP_MINUTES + " minutes apart");
            }
        }
    }

    private ClassScheduleResponse toResponse(ClassSchedule cs) {
        ClassScheduleResponse res = new ClassScheduleResponse();
        res.setId(cs.getId());

        SportClass sc = cs.getSportClass();
        res.setClassId(sc.getId());
        res.setClassName(sc.getName());
        res.setCategoryName(sc.getCourse().getCategory().getName());
        res.setLevel(sc.getCourse().getLevel());
        res.setClassMaxCapacity(sc.getMaxCapacity());

        res.setCoachId(sc.getCoach().getId());
        res.setCoachName(sc.getCoach().getFullName());

        FacilityRoom room = cs.getRoom();
        res.setRoomId(room.getId());
        res.setRoomName(room.getRoomName());
        res.setFloor(room.getFloor());
        res.setRoomCapacity(room.getCapacity());

        res.setDayOfWeek(cs.getDayOfWeek());
        res.setDayOfWeekName(DayOfWeek.of(cs.getDayOfWeek()).name());
        res.setStartTime(cs.getStartTime());
        res.setEndTime(cs.getEndTime());

        return res;
    }
}
