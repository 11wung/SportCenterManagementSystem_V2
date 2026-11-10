package com.swp391.sportcentermanagementsystem_v2.service;

import com.swp391.sportcentermanagementsystem_v2.dto.responseDTO.ClassPerformanceResponse;
import com.swp391.sportcentermanagementsystem_v2.dto.responseDTO.ClassSessionAttendanceSummary;
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

import java.time.LocalDateTime;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

// Báo cáo hiệu quả từng lớp cho Manager. Trạng thái điểm danh được tính độc lập cho mỗi buổi.
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
        if (classes.isEmpty()) return List.of();

        List<Long> classIds = classes.stream().map(SportClass::getId).toList();
        Map<Long, Long> reservedByClass = new HashMap<>();
        for (Object[] row : registrationRepository.countTakenByClassIds(classIds)) {
            reservedByClass.put((Long) row[0], (Long) row[1]);
        }
        Map<Long, Long> registeredByClass = new HashMap<>();
        for (Object[] row : registrationRepository.countRegisteredByClassIds(classIds)) {
            registeredByClass.put((Long) row[0], (Long) row[1]);
        }

        List<ClassSession> sessions = sessionRepository.findBySportClass_IdIn(classIds);
        List<Long> sessionIds = sessions.stream().map(ClassSession::getId).toList();
        Map<Long, List<ClassAttendance>> attendanceBySession = new HashMap<>();
        if (!sessionIds.isEmpty()) {
            for (ClassAttendance attendance : attendanceRepository.findBySession_IdIn(sessionIds)) {
                attendanceBySession.computeIfAbsent(attendance.getSession().getId(), key -> new ArrayList<>()).add(attendance);
            }
        }
        Map<Long, List<ClassSession>> sessionsByClass = new HashMap<>();
        for (ClassSession session : sessions) {
            sessionsByClass.computeIfAbsent(session.getSportClass().getId(), key -> new ArrayList<>()).add(session);
        }

        LocalDateTime now = LocalDateTime.now(CENTER_ZONE);
        List<ClassPerformanceResponse> result = new ArrayList<>();
        for (SportClass sportClass : classes) {
            List<ClassSession> classSessions = sessionsByClass.getOrDefault(sportClass.getId(), List.of());
            List<ClassSessionAttendanceSummary> summaries = new ArrayList<>();
            long marked = 0, present = 0;
            boolean inProgress = false, notRecorded = false, recorded = false, notStarted = false, canceled = false;

            for (ClassSession session : classSessions) {
                List<ClassAttendance> attendance = attendanceBySession.getOrDefault(session.getId(), List.of());
                long sessionMarked = 0, sessionPresent = 0, absent = 0, late = 0, excused = 0;
                for (ClassAttendance row : attendance) {
                    if (!MARKED_STATUSES.contains(row.getStatus())) continue;
                    sessionMarked++;
                    if ("PRESENT".equals(row.getStatus())) sessionPresent++;
                    if ("LATE".equals(row.getStatus())) { sessionPresent++; late++; }
                    if ("ABSENT".equals(row.getStatus())) absent++;
                    if ("EXCUSED".equals(row.getStatus())) excused++;
                }
                marked += sessionMarked;
                present += sessionPresent;

                String attendanceStatus;
                String attendanceLabel;
                String teachingStatus;
                if ("CANCELED".equals(session.getStatus()) || "CANCELED_BY_CENTER".equals(session.getStatus())) {
                    teachingStatus = "CANCELED";
                    attendanceStatus = "CANCELED";
                    attendanceLabel = "Buổi đã hủy";
                    canceled = true;
                } else {
                    LocalDateTime start = LocalDateTime.of(session.getSessionDate(), session.getStartTime());
                    LocalDateTime end = LocalDateTime.of(session.getSessionDate(), session.getEndTime());
                    if ("DONE".equals(session.getStatus())) {
                        teachingStatus = "TAUGHT";
                        if (sessionMarked > 0) {
                            attendanceStatus = "RECORDED"; attendanceLabel = "Đã có điểm danh"; recorded = true;
                        } else {
                            attendanceStatus = "NOT_RECORDED"; attendanceLabel = "Chưa ghi nhận điểm danh"; notRecorded = true;
                        }
                    } else if ("SCHEDULED".equals(session.getStatus()) && !now.isBefore(start) && now.isBefore(end)) {
                        teachingStatus = "IN_PROGRESS"; attendanceStatus = "IN_PROGRESS";
                        attendanceLabel = "Đang diễn ra"; inProgress = true;
                    } else if (now.isBefore(start)) {
                        // Buổi ở trạng thái chưa diễn ra (kể cả chưa publish SCHEDULED) phải ghi rõ Chưa bắt đầu.
                        teachingStatus = "NOT_TAUGHT"; attendanceStatus = "NOT_STARTED";
                        attendanceLabel = "Chưa bắt đầu"; notStarted = true;
                    } else {
                        teachingStatus = "NOT_TAUGHT";
                        attendanceStatus = "NOT_RECORDED";
                        attendanceLabel = "Chưa ghi nhận điểm danh";
                        notRecorded = true;
                    }
                }
                summaries.add(new ClassSessionAttendanceSummary(session.getId(), session.getSessionNo(),
                        session.getSessionDate(), session.getStartTime(), session.getEndTime(), session.getStatus(),
                        teachingStatus, attendanceStatus, attendanceLabel, sessionMarked, sessionPresent, absent, late,
                        excused, Math.max(0, registeredByClass.getOrDefault(sportClass.getId(), 0L) - sessionMarked)));
            }

            String status;
            String label;
            if (inProgress) { status = "IN_PROGRESS"; label = "Đang diễn ra"; }
            else if (recorded && (notStarted || notRecorded)) {
                status = "PARTIAL"; label = "Chưa hoàn tất điểm danh";
            }
            else if (notRecorded) { status = "NOT_RECORDED"; label = "Chưa ghi nhận điểm danh"; }
            else if (notStarted) { status = "NOT_STARTED"; label = "Chưa bắt đầu"; }
            else if (recorded) { status = "RECORDED"; label = "Đã có điểm danh"; }
            else if (canceled && classSessions.size() > 0) { status = "CANCELED"; label = "Buổi đã hủy"; }
            else { status = "NOT_STARTED"; label = "Chưa bắt đầu"; }

            int capacity = sportClass.getMaxCapacity() == null ? 0 : sportClass.getMaxCapacity();
            long reserved = reservedByClass.getOrDefault(sportClass.getId(), 0L);
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
            response.setAttendanceStatus(status);
            response.setAttendanceStatusLabel(label);
            response.setSessions(summaries);
            result.add(response);
        }
        return result;
    }
}
