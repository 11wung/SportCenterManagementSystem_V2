package com.swp391.sportcentermanagementsystem_v2.dto.requestDTO;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

@Getter
@Setter
@NoArgsConstructor
public class EvaluateStudentRequest {

    @NotBlank(message = "Grade is required")
    @Pattern(regexp = "[ABCD]", message = "Grade must be A, B, C or D")
    private String grade;

    @Size(max = 2000, message = "Comment must not exceed 2000 characters")
    private String comment;
}
