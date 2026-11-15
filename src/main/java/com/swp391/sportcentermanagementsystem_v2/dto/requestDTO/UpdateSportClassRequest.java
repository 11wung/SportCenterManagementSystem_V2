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

// Course không đổi được sau khi tạo class
@Getter
@Setter
public class UpdateSportClassRequest {

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

    @NotNull(message = "Vui lòng nhập học phí")
    @DecimalMin(value = "0", message = "Học phí không được âm")
    private BigDecimal tuition;

    @NotNull(message = "Vui lòng chọn ngày bắt đầu")
    private LocalDate startDate;

    // Tùy chọn: ngày tự mở đăng ký (chỉ có tác dụng khi lớp còn nháp)
    private LocalDate registrationOpensOn;
}
