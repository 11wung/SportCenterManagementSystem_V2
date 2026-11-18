package com.swp391.sportcentermanagementsystem_v2.service;

import com.swp391.sportcentermanagementsystem_v2.dto.responseDTO.ClassSessionResponse;
import com.swp391.sportcentermanagementsystem_v2.dto.responseDTO.MakeupProposalResponse;
import com.swp391.sportcentermanagementsystem_v2.dto.responseDTO.RoomMaintenanceResponse;
import com.swp391.sportcentermanagementsystem_v2.entity.ClassSchedule;
import com.swp391.sportcentermanagementsystem_v2.entity.ClassSession;
import com.swp391.sportcentermanagementsystem_v2.entity.FacilityRoom;
import com.swp391.sportcentermanagementsystem_v2.entity.SportClass;
import com.swp391.sportcentermanagementsystem_v2.entity.TimeSlot;
import com.swp391.sportcentermanagementsystem_v2.entity.User;
import com.swp391.sportcentermanagementsystem_v2.exception.BusinessRuleException;
import com.swp391.sportcentermanagementsystem_v2.repository.ClassRegistrationRepository;
import com.swp391.sportcentermanagementsystem_v2.repository.ClassScheduleRepository;
import com.swp391.sportcentermanagementsystem_v2.repository.ClassSessionRepository;
import com.swp391.sportcentermanagementsystem_v2.repository.FacilityRoomRepository;
import com.swp391.sportcentermanagementsystem_v2.repository.SportClassRepository;
import com.swp391.sportcentermanagementsystem_v2.repository.TimeSlotRepository;
import com.swp391.sportcentermanagementsystem_v2.repository.UserRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;

import java.time.LocalDate;
import java.time.Duration;
import java.time.LocalDateTime;
import java.time.LocalTime;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeSet;

// BR-MKP, BR-SCH-05: buổi học bị hủy do lỗi trung tâm (coach bận, phòng hỏng) và việc xếp buổi bù.
// Phần tiền (hoàn một buổi, rời lớp) nằm ở SessionRefundService
@Slf4j
@Service
@RequiredArgsConstructor
public class ClassDisruptionService {
    private static final java.time.format.DateTimeFormatter DAY = java.time.format.DateTimeFormatter.ofPattern("dd/MM/yyyy");


    private static final List<String> LIVE_CLASS_STATUSES = List.of("OPEN", "IN_PROGRESS");

    private final ClassSessionRepository sessionRepository;
    private final ClassScheduleRepository scheduleRepository;
    private final SportClassRepository sportClassRepository;
    private final ClassRegistrationRepository registrationRepository;
    private final FacilityRoomRepository facilityRoomRepository;
    private final TimeSlotRepository timeSlotRepository;
    private final UserRepository userRepository;
    private final ClassSessionService classSessionService;
    private final CoachSlotService coachSlotService;
    private final SystemSettingService settings;
    private final AuditService auditService;
    private final NotificationService notificationService;

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
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "Không tìm thấy coach có mã " + coachId));
        if (coach.getRole() == null || !"COACH".equals(coach.getRole().getCode())) {
            throw new BusinessRuleException("Người dùng này không phải coach");
        }
        if (to.isBefore(from)) {
            throw new BusinessRuleException("Ngày kết thúc không được trước ngày bắt đầu");
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
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "Không tìm thấy phòng có mã " + roomId));
        if (room.getArchivedAt() != null) {
            throw new BusinessRuleException("Phòng này đã ngừng sử dụng, hãy bấm \"Dùng lại\" trước khi đổi trạng thái");
        }
        String previous = room.getMaintenanceStatus();
        room.setMaintenanceStatus(status);
        facilityRoomRepository.saveAndFlush(room);

        List<String> moved = new ArrayList<>();
        List<String> canceled = new ArrayList<>();
        List<String> updatedSchedules = new ArrayList<>();
        List<String> unfixedSchedules = new ArrayList<>();
        if (!"AVAILABLE".equals(status)) {
            List<ClassSession> sessions = sessionRepository
                    .findRoomUpcoming(roomId, LocalDate.now(SportClassService.CENTER_ZONE))
                    .stream().filter(s -> !hasEnded(s)).toList();
            lockClasses(sessions);
            List<ClassSession> movedSessions = new ArrayList<>();
            for (ClassSession s : sessions) {
                String label = s.getSportClass().getName() + " #" + s.getSessionNo() + " ngày " + s.getSessionDate();
                FacilityRoom replacement = findReplacementRoom(s, roomId);
                if (replacement != null) {
                    s.setRoom(replacement);
                    sessionRepository.saveAndFlush(s);
                    moved.add(label + " -> " + replacement.getRoomName());
                    movedSessions.add(s);
                    notificationService.sendToClass(s.getSportClass().getId(), "ANNOUNCEMENT", "Buổi học được chuyển sang phòng khác",
                            "Lớp " + s.getSportClass().getName() + ": buổi " + s.getSessionNo() + " ngày " + DisplayFormat.day(s.getSessionDate()) + " được chuyển từ phòng "
                                    + room.getRoomName() + " sang phòng " + replacement.getRoomName() + ".");
                } else {
                    cancelOne(s, "phòng " + room.getRoomName() + " không dùng được và chưa tìm được phòng thay", manager);
                    canceled.add(label);
                }
            }
            fixWeeklySchedules(roomId, movedSessions, updatedSchedules, unfixedSchedules);
        }
        auditService.log(manager, "ROOM_STATUS_CHANGED", "facility_room", roomId, Map.of(
                "from", previous, "to", status, "movedSessions", moved.size(), "canceledSessions", canceled.size()));
        return new RoomMaintenanceResponse(roomId, status, moved, canceled, updatedSchedules, unfixedSchedules);
    }

    // Lịch tuần của class chưa có ghi danh (thường là nháp) còn trỏ vào phòng không dùng được thì chuyển sang phòng mới, nếu
    // không việc sinh lại buổi sau này sẽ tìm đúng phòng hỏng. Ưu tiên phòng mà các buổi cùng thứ và giờ của lớp đã được
    // chuyển tới; chưa có buổi nào để so thì lấy phòng đầu tiên phù hợp. Không có phòng nào thì báo lại cho manager
    private void fixWeeklySchedules(Long roomId, List<ClassSession> movedSessions, List<String> updated, List<String> unfixed) {
        for (ClassSchedule rule : scheduleRepository.findByRoom_IdAndSportClass_StatusIn(roomId,
                List.of("DRAFT", "OPEN", "IN_PROGRESS"))) {
            String label = rule.getSportClass().getName() + " (day " + rule.getDayOfWeek() + " " + rule.getStartTime() + ")";
            // BR-CLS-04: lớp đã có ghi danh thì lịch tuần bị khóa (database chặn sửa). Buổi bù sau này tự chọn phòng khác
            if (registrationRepository.countBySportClass_IdAndStatusIn(rule.getSportClass().getId(),
                    List.of("PENDING_PAYMENT", "REGISTERED")) > 0) {
                unfixed.add(label + ": lịch tuần đã bị khóa vì đã có ghi danh, buổi học bù sẽ chọn phòng trống khác");
                continue;
            }
            FacilityRoom target = movedSessions.stream()
                    .filter(s -> s.getSportClass().getId().equals(rule.getSportClass().getId())
                            && s.getSessionDate().getDayOfWeek().getValue() == rule.getDayOfWeek()
                            && s.getStartTime().equals(rule.getStartTime()))
                    .collect(java.util.stream.Collectors.groupingBy(ClassSession::getRoom, java.util.stream.Collectors.counting()))
                    .entrySet().stream().max(Map.Entry.comparingByValue()).map(Map.Entry::getKey)
                    .orElseGet(() -> firstFittingRoom(rule, roomId));
            if (target == null) {
                unfixed.add(label);
                continue;
            }
            rule.setRoom(target);
            scheduleRepository.saveAndFlush(rule);
            updated.add(label + " -> " + target.getRoomName());
        }
    }

    private FacilityRoom firstFittingRoom(ClassSchedule rule, Long excludeRoomId) {
        for (FacilityRoom candidate : facilityRoomRepository.findAll().stream()
                .filter(r -> !r.getId().equals(excludeRoomId))
                .sorted(Comparator.comparing(FacilityRoom::getId)).toList()) {
            try {
                ClassScheduleService.checkRoomFitsClass(candidate, rule.getSportClass());
                return candidate;
            } catch (BusinessRuleException ex) {
                // phòng này không phù hợp, xét phòng kế tiếp
            }
        }
        return null;
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

    // BR-MKP-01, BR-MKP-02: ưu tiên xếp bù trong khóa. Xét mọi ngày từ (hôm nay + makeup.min_notice_days) đến buổi cuối của
    // lớp, mọi khung giờ của trung tâm; chỉ nhận chỗ làm không quá makeup.max_busy_percent lớp bị trùng lịch. Nếu chỗ tốt
    // nhất trong khóa vẫn có người bị trùng, hoặc trong khóa không còn chỗ, kèm thêm một chỗ sau buổi cuối của lớp để
    // manager so sánh (chỉ khi chỗ đó ít người trùng hơn)
    @Transactional(readOnly = true)
    public List<MakeupProposalResponse> proposeMakeup(Long sessionId) {
        ClassSession canceled = loadCanceledSession(sessionId);
        SportClass sportClass = sportClassRepository.findById(canceled.getSportClass().getId())
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "Không tìm thấy lớp"));
        requireLive(sportClass);

        int wanted = settings.getInt("makeup.proposal_count");
        MakeupWindow window = makeupWindow(canceled);
        LocalDate inCourseTo = window.courseEnd().isBefore(window.latest()) ? window.courseEnd() : window.latest();
        List<Candidate> inCourse = findCandidates(sportClass, canceled, window.earliest(), inCourseTo, wanted);

        List<MakeupProposalResponse> proposals = new ArrayList<>();
        inCourse.forEach(c -> proposals.add(toProposal(c, false)));

        LocalDate afterFrom = window.courseEnd().plusDays(1).isAfter(window.earliest())
                ? window.courseEnd().plusDays(1) : window.earliest();
        if (inCourse.isEmpty() || inCourse.get(0).busy() > 0) {
            // Trong khóa không còn chỗ thì đề xuất đủ số ngày sau khóa; còn chỗ thì chỉ thêm một chỗ để so sánh
            List<Candidate> after = findCandidates(sportClass, canceled, afterFrom, window.latest(),
                    inCourse.isEmpty() ? wanted : 1);
            if (inCourse.isEmpty()) {
                after.forEach(c -> proposals.add(toProposal(c, true)));
            } else if (!after.isEmpty() && after.get(0).busy() < inCourse.get(0).busy()) {
                proposals.add(toProposal(after.get(0), true));
            }
        }
        return proposals;
    }

    // BR-MKP-02: manager chọn ngày và giờ thì hệ thống mới tạo buổi bù. Chỗ được chọn kiểm lại bằng đúng các luật của đề xuất
    @Transactional
    public ClassSessionResponse chooseMakeup(Long sessionId, LocalDate date, LocalTime startTime, String managerEmail) {
        User manager = findUser(managerEmail);
        ClassSession canceled = loadCanceledSession(sessionId);
        SportClass sportClass = lockLiveClass(canceled.getSportClass().getId());
        Long classId = sportClass.getId();
        MakeupWindow window = makeupWindow(canceled);

        if (date.isBefore(window.earliest())) {
            throw new BusinessRuleException("Buổi học bù phải báo trước ít nhất " + settings.getInt("makeup.min_notice_days", 2)
                    + " ngày, sớm nhất là ngày " + DisplayFormat.day(window.earliest()));
        }
        if (date.isAfter(window.latest())) {
            throw new BusinessRuleException("Ngày học bù cách quá " + settings.getInt("makeup.max_days")
                    + " ngày sau buổi bị hủy, hãy hoàn tiền buổi đó thay vì học bù");
        }
        if (sessionRepository.findActiveDates(classId).contains(date)) {
            throw new BusinessRuleException("Lớp đã có buổi học vào ngày " + DisplayFormat.day(date) + ", hãy chọn ngày khác");
        }
        TimeSlot slot = slotsFor(canceled).stream().filter(s -> s.getStartTime().equals(startTime)).findFirst()
                .orElseThrow(() -> new BusinessRuleException("Giờ này không phải khung giờ của trung tâm"));
        if (sessionRepository.countBySportClassIdAndMakeupTrueAndStatusIn(classId, List.of("SCHEDULED", "DONE"))
                >= sessionRepository.countBySportClassIdAndStatusIn(classId, List.of("CANCELED_BY_CENTER"))) {
            throw new BusinessRuleException("Mọi buổi bị hủy của lớp này đã có buổi học bù");
        }

        MakeupPlan plan = planMakeup(sportClass, canceled.getRoom(), date, slot.getStartTime(), slot.getEndTime());
        if (plan.problem() != null) {
            throw new BusinessRuleException("Không thể xếp buổi học bù: " + plan.problem());
        }
        long busy = busyMembers(sportClass, date, slot);
        if (tooManyBusy(busy, sportClass.getId())) {
            throw new BusinessRuleException("Có " + busy + " học viên bị trùng lịch vào giờ này, quá "
                    + settings.getInt("makeup.max_busy_percent", 50) + "% lớp, hãy chọn ngày giờ khác");
        }

        ClassSession makeup = new ClassSession();
        makeup.setSportClass(sportClass);
        makeup.setSessionNo(sessionRepository.findMaxSessionNo(classId) + 1);
        makeup.setSessionDate(date);
        makeup.setStartTime(slot.getStartTime());
        makeup.setEndTime(slot.getEndTime());
        makeup.setRoom(plan.room());
        makeup.setCoach(sportClass.getCoach());
        makeup.setMakeup(true);

        ClassSession saved = sessionRepository.saveAndFlush(makeup);
        auditService.log(manager, "MAKEUP_SESSION_CHOSEN", "class_session", saved.getId(), Map.of(
                "classId", classId, "replacesSessionNo", canceled.getSessionNo(), "date", date.toString()));
        notificationService.sendToClass(classId, "MAKEUP", "Có lịch học bù", "Lớp " + saved.getSportClass().getName() + " có buổi học bù " + saved.getSessionNo() + " lúc " + saved.getStartTime() + " ngày " + DisplayFormat.day(date) + " ở phòng " + saved.getRoom().getRoomName() + ".");
        return new ClassSessionResponse(saved);
    }

    private record MakeupPlan(FacilityRoom room, String problem) {
    }

    // Một chỗ bù hợp lệ: ngày, khung giờ, phòng, số học viên bị trùng lịch
    private record Candidate(LocalDate date, TimeSlot slot, FacilityRoom room, long busy) {
    }

    // Khoảng ngày được phép bù: earliest (báo trước), courseEnd (buổi cuối còn hiệu lực của lớp), latest (BR-MKP-06, quá
    // hạn này thì hoàn tiền thay vì bù)
    private record MakeupWindow(LocalDate earliest, LocalDate courseEnd, LocalDate latest) {
    }

    private MakeupWindow makeupWindow(ClassSession canceled) {
        LocalDate today = LocalDate.now(SportClassService.CENTER_ZONE);
        LocalDate courseEnd = sessionRepository.findLastActiveDate(canceled.getSportClass().getId())
                .orElse(today.minusDays(1));
        return new MakeupWindow(today.plusDays(settings.getInt("makeup.min_notice_days", 2)), courseEnd,
                canceled.getSessionDate().plusDays(settings.getInt("makeup.max_days")));
    }

    // Khung giờ đang mở của trung tâm có độ dài bằng buổi bị hủy
    private List<TimeSlot> slotsFor(ClassSession canceled) {
        Duration length = Duration.between(canceled.getStartTime(), canceled.getEndTime());
        return timeSlotRepository.findByActiveTrueOrderByStartTime().stream()
                .filter(s -> Duration.between(s.getStartTime(), s.getEndTime()).equals(length)).toList();
    }

    // Tối đa `limit` chỗ tốt nhất trong [from, to]: ít học viên trùng lịch trước, rồi ngày sớm hơn. Dừng sớm khi đã đủ
    // `limit` chỗ không ai bị trùng (các ngày sau chỉ xếp sau những chỗ này)
    private List<Candidate> findCandidates(SportClass sportClass, ClassSession canceled, LocalDate from, LocalDate to,
            int limit) {
        List<Candidate> found = new ArrayList<>();
        if (from.isAfter(to)) {
            return found;
        }
        Set<LocalDate> taken = new HashSet<>(sessionRepository.findActiveDates(sportClass.getId()));
        List<TimeSlot> slots = slotsFor(canceled);
        int noBusy = 0;
        for (LocalDate date = from; !date.isAfter(to) && noBusy < limit; date = date.plusDays(1)) {
            if (taken.contains(date)) {
                continue;
            }
            for (TimeSlot slot : slots) {
                MakeupPlan plan = planMakeup(sportClass, canceled.getRoom(), date, slot.getStartTime(), slot.getEndTime());
                if (plan.problem() != null) {
                    continue;
                }
                long busy = busyMembers(sportClass, date, slot);
                if (tooManyBusy(busy, sportClass.getId())) {
                    continue;
                }
                found.add(new Candidate(date, slot, plan.room(), busy));
                if (busy == 0) {
                    noBusy++;
                }
            }
        }
        found.sort(Comparator.comparingLong(Candidate::busy).thenComparing(Candidate::date)
                .thenComparing(c -> c.slot().getStartTime()));
        return found.size() > limit ? new ArrayList<>(found.subList(0, limit)) : found;
    }

    private long busyMembers(SportClass sportClass, LocalDate date, TimeSlot slot) {
        return sessionRepository.countMembersBusy(sportClass.getId(), date, slot.getStartTime(), slot.getEndTime(),
                settings.getInt("schedule.buffer_minutes"));
    }

    // Quá makeup.max_busy_percent số học viên của lớp bị trùng lịch thì không dùng chỗ này
    private boolean tooManyBusy(long busy, Long classId) {
        long enrolled = registrationRepository.countBySportClass_IdAndStatusIn(classId, List.of("PENDING_PAYMENT", "REGISTERED"));
        return busy * 100 > enrolled * settings.getInt("makeup.max_busy_percent", 50);
    }

    private MakeupProposalResponse toProposal(Candidate c, boolean afterClassEnd) {
        return new MakeupProposalResponse(c.date(), c.slot().getStartTime(), c.slot().getEndTime(),
                c.room().getId(), c.room().getRoomName(), c.busy(), afterClassEnd);
    }

    // Phòng và lý do (nếu có) để xếp một buổi bù vào ngày giờ này. Lịch tuần của class bị khóa sau khi có ghi danh (BR-CLS-04)
    // nên không sửa được khi phòng hỏng; thay vào đó dùng phòng của buổi bị hủy nếu còn dùng được, không thì phòng khác
    // cùng bộ môn, đủ sức chứa, đang hoạt động, không trùng giờ. Lý do không xếp được: coach trùng buổi hoặc có 1-1
    // đã đặt, hoặc không có phòng nào phù hợp
    private MakeupPlan planMakeup(SportClass sportClass, FacilityRoom preferredRoom, LocalDate date,
            LocalTime start, LocalTime end) {
        int gap = ClassScheduleService.GAP_MINUTES;
        LocalTime startMinus = start.minusMinutes(gap);
        LocalTime endPlus = end.plusMinutes(gap);
        Long coachId = sportClass.getCoach().getId();

        if (sessionRepository.existsCoachConflict(coachId, date, startMinus, endPlus, null, null)) {
            return new MakeupPlan(null, "huấn luyện viên đã có buổi dạy khác trùng giờ ngày " + DisplayFormat.day(date));
        }
        ClassSession draft = new ClassSession();
        draft.setSportClass(sportClass);
        draft.setSessionDate(date);
        draft.setStartTime(start);
        draft.setEndTime(end);
        draft.setCoach(sportClass.getCoach());
        String slotProblem = classSessionService.slotConflict(draft);
        if (slotProblem != null) {
            return new MakeupPlan(null, slotProblem);
        }

        List<FacilityRoom> candidates = new ArrayList<>();
        candidates.add(preferredRoom);
        facilityRoomRepository.findAll().stream()
                .filter(r -> !r.getId().equals(preferredRoom.getId()))
                .sorted(Comparator.comparing(FacilityRoom::getId)).forEach(candidates::add);
        String roomProblem = "không còn phòng phù hợp vào ngày " + date;
        for (FacilityRoom candidate : candidates) {
            try {
                ClassScheduleService.checkRoomFitsClass(candidate, sportClass);
            } catch (BusinessRuleException ex) {
                if (candidate.getId().equals(preferredRoom.getId())) {
                    roomProblem = ex.getMessage() + ", và không còn phòng phù hợp khác vào ngày " + date;
                }
                continue;
            }
            if (!sessionRepository.existsRoomConflict(candidate.getId(), date, startMinus, endPlus, null, null)) {
                return new MakeupPlan(candidate, null);
            }
        }
        return new MakeupPlan(null, roomProblem);
    }

    // ====================== HELPERS ======================

    // Hủy một buổi do trung tâm: ghi lý do, nhật ký, báo cả lớp. Caller đã khóa class
    private void cancelOne(ClassSession session, String reason, User manager) {
        if (!"SCHEDULED".equals(session.getStatus())) {
            throw new BusinessRuleException("Chỉ hủy được buổi chưa diễn ra và chưa bị hủy");
        }
        if (hasEnded(session)) {
            throw new BusinessRuleException("Buổi học đã diễn ra");
        }
        session.setStatus("CANCELED_BY_CENTER");
        session.setCancelReason(reason.length() > 255 ? reason.substring(0, 255) : reason);
        sessionRepository.saveAndFlush(session);

        auditService.log(manager, "CLASS_SESSION_CANCELED", "class_session", session.getId(), Map.of(
                "classId", session.getSportClass().getId(), "sessionNo", session.getSessionNo(), "reason", reason));
        notificationService.sendToClassWithEmail(session.getSportClass().getId(), "MAKEUP", "Cập nhật lịch lớp",
                "Lớp " + session.getSportClass().getName() + ": buổi " + session.getSessionNo() + " ngày " + DisplayFormat.day(session.getSessionDate()) + " đã bị trung tâm hủy (" + reason + "). Bạn không bị tính vắng, trung tâm sẽ báo lịch học bù.",
                "Sport Center - Buổi học lớp " + session.getSportClass().getName() + " bị hủy",
                "Trung tâm đã hủy buổi " + session.getSessionNo() + " ngày " + session.getSessionDate().format(DAY)
                        + " của lớp " + session.getSportClass().getName() + ". Lý do: " + reason
                        + ". Bạn không bị tính vắng, trung tâm sẽ báo lịch học bù.");
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
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "Không tìm thấy lớp"));
        requireLive(sportClass);
        return sportClass;
    }

    private void requireLive(SportClass sportClass) {
        if (!LIVE_CLASS_STATUSES.contains(sportClass.getStatus())) {
            throw new BusinessRuleException("Chỉ xử lý được buổi của lớp đang mở đăng ký hoặc đang học");
        }
    }

    private ClassSession loadSession(Long sessionId) {
        return sessionRepository.findWithDetailsById(sessionId)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "Không tìm thấy buổi học có mã " + sessionId));
    }

    private ClassSession loadCanceledSession(Long sessionId) {
        ClassSession session = loadSession(sessionId);
        if (!"CANCELED_BY_CENTER".equals(session.getStatus())) {
            throw new BusinessRuleException("Chỉ buổi bị trung tâm hủy mới cần học bù");
        }
        return session;
    }

    private User findUser(String email) {
        return userRepository.findByEmail(email)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "Không tìm thấy người dùng"));
    }

    
}
