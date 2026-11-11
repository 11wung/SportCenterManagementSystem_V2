package com.swp391.sportcentermanagementsystem_v2.dto.requestDTO;

import jakarta.validation.Valid;
import jakarta.validation.constraints.NotEmpty;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Positive;
import jakarta.validation.constraints.Size;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

import java.util.List;

@Getter
@Setter
@NoArgsConstructor
public class BulkEvaluateStudentsRequest {

    @NotEmpty(message = "Danh sách đánh giá không được để trống")
    @Valid
    private List<@NotNull StudentEvaluationItem> evaluations;

    @Getter
    @Setter
    @NoArgsConstructor
    public static class StudentEvaluationItem {

        @NotNull(message = "Registration ID is required")
        @Positive(message = "Registration ID must be positive")
        private Long registrationId;

        @NotBlank(message = "Grade is required")
        @Pattern(regexp = "[ABCD]", message = "Grade must be A, B, C or D")
        private String grade;

        @Size(max = 2000, message = "Comment must not exceed 2000 characters")
        private String comment;
    }
}
