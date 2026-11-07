package com.swp391.sportcentermanagementsystem_v2.service;

import com.swp391.sportcentermanagementsystem_v2.dto.requestDTO.UpdateClassSessionRequest;
import com.swp391.sportcentermanagementsystem_v2.dto.responseDTO.ClassSessionResponse;
import com.swp391.sportcentermanagementsystem_v2.dto.responseDTO.CoachSessionResponse;
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
import java.time.temporal.ChronoUnit;
import java.time.LocalTime;
import java.util.ArrayList;
import java.util.List;

@Slf4j
@Service
@RequiredArgsConstructor
public class ClassSessionService {

    private static final int MAX_REPORTED_CONFLICTS = 10;
    private static final int DEFAULT_RANGE_DAYS = 30;
    private static final int MAX_RANGE_DAYS = 90;

    private final ClassSessionRepository sessionRepository;
    private final ClassScheduleRepository scheduleRepository;
    private final SportClassRepository sportClassRepository;
    private final FacilityRoomRepository facilityRoomRepository;
    private final CoachSlotService coachSlotService;
    private final UserRepository userRepository;
    private final SportClassService sportClassService;
    private final NotificationService notificationService;

    // BR-CLS-03: sinh các buổi học từ lịch tuần, đánh số từ ngày khai giảng.
    // Gọi lại thì xóa các buổi cũ và sinh lại (chỉ khi class còn nháp)
    @Transactional
    public List<ClassSessionResponse> generateSessions(Long classId, String managerEmail) {
        SportClass sportClass = sportClassRepository.findByIdForUpdate(classId)
                .orElseThrow(() -> new ResponseStatusException(
                        HttpStatus.NOT_FOUND, "Không tìm thấy lớp có mã " + classId));
        ClassScheduleService.requireDraft(sportClass);

        List<ClassSchedule> rules = scheduleRepository.findBySportClassIdOrderByDayOfWeekAscStartTimeAsc(classId);
        if (rules.isEmpty()) {
            throw new BusinessRuleException("Hãy xếp lịch tuần trước khi sinh buổi học");
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
                    conflict = slotConflict(session);
                }
                if (conflict != null) {
                    conflicts.add(conflict);
                }
            }
            date = date.plusDays(1);
        }

        if (!conflicts.isEmpty()) {
            throw new BusinessRuleException("Không thể sinh buổi học: "
                    + String.join("; ", conflicts.stream().limit(MAX_REPORTED_CONFLICTS).toList())
                    + (conflicts.size() > MAX_REPORTED_CONFLICTS
                            ? " (và " + (conflicts.size() - MAX_REPORTED_CONFLICTS) + " mục khác)" : ""));
        }

        sessionRepository.deleteByClassId(classId);
        return sessionRepository.saveAll(sessions).stream().map(ClassSessionResponse::new).toList();
    }

    @Transactional(readOnly = true)
    public List<ClassSessionResponse> getSessionsByClass(Long classId) {
        if (!sportClassRepository.existsById(classId)) {
            throw new ResponseStatusException(HttpStatus.NOT_FOUND, "Không tìm thấy lớp có mã " + classId);
        }
        return sessionRepository.findBySportClassIdOrderBySessionNo(classId)
                .stream()
                .map(ClassSessionResponse::new)
                .toList();
    }

    // Manager xem mọi buổi học trong khoảng ngày (lịch tuần), tối đa 90 ngày
    @Transactional(readOnly = true)
    public List<ClassSessionResponse> getSessionsBetween(LocalDate from, LocalDate to) {
        if (to.isBefore(from) || ChronoUnit.DAYS.between(from, to) > MAX_RANGE_DAYS) {
            throw new BusinessRuleException("Khoảng ngày phải hợp lệ và không quá " + MAX_RANGE_DAYS + " days");
        }
        return sessionRepository.findBySessionDateBetweenOrderBySessionDateAscStartTimeAsc(from, to).stream()
                .map(ClassSessionResponse::new)
                .toList();
    }

    // Coach xem các buổi dạy của chính mình, kể cả buổi được manager gán riêng. Mặc định từ hôm nay đến 30 ngày tới
    @Transactional(readOnly = true)
    public List<CoachSessionResponse> getMySessions(String coachEmail, LocalDate from, LocalDate to) {
        User coach = findUser(coachEmail);
        LocalDate start = from != null ? from : LocalDate.now(SportClassService.CENTER_ZONE);
        LocalDate end = to != null ? to : start.plusDays(DEFAULT_RANGE_DAYS);
        if (end.isBefore(start)) {
            throw new BusinessRuleException("Ngày kết thúc không được trước ngày bắt đầu");
        }
        if (ChronoUnit.DAYS.between(start, end) > MAX_RANGE_DAYS) {
            throw new BusinessRuleException("Khoảng ngày không được quá " + MAX_RANGE_DAYS + " days");
        }
        return sessionRepository.findCoachSessions(coach.getId(), start, end).stream()
                .map(CoachSessionResponse::new)
                .toList();
    }

    // BR-CLS-03, BR-CLS-04: đổi phòng hoặc coach của một buổi, ngày và giờ giữ nguyên (muốn dời thì hủy buổi rồi xếp bù).
    // Nháp sửa tự do; class đã mở đăng ký hay đang học thì chỉ đổi được buổi chưa diễn ra, và học viên được thông báo
    @Transactional
    public ClassSessionResponse updateSession(Long sessionId, UpdateClassSessionRequest request, String managerEmail) {
        ClassSession session = sessionRepository.findWithDetailsById(sessionId)
                .orElseThrow(() -> new ResponseStatusException(
                        HttpStatus.NOT_FOUND, "Không tìm thấy buổi học có mã " + sessionId));

        SportClass sportClass = sportClassRepository.findByIdForUpdate(session.getSportClass().getId())
                .orElseThrow(() -> new ResponseStatusException(
                        HttpStatus.NOT_FOUND, "Không tìm thấy lớp"));
        if (!List.of("DRAFT", "OPEN", "IN_PROGRESS").contains(sportClass.getStatus())) {
            throw new BusinessRuleException("Không đổi được buổi học của lớp " + sportClass.getStatus() + "");
        }
        if (!"SCHEDULED".equals(session.getStatus())) {
            throw new BusinessRuleException("Chỉ đổi được buổi đang ở trạng thái SCHEDULED (trạng thái hiện tại: "
                    + session.getStatus() + ")");
        }
        if (session.getSessionDate().isBefore(LocalDate.now(SportClassService.CENTER_ZONE))) {
            throw new BusinessRuleException("Không đổi được buổi học đã diễn ra");
        }
        if (session.getRoom().getId().equals(request.getRoomId())
                && session.getCoach().getId().equals(request.getCoachId())) {
            throw new BusinessRuleException("Phòng và coach trùng với hiện tại");
        }

        FacilityRoom room = facilityRoomRepository.findById(request.getRoomId())
                .orElseThrow(() -> new ResponseStatusException(
                        HttpStatus.BAD_REQUEST, "Không tìm thấy phòng có mã " + request.getRoomId()));
        ClassScheduleService.checkRoomFitsClass(room, sportClass);
        User coach = sportClassService.findTeachingCoach(request.getCoachId(), sportClass.getCourse().getCategory().getId());

        // Kiểm tra trên bản nháp chưa gắn vào session: nếu sửa session trước, Hibernate tự flush UPDATE
        // trước câu query kiểm tra và database sẽ báo lỗi chung chung thay vì câu mô tả rõ ràng
        ClassSession candidate = new ClassSession();
        candidate.setSessionNo(session.getSessionNo());
        candidate.setSessionDate(session.getSessionDate());
        candidate.setStartTime(session.getStartTime());
        candidate.setEndTime(session.getEndTime());
        candidate.setRoom(room);
        candidate.setCoach(coach);
        String conflict = findConflict(candidate, null, sessionId);
        if (conflict == null) {
            conflict = slotConflict(candidate);
        }
        if (conflict != null) {
            throw new BusinessRuleException("Không thể cập nhật buổi học: " + conflict
                    + ". Nếu không đổi được phòng hoặc coach, hãy hủy buổi và xếp học bù");
        }

        String oldRoom = session.getRoom().getRoomName();
        String oldCoach = session.getCoach().getFullName();
        Long oldCoachId = session.getCoach().getId();

        session.setRoom(room);
        session.setCoach(coach);

        // saveAndFlush để lỗi từ database (vd. trùng khung 1-1 của coach) nổ ngay trong hàm này
        ClassSession saved = sessionRepository.saveAndFlush(session);

        if (!"DRAFT".equals(sportClass.getStatus())) {
            notificationService.sendToClass(sportClass.getId(), "ANNOUNCEMENT", "Buổi học đã được cập nhật",
                    "Buổi " + saved.getSessionNo() + " của lớp " + sportClass.getName() + " ngày " + saved.getSessionDate()
                            + " " + saved.getStartTime() + " được đổi từ phòng " + oldRoom + " (coach " + oldCoach
                            + ") sang phòng " + room.getRoomName() + " (coach " + coach.getFullName() + ").");
            if (!coach.getId().equals(oldCoachId)) {
                notificationService.send(coach.getId(), "ANNOUNCEMENT", "Bạn được phân công một buổi học",
                        "Bạn được phân công buổi " + saved.getSessionNo() + " của lớp " + sportClass.getName() + " ngày "
                                + saved.getSessionDate() + " " + saved.getStartTime() + " (room " + room.getRoomName() + ").");
            }
        }
        return new ClassSessionResponse(saved);
    }

    // Suất 1-1 của coach đã có người đặt trùng giờ buổi học thì chặn (đã có tiền của hội viên, phải dời hoặc đổi coach trước)
    String slotConflict(ClassSession s) {
        Long coachId = s.getCoach().getId();
        String blocker = coachSlotService.findBookedSlotBlocker(
                coachId, s.getSessionDate(), s.getStartTime(), s.getEndTime());
        if (blocker != null) {
            return "buổi " + (s.getSessionNo() != null ? s.getSessionNo() + " " : "") + "ngày " + s.getSessionDate()
                    + ": " + blocker;
        }
        return null;
    }

    private User findUser(String email) {
        return userRepository.findByEmail(email)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "Không tìm thấy người dùng"));
    }

    // BR-SCH-01: kiểm tra trùng coach / phòng với các buổi khác (cách nhau dưới 15 phút cũng tính là trùng).
    // Trả về câu mô tả xung đột, hoặc null nếu không trùng.
    // shortcut: LocalTime không vòng qua nửa đêm nên buổi sát 00:00 có thể lọt, exclusion constraint của DB vẫn chặn ở bước lưu
    String findConflict(ClassSession s, Long excludeClassId, Long excludeSessionId) {
        LocalTime startMinus = s.getStartTime().minusMinutes(ClassScheduleService.GAP_MINUTES);
        LocalTime endPlus = s.getEndTime().plusMinutes(ClassScheduleService.GAP_MINUTES);
        String when = "buổi " + (s.getSessionNo() != null ? s.getSessionNo() + " " : "") + "ngày " + s.getSessionDate();

        if (sessionRepository.existsCoachConflict(s.getCoach().getId(), s.getSessionDate(),
                startMinus, endPlus, excludeClassId, excludeSessionId)) {
            return when + " trùng buổi dạy khác của coach (cần cách nhau ít nhất 15 phút)";
        }
        if (sessionRepository.existsRoomConflict(s.getRoom().getId(), s.getSessionDate(),
                startMinus, endPlus, excludeClassId, excludeSessionId)) {
            return when + " trùng buổi học khác ở phòng " + s.getRoom().getRoomName()
                    + " (cần cách nhau ít nhất 15 phút)";
        }
        return null;
    }
}
