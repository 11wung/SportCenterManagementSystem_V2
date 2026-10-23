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

    @NotNull(message = "Coach ID is required")
    @Min(value = 1, message = "Coach ID must be greater than 0")
    private Long coachId;

    @NotBlank(message = "Name is required")
    @Size(max = 150, message = "Name must not exceed 150 characters")
    private String name;

    private String description;

    @NotNull(message = "Max capacity is required")
    @Min(value = 1, message = "Max capacity must be greater than 0")
    private Integer maxCapacity;

    @NotNull(message = "Tuition is required")
    @DecimalMin(value = "0", message = "Tuition cannot be negative")
    private BigDecimal tuition;

    @NotNull(message = "Start date is required")
    private LocalDate startDate;

    @NotNull(message = "Registration opening date is required")
    private LocalDate registrationOpensOn;
}
