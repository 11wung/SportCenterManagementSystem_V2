package com.swp391.sportcentermanagementsystem_v2.service;

import com.swp391.sportcentermanagementsystem_v2.dto.requestDTO.ClassScheduleRequest;
import com.swp391.sportcentermanagementsystem_v2.dto.responseDTO.ClassScheduleResponse;
import com.swp391.sportcentermanagementsystem_v2.entity.ClassSchedule;
import com.swp391.sportcentermanagementsystem_v2.entity.FacilityRoom;
import com.swp391.sportcentermanagementsystem_v2.entity.SportClass;
import com.swp391.sportcentermanagementsystem_v2.entity.User;
import com.swp391.sportcentermanagementsystem_v2.exception.BusinessRuleException;
import com.swp391.sportcentermanagementsystem_v2.repository.ClassScheduleRepository;
import com.swp391.sportcentermanagementsystem_v2.repository.FacilityRoomRepository;
import com.swp391.sportcentermanagementsystem_v2.repository.SportClassRepository;
import com.swp391.sportcentermanagementsystem_v2.repository.UserRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;

import java.time.DayOfWeek;
import java.util.List;

@Service
@RequiredArgsConstructor
public class ClassScheduleService {

    private final ClassScheduleRepository scheduleRepository;
    private final SportClassRepository sportClassRepository;
    private final FacilityRoomRepository facilityRoomRepository;
    private final UserRepository userRepository;

    // 1. Manager tạo mới lịch hoạt động cho lớp học
    @Transactional
    public ClassScheduleResponse createSchedule(ClassScheduleRequest request) {
        SportClass sportClass = getAndValidateSportClass(request.getClassId());
        FacilityRoom room = getAndValidateRoom(request.getRoomId());

        // Kiểm tra toàn bộ quy tắc nghiệp vụ (Time, BR-CLS-01, BR-CLS-02)
        validateScheduleRules(sportClass, room, request, null);

        ClassSchedule schedule = new ClassSchedule();
        schedule.setSportClass(sportClass);
        schedule.setRoom(room);
        schedule.setDayOfWeek(request.getDayOfWeek());
        schedule.setStartTime(request.getStartTime());
        schedule.setEndTime(request.getEndTime());

        ClassSchedule saved = scheduleRepository.save(schedule);
        return toResponse(saved);
    }

    // 2. Manager cập nhật lịch hoạt động
    @Transactional
    public ClassScheduleResponse updateSchedule(Long id, ClassScheduleRequest request) {
        ClassSchedule schedule = scheduleRepository.findWithDetailsById(id)
                .orElseThrow(() -> new ResponseStatusException(
                        HttpStatus.NOT_FOUND, "Class schedule not found with ID: " + id));

        SportClass sportClass = getAndValidateSportClass(request.getClassId());
        FacilityRoom room = getAndValidateRoom(request.getRoomId());

        // Kiểm tra quy tắc nghiệp vụ (loại trừ chính bản ghi đang sửa qua id)
        validateScheduleRules(sportClass, room, request, id);

        schedule.setSportClass(sportClass);
        schedule.setRoom(room);
        schedule.setDayOfWeek(request.getDayOfWeek());
        schedule.setStartTime(request.getStartTime());
        schedule.setEndTime(request.getEndTime());

        ClassSchedule updated = scheduleRepository.save(schedule);
        return toResponse(updated);
    }

    // 3. Manager xóa lịch hoạt động
    @Transactional
    public void deleteSchedule(Long id) {
        ClassSchedule schedule = scheduleRepository.findById(id)
                .orElseThrow(() -> new ResponseStatusException(
                        HttpStatus.NOT_FOUND, "Class schedule not found with ID: " + id));
        scheduleRepository.delete(schedule);
    }

    // 4. Xem toàn bộ lịch hoạt động của trung tâm (Manager / Member / Coach /
    // Receptionist)
    @Transactional(readOnly = true)
    public List<ClassScheduleResponse> getAllSchedules() {
        return scheduleRepository.findAllByOrderByDayOfWeekAscStartTimeAsc()
                .stream()
                .map(this::toResponse)
                .toList();
    }

    // 5. Xem chi tiết 1 ca học theo ID
    @Transactional(readOnly = true)
    public ClassScheduleResponse getScheduleById(Long id) {
        ClassSchedule schedule = scheduleRepository.findWithDetailsById(id)
                .orElseThrow(() -> new ResponseStatusException(
                        HttpStatus.NOT_FOUND, "Class schedule not found with ID: " + id));
        return toResponse(schedule);
    }

    // 6. Xem danh sách lịch học của 1 lớp cụ thể
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

    // 7. Xem lịch hoạt động theo Thứ trong tuần (1 = Monday ... 7 = Sunday)
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

    // 8. Coach xem lịch dạy của chính mình
    @Transactional(readOnly = true)
    public List<ClassScheduleResponse> getMyCoachSchedules(String email) {
        User coach = userRepository.findByEmail(email)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "Coach not found!"));

        return scheduleRepository.findBySportClassCoachIdOrderByDayOfWeekAscStartTimeAsc(coach.getId())
                .stream()
                .map(this::toResponse)
                .toList();
    }

    // ===================== CÁC HÀM KIỂM TRA NGHIỆP VỤ =====================

    private SportClass getAndValidateSportClass(Long classId) {
        SportClass sportClass = sportClassRepository.findById(classId)
                .orElseThrow(() -> new ResponseStatusException(
                        HttpStatus.NOT_FOUND, "Sport class not found with ID: " + classId));

        if (!"ACTIVE".equalsIgnoreCase(sportClass.getStatus())) {
            throw new BusinessRuleException("Cannot schedule a class that is not ACTIVE!");
        }
        return sportClass;
    }

    private FacilityRoom getAndValidateRoom(Long roomId) {
        FacilityRoom room = facilityRoomRepository.findById(roomId)
                .orElseThrow(() -> new ResponseStatusException(
                        HttpStatus.NOT_FOUND, "Facility room not found with ID: " + roomId));

        if (!"AVAILABLE".equalsIgnoreCase(room.getMaintenanceStatus())) {
            throw new BusinessRuleException("Facility room is currently not AVAILABLE (Status: "
                    + room.getMaintenanceStatus() + ")!");
        }
        return room;
    }

    private void validateScheduleRules(SportClass sportClass, FacilityRoom room,
            ClassScheduleRequest request, Long excludeScheduleId) {
        // 1. Giờ kết thúc phải sau giờ bắt đầu
        if (!request.getEndTime().isAfter(request.getStartTime())) {
            throw new BusinessRuleException("End time must be after start time!");
        }

        // 2. BR-CLS-01: Sĩ số tối đa của lớp không được vượt quá sức chứa phòng tập
        if (sportClass.getMaxCapacity() > room.getCapacity()) {
            throw new BusinessRuleException(String.format(
                    "BR-CLS-01: Class max capacity (%d) exceeds room capacity (%d)!",
                    sportClass.getMaxCapacity(), room.getCapacity()));
        }

        // 3. BR-CLS-02a: Kiểm tra trùng phòng trong cùng Thứ và khung giờ
        boolean roomConflict = scheduleRepository.existsRoomOverlap(
                room.getId(),
                request.getDayOfWeek(),
                request.getStartTime(),
                request.getEndTime(),
                excludeScheduleId);
        if (roomConflict) {
            throw new BusinessRuleException(
                    "BR-CLS-02: This room already has another class scheduled during this time slot!");
        }

        // 4. BR-CLS-02b: Kiểm tra Huấn luyện viên có bị trùng giờ dạy lớp khác không
        boolean coachConflict = scheduleRepository.existsCoachOverlap(
                sportClass.getCoach().getId(),
                request.getDayOfWeek(),
                request.getStartTime(),
                request.getEndTime(),
                excludeScheduleId);
        if (coachConflict) {
            throw new BusinessRuleException(
                    "BR-CLS-02: Coach already has another class scheduled during this time slot!");
        }
    }

    private ClassScheduleResponse toResponse(ClassSchedule cs) {
        ClassScheduleResponse res = new ClassScheduleResponse();
        res.setId(cs.getId());

        SportClass sc = cs.getSportClass();
        res.setClassId(sc.getId());
        res.setClassName(sc.getName());
        res.setCategoryName(sc.getCategory().getName());
        res.setLevel(sc.getLevel());
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