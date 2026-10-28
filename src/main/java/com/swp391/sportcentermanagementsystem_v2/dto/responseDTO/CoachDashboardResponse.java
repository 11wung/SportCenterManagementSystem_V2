package com.swp391.sportcentermanagementsystem_v2.dto.responseDTO;

import com.swp391.sportcentermanagementsystem_v2.dto.responseDTO.TrainingResponse.Session;
import java.time.*;
import java.util.List;

public record CoachDashboardResponse(LocalDate today, OffsetDateTime refreshedAt, Summary summary,
        List<SessionItem> todaySessions, List<SessionItem> pendingSessions,
        List<AssignedClass> classes, List<Activity> recentActivities) {
    public record Summary(long myClasses, long todaySessions, long upcomingSessions, long pendingAttendance) {}
    public record SessionFacts(String sport, long members, long marked) {}
    public record SessionItem(Session session, String sport, long members, long marked,
            boolean attendanceEditable, boolean finalizationAllowed, boolean resultsEditable) {}
    public record NextSession(long id, LocalDate date, LocalTime startTime, LocalTime endTime, String room) {}
    public record AssignedClass(long id, long coachId, String name, String sport, String course,
            String description, String status, int capacity, long members, String schedule,
            NextSession nextSession, Long representativeSessionId) {}
    public record Activity(long id, String action, String resourceId, OffsetDateTime occurredAt, boolean finalized) {}
}
