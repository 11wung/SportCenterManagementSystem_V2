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

    @NotNull(message = "Vui lòng chọn môn")
    private Long sportCategoryId;

    @NotBlank(message = "Vui lòng nhập tên khóa học")
    @Size(max = 150, message = "Tên khóa học tối đa 150 ký tự")
    private String name;

    private String description;

    @NotBlank(message = "Vui lòng chọn trình độ")
    @Pattern(regexp = "BEGINNER|INTERMEDIATE|ADVANCED",
            message = "Trình độ phải là BEGINNER, INTERMEDIATE hoặc ADVANCED")
    private String level;

    @NotNull(message = "Vui lòng nhập số buổi")
    @Min(value = 1, message = "Số buổi phải lớn hơn 0")
    private Integer totalSessions;

    @NotNull(message = "Vui lòng nhập học phí mặc định")
    @DecimalMin(value = "0", message = "Học phí mặc định không được âm")
    private BigDecimal defaultTuition;

    // Khóa cấp thấp hơn cùng môn, dùng cho cảnh báo khi đăng ký khóa nâng cao (BR-CRS-02)
    private Long prerequisiteCourseId;
}
