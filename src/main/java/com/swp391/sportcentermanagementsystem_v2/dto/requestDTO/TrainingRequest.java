package com.swp391.sportcentermanagementsystem_v2.dto.requestDTO;

import com.fasterxml.jackson.databind.annotation.JsonDeserialize;
import jakarta.validation.Valid;
import jakarta.validation.constraints.*;
import java.time.LocalDate;
import java.util.List;

/** Flow 4 request contracts. Duration values always use seconds. */
public final class TrainingRequest {
    private TrainingRequest() {}

    public record AttendanceEntry(@NotNull @Positive @JsonDeserialize(using = TrainingNumbers.LongValue.class) Long memberId,
            @NotBlank @Pattern(regexp = "PRESENT|ABSENT|LATE|EXCUSED") String status,
            @Size(max = 500) String notes) {}

    public record Attendance(@NotNull @Size(max = 500) List<@NotNull @Valid AttendanceEntry> entries,
            boolean finalizeSession,
            @NotNull @PositiveOrZero @JsonDeserialize(using = TrainingNumbers.LongValue.class) Long version) {}

    public record Exercise(@Positive @JsonDeserialize(using = TrainingNumbers.LongValue.class) Long id, @NotBlank @Size(max = 200) String name,
            @Size(max = 2000) String instructions,
            @Min(0) @Max(10000) @JsonDeserialize(using = TrainingNumbers.IntegerValue.class) Integer sets, @Min(0) @Max(100000) @JsonDeserialize(using = TrainingNumbers.IntegerValue.class) Integer repetitions,
            @Min(0) @Max(604800) @JsonDeserialize(using = TrainingNumbers.IntegerValue.class) Integer durationSeconds) {}

    public record Plan(@NotNull @Positive @JsonDeserialize(using = TrainingNumbers.LongValue.class) Long classId, @Positive @JsonDeserialize(using = TrainingNumbers.LongValue.class) Long memberId,
            @NotBlank @Size(max = 200) String title, @Size(max = 4000) String description,
            @NotBlank @Size(max = 2000) String objective,
            @NotNull LocalDate startDate, @NotNull LocalDate endDate,
            @NotBlank @Pattern(regexp = "DRAFT|PUBLISHED|COMPLETED|ARCHIVED") String status,
            @Size(max = 4000) String coachNotes,
            @NotEmpty @Size(max = 100) List<@NotNull @Valid Exercise> exercises,
            @Positive @JsonDeserialize(using = TrainingNumbers.IntegerValue.class) Integer version) {}

    public record Result(@NotNull @Positive @JsonDeserialize(using = TrainingNumbers.LongValue.class) Long memberId, @Positive @JsonDeserialize(using = TrainingNumbers.LongValue.class) Long exerciseId,
            @NotBlank @Size(max = 100) @Pattern(regexp = "[a-zA-Z0-9:_-]+") String activityKey,
            @NotBlank @Size(max = 200) String activityName,
            @Min(0) @Max(10000) @JsonDeserialize(using = TrainingNumbers.IntegerValue.class) Integer actualSets,
            @Min(0) @Max(100000) @JsonDeserialize(using = TrainingNumbers.IntegerValue.class) Integer actualRepetitions,
            @Min(0) @Max(604800) @JsonDeserialize(using = TrainingNumbers.IntegerValue.class) Integer actualDurationSeconds,
            @Size(max = 2000) String performance,
            @NotBlank @Pattern(regexp = "COMPLETED|PARTIAL|NOT_COMPLETED") String completionStatus,
            @Size(max = 4000) String notes,
            @NotNull @PositiveOrZero @JsonDeserialize(using = TrainingNumbers.LongValue.class) Long version) {}

    // TrainingService validates the date against its Asia/Ho_Chi_Minh clock, not the server's calendar day.
    public record Evaluation(@NotNull @Positive @JsonDeserialize(using = TrainingNumbers.LongValue.class) Long classId, @NotNull @Positive @JsonDeserialize(using = TrainingNumbers.LongValue.class) Long memberId,
            @NotNull LocalDate evaluationDate, @NotNull LocalDate periodStart,
            @NotNull LocalDate periodEnd, @NotBlank @Size(max = 4000) String summary,
            @NotNull @Min(1) @Max(5) @JsonDeserialize(using = TrainingNumbers.IntegerValue.class) Integer rating,
            @Size(max = 2000) String strengths, @Size(max = 2000) String improvements,
            @Size(max = 2000) String recommendations, @Size(max = 4000) String feedback,
            @Positive @JsonDeserialize(using = TrainingNumbers.IntegerValue.class) Integer version) {}
}
