package com.swp391.sportcentermanagementsystem_v2.dto.responseDTO;

import java.time.*;
import java.util.List;
import com.fasterxml.jackson.databind.JsonNode;

public final class TrainingResponse {
    private TrainingResponse() {}

    public record AssignedClass(Long id, String name, String status, Long coachId) {}
    public record Session(Long id, Long classId, String className, Long coachId, String coachName,
            int sessionNo, LocalDate date, LocalTime startTime, LocalTime endTime, String room,
            String status, String classStatus, OffsetDateTime finalizedAt, long version) {}
    public record Participant(Long memberId, String fullName, String fitnessGoal,
            Long attendanceId, String status, String notes, boolean eligible, boolean withdrawnBeforeSession) {}
    public record Counts(long present, long absent, long late, long excused, long unmarked, long declined) {}
    public record SessionDetail(Session session, List<Participant> roster, Counts counts,
            boolean attendanceEditable, boolean finalizationAllowed, boolean resultsEditable, List<Result> results, List<Plan> plans) {}
    public record Exercise(Long id, int position, String name, String instructions,
            Integer sets, Integer repetitions, Integer durationSeconds) {}
    public record Plan(Long id, Long classId, String className, Long memberId, String memberName,
            Long coachId, String coachName, String title, String description, String objective,
            LocalDate startDate, LocalDate endDate, String status, String coachNotes, int version,
            List<Exercise> exercises) {}
    public record Result(Long id, Long sessionId, String className, LocalDate sessionDate,
            Long memberId, String memberName, Long exerciseId, String activityKey, String activityName,
            JsonNode prescription, Integer actualSets, Integer actualRepetitions, Integer actualDurationSeconds,
            String performance, String completionStatus, String notes, String coachName,
            OffsetDateTime recordedAt, OffsetDateTime updatedAt) {}
    public record Evaluation(Long id, Long classId, String className, Long memberId, String memberName,
            Long coachId, String coachName, LocalDate evaluationDate, LocalDate periodStart, LocalDate periodEnd,
            String summary, int rating, String strengths, String improvements, String recommendations,
            String feedback, int version) {}
    public record Attendance(Long id, Long sessionId, String className, LocalDate date, LocalTime startTime,
            String status, String notes, OffsetDateTime finalizedAt, boolean withdrawnBeforeSession,
            String sessionStatus, String cancelReason) {}
    public record Statistics(long attendedSessions, long markedSessions, Double attendanceRate,
            long completedSessions, long completedActivities) {}
    public record Page<T>(List<T> items, Long nextCursor) {}
    public record Dashboard(List<Session> sessions, Page<Plan> plans, Page<Attendance> attendance,
            Page<Result> results, Page<Evaluation> evaluations, Statistics statistics) {}
    public record MemberHistory(Long memberId, String fullName, String fitnessGoal,
            Page<Attendance> attendance, Page<Result> results, Page<Evaluation> evaluations) {}
}
