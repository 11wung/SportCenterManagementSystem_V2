package com.swp391.sportcentermanagementsystem_v2.service;

import com.swp391.sportcentermanagementsystem_v2.dto.requestDTO.UpdateClassSessionRequest;
import com.swp391.sportcentermanagementsystem_v2.dto.responseDTO.ClassSessionResponse;
import com.swp391.sportcentermanagementsystem_v2.entity.ClassSchedule;
import com.swp391.sportcentermanagementsystem_v2.entity.ClassSession;
import com.swp391.sportcentermanagementsystem_v2.entity.FacilityRoom;
import com.swp391.sportcentermanagementsystem_v2.entity.SportClass;
import com.swp391.sportcentermanagementsystem_v2.exception.BusinessRuleException;
import com.swp391.sportcentermanagementsystem_v2.repository.ClassScheduleRepository;
import com.swp391.sportcentermanagementsystem_v2.repository.ClassSessionRepository;
import com.swp391.sportcentermanagementsystem_v2.repository.FacilityRoomRepository;
import com.swp391.sportcentermanagementsystem_v2.repository.SportClassRepository;
import com.swp391.sportcentermanagementsystem_v2.repository.UserRepository;
import com.swp391.sportcentermanagementsystem_v2.entity.User;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.LocalTime;
import java.util.ArrayList;
import java.util.List;

@Slf4j
@Service
@RequiredArgsConstructor
public class ClassSessionService {

    private static final int MAX_REPORTED_CONFLICTS = 10;

    private final ClassSessionRepository sessionRepository;
    private final ClassScheduleRepository scheduleRepository;
    private final SportClassRepository sportClassRepository;
    private final FacilityRoomRepository facilityRoomRepository;
    private final CoachSlotService coachSlotService;
    private final UserRepository userRepository;

    // BR-CLS-03: sinh các buổi học từ lịch tuần, đánh số từ ngày khai giảng.
    // Gọi lại thì xóa các buổi cũ và sinh lại (chỉ khi class còn nháp)
    @Transactional
    public List<ClassSessionResponse> generateSessions(Long classId, String managerEmail) {
        SportClass sportClass = sportClassRepository.findByIdForUpdate(classId)
                .orElseThrow(() -> new ResponseStatusException(
                        HttpStatus.NOT_FOUND, "Sport class not found with ID: " + classId));
        ClassScheduleService.requireDraft(sportClass);

        List<ClassSchedule> rules = scheduleRepository.findBySportClassIdOrderByDayOfWeekAscStartTimeAsc(classId);
        if (rules.isEmpty()) {
            throw new BusinessRuleException("Set the weekly schedule before generating sessions");
        }

        int total = sportClass.getCourse().getTotalSessions();
        List<ClassSession> sessions = new ArrayList<>();
        List<String> conflicts = new ArrayList<>();
        List<Long> openSlotIds = new ArrayList<>();

        // Đi từng ngày từ ngày khai giảng, mỗi ngày khớp thứ nào trong lịch thì sinh buổi đó
        LocalDate date = sportClass.getStartDate();
        while (sessions.size() < total) {
            for (ClassSchedule rule : rules) {
                if (sessions.size() == total) {
                    break;
                }
                if (rule.getDayOfWeek() != date.getDayOfWeek().getValue()) {
                    continue;
                }
                ClassSession session = new ClassSession();
                session.setSportClass(sportClass);
                session.setSessionNo(sessions.size() + 1);
                session.setSessionDate(date);
                session.setStartTime(rule.getStartTime());
                session.setEndTime(rule.getEndTime());
                session.setRoom(rule.getRoom());
                session.setCoach(sportClass.getCoach());
                sessions.add(session);

                String conflict = findConflict(session, classId, null);
                if (conflict == null) {
                    conflict = slotConflict(session, openSlotIds);
                }
                if (conflict != null) {
                    conflicts.add(conflict);
                }
            }
            date = date.plusDays(1);
        }

        if (!conflicts.isEmpty()) {
            throw new BusinessRuleException("Cannot generate sessions, "
                    + String.join("; ", conflicts.stream().limit(MAX_REPORTED_CONFLICTS).toList())
                    + (conflicts.size() > MAX_REPORTED_CONFLICTS
                            ? " (and " + (conflicts.size() - MAX_REPORTED_CONFLICTS) + " more)" : ""));
        }

        // Cách A: lớp học được ưu tiên, các suất 1-1 còn trống của coach trùng giờ bị hủy trước khi xếp buổi
        coachSlotService.cancelOpenSlotsForClass(openSlotIds.stream().distinct().toList(),
                sportClass.getCoach().getId(), findUser(managerEmail), classId);

        sessionRepository.deleteByClassId(classId);
        return sessionRepository.saveAll(sessions).stream().map(ClassSessionResponse::new).toList();
    }

    @Transactional(readOnly = true)
    public List<ClassSessionResponse> getSessionsByClass(Long classId) {
        if (!sportClassRepository.existsById(classId)) {
            throw new ResponseStatusException(HttpStatus.NOT_FOUND, "Sport class not found with ID: " + classId);
        }
        return sessionRepository.findBySportClassIdOrderBySessionNo(classId)
                .stream()
                .map(ClassSessionResponse::new)
                .toList();
    }

    // BR-CLS-03, BR-CLS-04: dời giờ hoặc đổi phòng một buổi. Nháp thì sửa tự do; class đã mở đăng ký
    // hay đang học thì chỉ dời / đổi được buổi chưa diễn ra, và học viên được thông báo
    @Transactional
    public ClassSessionResponse updateSession(Long sessionId, UpdateClassSessionRequest request, String managerEmail) {
        ClassSession session = sessionRepository.findWithDetailsById(sessionId)
                .orElseThrow(() -> new ResponseStatusException(
                        HttpStatus.NOT_FOUND, "Class session not found with ID: " + sessionId));

        SportClass sportClass = sportClassRepository.findByIdForUpdate(session.getSportClass().getId())
                .orElseThrow(() -> new ResponseStatusException(
                        HttpStatus.NOT_FOUND, "Sport class not found"));
        if (!List.of("DRAFT", "OPEN", "IN_PROGRESS").contains(sportClass.getStatus())) {
            throw new BusinessRuleException("Sessions of a " + sportClass.getStatus() + " class cannot be changed");
        }
        if (!"SCHEDULED".equals(session.getStatus())) {
            throw new BusinessRuleException("Only a SCHEDULED session can be changed (status: "
                    + session.getStatus() + ")");
        }

        if (!request.getEndTime().isAfter(request.getStartTime())) {
            throw new BusinessRuleException("End time must be after start time");
        }
        if (request.getSessionDate().isBefore(LocalDate.now(SportClassService.CENTER_ZONE))) {
            throw new BusinessRuleException("Session date cannot be in the past");
        }
        FacilityRoom room = facilityRoomRepository.findById(request.getRoomId())
                .orElseThrow(() -> new ResponseStatusException(
                        HttpStatus.BAD_REQUEST, "Facility room not found with ID: " + request.getRoomId()));
        ClassScheduleService.checkRoomFitsClass(room, sportClass);
        checkKeepsOrder(session, request);

        // Kiểm tra trên bản nháp chưa gắn vào session: nếu sửa session trước, Hibernate tự flush UPDATE
        // trước câu query kiểm tra và database sẽ báo lỗi chung chung thay vì câu mô tả rõ ràng
        ClassSession candidate = new ClassSession();
        candidate.setSessionNo(session.getSessionNo());
        candidate.setSessionDate(request.getSessionDate());
        candidate.setStartTime(request.getStartTime());
        candidate.setEndTime(request.getEndTime());
        candidate.setRoom(room);
        candidate.setCoach(session.getCoach());
        String conflict = findConflict(candidate, null, sessionId);
        List<Long> openSlotIds = new ArrayList<>();
        if (conflict == null) {
            conflict = slotConflict(candidate, openSlotIds);
        }
        if (conflict != null) {
            throw new BusinessRuleException("Cannot update session, " + conflict);
        }
        coachSlotService.cancelOpenSlotsForClass(openSlotIds, session.getCoach().getId(),
                findUser(managerEmail), sportClass.getId());

        LocalDate oldDate = session.getSessionDate();
        LocalTime oldStart = session.getStartTime();
        String oldRoom = session.getRoom().getRoomName();

        session.setSessionDate(request.getSessionDate());
        session.setStartTime(request.getStartTime());
        session.setEndTime(request.getEndTime());
        session.setRoom(room);

        // saveAndFlush để lỗi từ database (vd. trùng khung 1-1 của coach) nổ ngay trong hàm này
        ClassSession saved = sessionRepository.saveAndFlush(session);

        if (!"DRAFT".equals(sportClass.getStatus())) {
            // shortcut: chỉ ghi log, thay bằng NotificationService của Flow 1 khi có (BR-NTF-01)
            log.info("NOTIFY class {} session {}: {} {} in {} moved to {} {} in {}",
                    sportClass.getId(), saved.getSessionNo(), oldDate, oldStart, oldRoom,
                    saved.getSessionDate(), saved.getStartTime(), room.getRoomName());
        }
        return new ClassSessionResponse(saved);
    }

    // Số buổi là thứ tự học (buổi đầu, buổi cuối cho luật hoàn tiền), nên một buổi chỉ được dời
    // trong khoảng giữa buổi trước và buổi sau nó. Buổi đã hủy không tính vào thứ tự
    private void checkKeepsOrder(ClassSession session, UpdateClassSessionRequest request) {
        LocalDateTime newStart = LocalDateTime.of(request.getSessionDate(), request.getStartTime());
        LocalDateTime newEnd = LocalDateTime.of(request.getSessionDate(), request.getEndTime());
        int no = session.getSessionNo();

        List<ClassSession> active = sessionRepository.findBySportClassIdOrderBySessionNo(session.getSportClass().getId())
                .stream()
                .filter(s -> List.of("SCHEDULED", "DONE").contains(s.getStatus()))
                .toList();
        ClassSession prev = active.stream().filter(s -> s.getSessionNo() < no).reduce((a, b) -> b).orElse(null);
        ClassSession next = active.stream().filter(s -> s.getSessionNo() > no).findFirst().orElse(null);

        if (prev != null && !newStart.isAfter(LocalDateTime.of(prev.getSessionDate(), prev.getEndTime()))) {
            throw new BusinessRuleException("Session " + no + " must stay after session " + prev.getSessionNo()
                    + " (" + prev.getSessionDate() + " " + prev.getEndTime() + ")");
        }
        if (next != null && !newEnd.isBefore(LocalDateTime.of(next.getSessionDate(), next.getStartTime()))) {
            throw new BusinessRuleException("Session " + no + " must stay before session " + next.getSessionNo()
                    + " (" + next.getSessionDate() + " " + next.getStartTime() + ")");
        }
    }

    // Suất 1-1 của coach trùng giờ buổi học: đã có người đặt thì chặn, còn trống thì ghi lại để hủy (lớp học được ưu tiên)
    private String slotConflict(ClassSession s, List<Long> openSlotIds) {
        Long coachId = s.getCoach().getId();
        String blocker = coachSlotService.findBookedSlotBlocker(
                coachId, s.getSessionDate(), s.getStartTime(), s.getEndTime());
        if (blocker != null) {
            return "session " + (s.getSessionNo() != null ? s.getSessionNo() + " " : "") + "on " + s.getSessionDate()
                    + ": " + blocker;
        }
        openSlotIds.addAll(coachSlotService.findOpenSlotIds(
                coachId, s.getSessionDate(), s.getStartTime(), s.getEndTime()));
        return null;
    }

    private User findUser(String email) {
        return userRepository.findByEmail(email)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "User not found"));
    }

    // BR-SCH-01: kiểm tra trùng coach / phòng với các buổi khác (cách nhau dưới 15 phút cũng tính là trùng).
    // Trả về câu mô tả xung đột, hoặc null nếu không trùng.
    // shortcut: LocalTime không vòng qua nửa đêm nên buổi sát 00:00 có thể lọt, exclusion constraint của DB vẫn chặn ở bước lưu
    private String findConflict(ClassSession s, Long excludeClassId, Long excludeSessionId) {
        LocalTime startMinus = s.getStartTime().minusMinutes(ClassScheduleService.GAP_MINUTES);
        LocalTime endPlus = s.getEndTime().plusMinutes(ClassScheduleService.GAP_MINUTES);
        String when = "session " + (s.getSessionNo() != null ? s.getSessionNo() + " " : "") + "on " + s.getSessionDate();

        if (sessionRepository.existsCoachConflict(s.getCoach().getId(), s.getSessionDate(),
                startMinus, endPlus, excludeClassId, excludeSessionId)) {
            return when + " overlaps another session of the coach (15-minute gap required)";
        }
        if (sessionRepository.existsRoomConflict(s.getRoom().getId(), s.getSessionDate(),
                startMinus, endPlus, excludeClassId, excludeSessionId)) {
            return when + " overlaps another session in room " + s.getRoom().getRoomName()
                    + " (15-minute gap required)";
        }
        return null;
    }
}
