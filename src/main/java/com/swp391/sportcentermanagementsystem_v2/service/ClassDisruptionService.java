package com.swp391.sportcentermanagementsystem_v2.service;

import com.swp391.sportcentermanagementsystem_v2.dto.responseDTO.ClassSessionResponse;
import com.swp391.sportcentermanagementsystem_v2.dto.responseDTO.MakeupProposalResponse;
import com.swp391.sportcentermanagementsystem_v2.dto.responseDTO.RoomMaintenanceResponse;
import com.swp391.sportcentermanagementsystem_v2.entity.ClassRegistration;
import com.swp391.sportcentermanagementsystem_v2.entity.ClassSchedule;
import com.swp391.sportcentermanagementsystem_v2.entity.ClassSession;
import com.swp391.sportcentermanagementsystem_v2.entity.FacilityRoom;
import com.swp391.sportcentermanagementsystem_v2.entity.SportClass;
import com.swp391.sportcentermanagementsystem_v2.entity.User;
import com.swp391.sportcentermanagementsystem_v2.exception.BusinessRuleException;
import com.swp391.sportcentermanagementsystem_v2.repository.ClassRegistrationRepository;
import com.swp391.sportcentermanagementsystem_v2.repository.ClassScheduleRepository;
import com.swp391.sportcentermanagementsystem_v2.repository.ClassSessionRepository;
import com.swp391.sportcentermanagementsystem_v2.repository.FacilityRoomRepository;
import com.swp391.sportcentermanagementsystem_v2.repository.SportClassRepository;
import com.swp391.sportcentermanagementsystem_v2.repository.UserRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.TreeSet;

// BR-MKP, BR-SCH-05: buổi học bị hủy do lỗi trung tâm (coach bận, phòng hỏng) và việc xếp buổi bù.
// Phần tiền (hoàn một buổi, rời lớp) nằm ở SessionRefundService
@Slf4j
@Service
@RequiredArgsConstructor
public class ClassDisruptionService {

    private static final List<String> LIVE_CLASS_STATUSES = List.of("OPEN", "IN_PROGRESS");
    private static final int MAX_REPORTED = 10;

    private final ClassSessionRepository sessionRepository;
    private final ClassScheduleRepository scheduleRepository;
    private final SportClassRepository sportClassRepository;
    private final ClassRegistrationRepository registrationRepository;
    private final FacilityRoomRepository facilityRoomRepository;
    private final UserRepository userRepository;
    private final ClassSessionService classSessionService;
    private final CoachSlotService coachSlotService;
    private final SystemSettingService settings;
    private final AuditService auditService;

    // ====================== HỦY BUỔI ======================

    // BR-MKP-01, BR-MKP-04: manager hủy một buổi do lỗi trung tâm. Học viên không bị tính vắng
    @Transactional
    public ClassSessionResponse cancelSession(Long sessionId, String reason, String managerEmail) {
        User manager = findUser(managerEmail);
        ClassSession session = loadSession(sessionId);
        lockLiveClass(session.getSportClass().getId());
        cancelOne(session, reason, manager);
        return new ClassSessionResponse(session);
    }

    // BR-MKP-05: coach nghỉ tạm, mọi buổi sắp tới của coach trong khoảng ngày bị hủy do trung tâm rồi xếp bù như thường
    @Transactional
    public List<ClassSessionResponse> coachLeave(Long coachId, LocalDate from, LocalDate to, String reason,
            String managerEmail) {
        User manager = findUser(managerEmail);
        User coach = userRepository.findById(coachId)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "Coach not found with ID: " + coachId));
        if (coach.getRole() == null || !"COACH".equals(coach.getRole().getCode())) {
            throw new BusinessRuleException("This user is not a coach");
        }
        if (to.isBefore(from)) {
            throw new BusinessRuleException("The end date must not be before the start date");
        }

        LocalDate today = LocalDate.now(SportClassService.CENTER_ZONE);
        List<ClassSession> sessions = sessionRepository.findCoachUpcoming(coachId, from.isBefore(today) ? today : from, to)
                .stream().filter(s -> !hasEnded(s)).toList();

        lockClasses(sessions);
        for (ClassSession s : sessions) {
            cancelOne(s, reason, manager);
        }
        auditService.log(manager, "COACH_LEAVE", "user", coachId, Map.of(
                "from", from.toString(), "to", to.toString(), "reason", reason, "canceledSessions", sessions.size()));
        return sessions.stream().map(ClassSessionResponse::new).toList();
    }

    // ====================== ĐỔI PHÒNG KHI BẢO TRÌ ======================

    // BR-SCH-05: phòng chuyển sang bảo trì hoặc đóng cửa thì các buổi chưa diễn ra trong phòng được đổi sang phòng khác
    // cùng bộ môn, đủ sức chứa, không trùng giờ. Không có phòng thay thì buổi đó bị hủy do trung tâm
    @Transactional
    public RoomMaintenanceResponse setRoomStatus(Long roomId, String status, String managerEmail) {
        User manager = findUser(managerEmail);
        FacilityRoom room = facilityRoomRepository.findById(roomId)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "Facility room not found with ID: " + roomId));
        String previous = room.getMaintenanceStatus();
        room.setMaintenanceStatus(status);
        facilityRoomRepository.saveAndFlush(room);

        List<String> moved = new ArrayList<>();
        List<String> canceled = new ArrayList<>();
        if (!"AVAILABLE".equals(status)) {
            List<ClassSession> sessions = sessionRepository
                    .findRoomUpcoming(roomId, LocalDate.now(SportClassService.CENTER_ZONE))
                    .stream().filter(s -> !hasEnded(s)).toList();
            lockClasses(sessions);
            for (ClassSession s : sessions) {
                String label = s.getSportClass().getName() + " #" + s.getSessionNo() + " on " + s.getSessionDate();
                FacilityRoom replacement = findReplacementRoom(s, roomId);
                if (replacement != null) {
                    s.setRoom(replacement);
                    sessionRepository.saveAndFlush(s);
                    moved.add(label + " -> " + replacement.getRoomName());
                    // shortcut: chỉ ghi log, thay bằng NotificationService của Flow 1 khi có (BR-NTF-01)
                    log.info("NOTIFY class {}: session {} on {} moved from room {} to room {}", s.getSportClass().getId(),
                            s.getSessionNo(), s.getSessionDate(), room.getRoomName(), replacement.getRoomName());
                } else {
                    cancelOne(s, "Room " + room.getRoomName() + " is unavailable and no replacement room was found", manager);
                    canceled.add(label);
                }
            }
        }
        auditService.log(manager, "ROOM_STATUS_CHANGED", "facility_room", roomId, Map.of(
                "from", previous, "to", status, "movedSessions", moved.size(), "canceledSessions", canceled.size()));
        return new RoomMaintenanceResponse(roomId, status, moved, canceled);
    }

    private FacilityRoom findReplacementRoom(ClassSession s, Long excludeRoomId) {
        int gap = ClassScheduleService.GAP_MINUTES;
        for (FacilityRoom candidate : facilityRoomRepository.findAll().stream()
                .filter(r -> !r.getId().equals(excludeRoomId))
                .sorted(Comparator.comparing(FacilityRoom::getId)).toList()) {
            try {
                ClassScheduleService.checkRoomFitsClass(candidate, s.getSportClass());
            } catch (BusinessRuleException ex) {
                continue;
            }
            if (!sessionRepository.existsRoomConflict(candidate.getId(), s.getSessionDate(),
                    s.getStartTime().minusMinutes(gap), s.getEndTime().plusMinutes(gap), null, s.getId())) {
                return candidate;
            }
        }
        return null;
    }

    // ====================== BUỔI BÙ ======================

    // BR-MKP-01, BR-MKP-02: tối đa N ngày bù gần nhất sau buổi cuối của class, kèm số học viên bị trùng giờ
    @Transactional(readOnly = true)
    public List<MakeupProposalResponse> proposeMakeup(Long sessionId) {
        ClassSession canceled = loadCanceledSession(sessionId);
        SportClass sportClass = sportClassRepository.findById(canceled.getSportClass().getId())
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "Sport class not found"));
        requireLive(sportClass);

        int wanted = settings.getInt("makeup.proposal_count");
        int buffer = settings.getInt("schedule.buffer_minutes");
        List<ClassSchedule> rules = scheduleRepository.findBySportClassIdOrderByDayOfWeekAscStartTimeAsc(sportClass.getId());
        List<MakeupProposalResponse> proposals = new ArrayList<>();

        LocalDate last = lastMakeupDate(canceled);
        for (LocalDate date = firstMakeupDate(sportClass.getId());
                !date.isAfter(last) && proposals.size() < wanted; date = date.plusDays(1)) {
            for (ClassSchedule rule : rulesOn(rules, date)) {
                if (proposals.size() < wanted && makeupProblem(sportClass, rule, date) == null) {
                    proposals.add(new MakeupProposalResponse(date, rule.getStartTime(), rule.getEndTime(),
                            rule.getRoom().getId(), rule.getRoom().getRoomName(),
                            sessionRepository.countMembersBusy(sportClass.getId(), date,
                                    rule.getStartTime(), rule.getEndTime(), buffer)));
                }
            }
        }
        return proposals;
    }

    // BR-MKP-02: manager chọn ngày thì hệ thống mới tạo buổi bù. Ngày chọn được kiểm lại bằng đúng các luật của đề xuất
    @Transactional
    public ClassSessionResponse chooseMakeup(Long sessionId, LocalDate date, String managerEmail) {
        User manager = findUser(managerEmail);
        ClassSession canceled = loadCanceledSession(sessionId);
        SportClass sportClass = lockLiveClass(canceled.getSportClass().getId());
        Long classId = sportClass.getId();

        if (date.isBefore(firstMakeupDate(classId))) {
            throw new BusinessRuleException("A makeup session must be after the last session of the class ("
                    + firstMakeupDate(classId).minusDays(1) + ") and not in the past");
        }
        if (date.isAfter(lastMakeupDate(canceled))) {
            throw new BusinessRuleException("The makeup date is more than " + settings.getInt("makeup.max_days")
                    + " days after the canceled session, refund the session instead");
        }
        if (sessionRepository.countBySportClassIdAndMakeupTrueAndStatusIn(classId, List.of("SCHEDULED", "DONE"))
                >= sessionRepository.countBySportClassIdAndStatusIn(classId, List.of("CANCELED_BY_CENTER"))) {
            throw new BusinessRuleException("Every canceled session of this class already has a makeup session");
        }

        List<ClassSchedule> rules = rulesOn(scheduleRepository.findBySportClassIdOrderByDayOfWeekAscStartTimeAsc(classId), date);
        if (rules.isEmpty()) {
            throw new BusinessRuleException("The date must fall on a weekday of the class weekly schedule");
        }
        ClassSchedule chosen = null;
        String firstProblem = null;
        for (ClassSchedule rule : rules) {
            String problem = makeupProblem(sportClass, rule, date);
            if (problem == null) {
                chosen = rule;
                break;
            }
            if (firstProblem == null) {
                firstProblem = problem;
            }
        }
        if (chosen == null) {
            throw new BusinessRuleException("Cannot schedule the makeup session, " + firstProblem);
        }

        ClassSession makeup = new ClassSession();
        makeup.setSportClass(sportClass);
        makeup.setSessionNo(sessionRepository.findMaxSessionNo(classId) + 1);
        makeup.setSessionDate(date);
        makeup.setStartTime(chosen.getStartTime());
        makeup.setEndTime(chosen.getEndTime());
        makeup.setRoom(chosen.getRoom());
        makeup.setCoach(sportClass.getCoach());
        makeup.setMakeup(true);

        // Lớp học thắng suất 1-1 còn trống: hủy các suất trống của coach trùng giờ trước khi xếp buổi
        List<Long> openSlotIds = new ArrayList<>();
        classSessionService.slotConflict(makeup, openSlotIds);
        coachSlotService.cancelOpenSlotsForClass(openSlotIds.stream().distinct().toList(),
                sportClass.getCoach().getId(), manager, classId);

        ClassSession saved = sessionRepository.saveAndFlush(makeup);
        auditService.log(manager, "MAKEUP_SESSION_CHOSEN", "class_session", saved.getId(), Map.of(
                "classId", classId, "replacesSessionNo", canceled.getSessionNo(), "date", date.toString()));
        notifyClass(classId, "makeup session " + saved.getSessionNo() + " is on " + date + " "
                + saved.getStartTime() + " in room " + saved.getRoom().getRoomName());
        return new ClassSessionResponse(saved);
    }

    // Lý do ngày này không xếp bù được (phòng không dùng được, trùng coach hoặc phòng, có 1-1 đã đặt). Null nếu xếp được
    private String makeupProblem(SportClass sportClass, ClassSchedule rule, LocalDate date) {
        try {
            ClassScheduleService.checkRoomFitsClass(rule.getRoom(), sportClass);
        } catch (BusinessRuleException ex) {
            return ex.getMessage();
        }
        ClassSession draft = new ClassSession();
        draft.setSportClass(sportClass);
        draft.setSessionDate(date);
        draft.setStartTime(rule.getStartTime());
        draft.setEndTime(rule.getEndTime());
        draft.setRoom(rule.getRoom());
        draft.setCoach(sportClass.getCoach());
        String conflict = classSessionService.findConflict(draft, null, null);
        return conflict != null ? conflict : classSessionService.slotConflict(draft, new ArrayList<>());
    }

    // Ngày bù sớm nhất: sau buổi cuối còn hiệu lực của class và không ở quá khứ
    private LocalDate firstMakeupDate(Long classId) {
        LocalDate today = LocalDate.now(SportClassService.CENTER_ZONE);
        LocalDate last = sessionRepository.findLastActiveDate(classId).orElse(today.minusDays(1));
        return (last.isBefore(today) ? today.minusDays(1) : last).plusDays(1);
    }

    // BR-MKP-06: ngày bù muộn nhất, quá hạn này thì hoàn tiền thay vì bù
    private LocalDate lastMakeupDate(ClassSession canceled) {
        return canceled.getSessionDate().plusDays(settings.getInt("makeup.max_days"));
    }

    private List<ClassSchedule> rulesOn(List<ClassSchedule> rules, LocalDate date) {
        return rules.stream().filter(r -> r.getDayOfWeek() == date.getDayOfWeek().getValue()).toList();
    }

    // ====================== HELPERS ======================

    // Hủy một buổi do trung tâm: ghi lý do, nhật ký, báo cả lớp. Caller đã khóa class
    private void cancelOne(ClassSession session, String reason, User manager) {
        if (!"SCHEDULED".equals(session.getStatus())) {
            throw new BusinessRuleException("Only a SCHEDULED session can be canceled by the center (status: "
                    + session.getStatus() + ")");
        }
        if (hasEnded(session)) {
            throw new BusinessRuleException("The session has already taken place");
        }
        session.setStatus("CANCELED_BY_CENTER");
        session.setCancelReason(reason.length() > 255 ? reason.substring(0, 255) : reason);
        sessionRepository.saveAndFlush(session);

        auditService.log(manager, "CLASS_SESSION_CANCELED", "class_session", session.getId(), Map.of(
                "classId", session.getSportClass().getId(), "sessionNo", session.getSessionNo(), "reason", reason));
        notifyClass(session.getSportClass().getId(), "session " + session.getSessionNo() + " on "
                + session.getSessionDate() + " was canceled by the center (" + reason + ")");
    }

    private boolean hasEnded(ClassSession s) {
        return !LocalDateTime.of(s.getSessionDate(), s.getEndTime()).isAfter(LocalDateTime.now(SportClassService.CENTER_ZONE));
    }

    // Khóa class (sắp theo id để hai thao tác hàng loạt không khóa ngược thứ tự nhau)
    private void lockClasses(List<ClassSession> sessions) {
        new TreeSet<>(sessions.stream().map(s -> s.getSportClass().getId()).toList())
                .forEach(this::lockLiveClass);
    }

    private SportClass lockLiveClass(Long classId) {
        SportClass sportClass = sportClassRepository.findByIdForUpdate(classId)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "Sport class not found"));
        requireLive(sportClass);
        return sportClass;
    }

    private void requireLive(SportClass sportClass) {
        if (!LIVE_CLASS_STATUSES.contains(sportClass.getStatus())) {
            throw new BusinessRuleException("Only sessions of an OPEN or IN_PROGRESS class can be handled here (class status: "
                    + sportClass.getStatus() + ")");
        }
    }

    private ClassSession loadSession(Long sessionId) {
        return sessionRepository.findWithDetailsById(sessionId)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "Class session not found with ID: " + sessionId));
    }

    private ClassSession loadCanceledSession(Long sessionId) {
        ClassSession session = loadSession(sessionId);
        if (!"CANCELED_BY_CENTER".equals(session.getStatus())) {
            throw new BusinessRuleException("Only a session canceled by the center needs a makeup (status: "
                    + session.getStatus() + ")");
        }
        return session;
    }

    private User findUser(String email) {
        return userRepository.findByEmail(email)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "User not found"));
    }

    private void notifyClass(Long classId, String message) {
        List<ClassRegistration> members = registrationRepository.findBySportClass_IdAndStatusInOrderById(classId,
                List.of("REGISTERED", "PENDING_PAYMENT"));
        // shortcut: chỉ ghi log, thay bằng NotificationService của Flow 1 khi có (BR-NTF-01)
        members.stream().limit(MAX_REPORTED).forEach(r -> log.info("NOTIFY user {}: class {}: {}",
                r.getUser().getId(), classId, message));
    }
}
