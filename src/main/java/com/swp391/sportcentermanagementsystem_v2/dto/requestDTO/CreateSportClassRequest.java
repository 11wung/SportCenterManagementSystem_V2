package com.swp391.sportcentermanagementsystem_v2.dto.requestDTO;

import jakarta.validation.constraints.DecimalMin;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import lombok.Getter;
import lombok.Setter;

import java.math.BigDecimal;
import java.time.LocalDate;

@Getter
@Setter
public class CreateSportClassRequest {

    @NotNull(message = "Vui lòng chọn khóa học")
    @Min(value = 1, message = "Vui lòng chọn khóa học")
    private Long courseId;

    @NotNull(message = "Vui lòng chọn huấn luyện viên")
    @Min(value = 1, message = "Vui lòng chọn huấn luyện viên")
    private Long coachId;

    @NotBlank(message = "Vui lòng nhập tên")
    @Size(max = 150, message = "Tên tối đa 150 ký tự")
    private String name;

    private String description;

    @NotNull(message = "Vui lòng nhập sĩ số tối đa")
    @Min(value = 1, message = "Sĩ số tối đa phải lớn hơn 0")
    private Integer maxCapacity;

    // Bỏ trống thì lấy học phí mặc định của course (BR-CLS-01)
    @DecimalMin(value = "0", message = "Học phí không được âm")
    private BigDecimal tuition;

    @NotNull(message = "Vui lòng chọn ngày bắt đầu")
    private LocalDate startDate;

    // Tùy chọn: ngày tự mở đăng ký. Bỏ trống thì lớp nằm ở nháp chờ manager mở tay
    private LocalDate registrationOpensOn;
}
