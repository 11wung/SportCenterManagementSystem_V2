package com.swp391.sportcentermanagementsystem_v2.dto.requestDTO;

import jakarta.validation.constraints.DecimalMin;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

import java.math.BigDecimal;

@Getter
@Setter
@NoArgsConstructor
public class CourseRequest {

    @NotNull(message = "Sport category cannot be null.")
    private Long sportCategoryId;

    @NotBlank(message = "Course name cannot be blank.")
    @Size(max = 150, message = "Course name must be at most 150 characters.")
    private String name;

    private String description;

    @NotBlank(message = "Level cannot be blank.")
    @Pattern(regexp = "BEGINNER|INTERMEDIATE|ADVANCED",
            message = "Level must be BEGINNER, INTERMEDIATE or ADVANCED.")
    private String level;

    @NotNull(message = "Total sessions cannot be null.")
    @Min(value = 1, message = "Total sessions must be positive.")
    private Integer totalSessions;

    @NotNull(message = "Default tuition cannot be null.")
    @DecimalMin(value = "0", message = "Default tuition cannot be negative.")
    private BigDecimal defaultTuition;

    // Khóa cấp thấp hơn cùng môn, dùng cho cảnh báo khi đăng ký khóa nâng cao (BR-CRS-02)
    private Long prerequisiteCourseId;
}
