package com.swp391.sportcentermanagementsystem_v2.service;

import com.swp391.sportcentermanagementsystem_v2.dto.responseDTO.ClassPerformanceResponse;
import com.swp391.sportcentermanagementsystem_v2.entity.ClassAttendance;
import com.swp391.sportcentermanagementsystem_v2.entity.ClassSession;
import com.swp391.sportcentermanagementsystem_v2.entity.SportClass;
import com.swp391.sportcentermanagementsystem_v2.repository.ClassAttendanceRepository;
import com.swp391.sportcentermanagementsystem_v2.repository.ClassRegistrationRepository;
import com.swp391.sportcentermanagementsystem_v2.repository.ClassSessionRepository;
import com.swp391.sportcentermanagementsystem_v2.repository.SportClassRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

// Báo cáo hiệu quả từng lớp cho Manager. Không dùng SQL viết trực tiếp trong service.
@Service
@RequiredArgsConstructor
public class ClassPerformanceReportService {

    private static final ZoneId CENTER_ZONE = ZoneId.of("Asia/Ho_Chi_Minh");
    private static final List<String> MARKED_STATUSES = List.of("PRESENT", "LATE", "ABSENT", "EXCUSED");

    private final SportClassRepository sportClassRepository;
    private final ClassRegistrationRepository registrationRepository;
    private final ClassSessionRepository sessionRepository;
    private final ClassAttendanceRepository attendanceRepository;

    @Transactional(readOnly = true)
    public List<ClassPerformanceResponse> getClassPerformance() {
        List<SportClass> classes = sportClassRepository.findAllByOrderByStartDateDescIdDesc();
        if (classes.isEmpty()) {
            return List.of();
        }

        List<Long> classIds = classes.stream().map(SportClass::getId).toList();
        Map<Long, Long> reservedByClass = new HashMap<>();
        for (Object[] row : registrationRepository.countTakenByClassIds(classIds)) {
            reservedByClass.put((Long) row[0], (Long) row[1]);
        }

        List<ClassSession> sessions = sessionRepository.findBySportClass_IdIn(classIds);
        List<Long> sessionIds = sessions.stream().map(ClassSession::getId).toList();
        Map<Long, Long> markedByClass = new HashMap<>();
        Map<Long, Long> presentByClass = new HashMap<>();
        if (!sessionIds.isEmpty()) {
            List<ClassAttendance> attendances = attendanceRepository
                    .findBySession_IdInAndStatusIn(sessionIds, MARKED_STATUSES);
            for (ClassAttendance attendance : attendances) {
                Long classId = attendance.getSession().getSportClass().getId();
                markedByClass.merge(classId, 1L, Long::sum);
                if ("PRESENT".equals(attendance.getStatus()) || "LATE".equals(attendance.getStatus())) {
                    presentByClass.merge(classId, 1L, Long::sum);
                }
            }
        }

        LocalDateTime now = LocalDateTime.now(CENTER_ZONE);
        Map<Long, Boolean> hasPastSession = new HashMap<>();
        Map<Long, Boolean> hasSessionInProgress = new HashMap<>();
        for (ClassSession session : sessions) {
            if (session.getSessionDate() == null || session.getStartTime() == null
                    || session.getEndTime() == null
                    || !List.of("SCHEDULED", "DONE").contains(session.getStatus())) {
                continue;
            }
            LocalDateTime startAt = LocalDateTime.of(session.getSessionDate(), session.getStartTime());
            LocalDateTime endAt = LocalDateTime.of(session.getSessionDate(), session.getEndTime());
            if (!now.isBefore(startAt) && now.isBefore(endAt)) {
                hasSessionInProgress.put(session.getSportClass().getId(), true);
            } else if (!now.isBefore(endAt)) {
                hasPastSession.put(session.getSportClass().getId(), true);
            }
        }

        List<ClassPerformanceResponse> result = new ArrayList<>();
        for (SportClass sportClass : classes) {
            long reserved = reservedByClass.getOrDefault(sportClass.getId(), 0L);
            long marked = markedByClass.getOrDefault(sportClass.getId(), 0L);
            long present = presentByClass.getOrDefault(sportClass.getId(), 0L);
            int capacity = sportClass.getMaxCapacity() == null ? 0 : sportClass.getMaxCapacity();

            String attendanceStatus;
            String attendanceStatusLabel;
            if (marked > 0) {
                attendanceStatus = "RECORDED";
                attendanceStatusLabel = "Đã có điểm danh";
            } else if (hasSessionInProgress.getOrDefault(sportClass.getId(), false)) {
                attendanceStatus = "IN_PROGRESS";
                attendanceStatusLabel = "Đang diễn ra";
            } else if (hasPastSession.getOrDefault(sportClass.getId(), false)) {
                attendanceStatus = "NOT_RECORDED";
                attendanceStatusLabel = "Chưa ghi nhận điểm danh";
            } else {
                attendanceStatus = "NOT_STARTED";
                attendanceStatusLabel = "Chưa bắt đầu";
            }

            ClassPerformanceResponse response = new ClassPerformanceResponse();
            response.setClassId(sportClass.getId());
            response.setClassName(sportClass.getName());
            response.setSportName(sportClass.getCourse() == null || sportClass.getCourse().getCategory() == null
                    ? null : sportClass.getCourse().getCategory().getName());
            response.setCoachName(sportClass.getCoach() == null ? null : sportClass.getCoach().getFullName());
            response.setStatus(sportClass.getStatus());
            response.setStartDate(sportClass.getStartDate());
            response.setMaxCapacity(capacity);
            response.setReservedSeats(reserved);
            response.setFillPercent(capacity == 0 ? 0 : (int) Math.round(reserved * 100.0 / capacity));
            response.setMarkedRecords(marked);
            response.setAttendanceRate(marked == 0 ? null : (int) Math.round(present * 100.0 / marked));
            response.setAttendanceStatus(attendanceStatus);
            response.setAttendanceStatusLabel(attendanceStatusLabel);
            result.add(response);
        }
        return result;
    }
}
