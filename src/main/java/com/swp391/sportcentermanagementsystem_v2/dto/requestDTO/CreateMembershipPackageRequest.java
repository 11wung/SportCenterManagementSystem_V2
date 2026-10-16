package com.swp391.sportcentermanagementsystem_v2.dto.requestDTO;

import jakarta.validation.constraints.DecimalMin;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.Digits;

import lombok.Getter;
import lombok.Setter;
import lombok.NoArgsConstructor;

import java.math.BigDecimal;

@Getter
@Setter
@NoArgsConstructor
public class CreateMembershipPackageRequest {

    @NotBlank(message = "Package code is required")
    @Size(max = 30, message = "Package code must not exceed 30 characters")
    private String code;

    @NotBlank(message = "Package name is required")
    @Size(max = 150, message = "Package name must not exceed 150 characters")
    private String name;

    private String description;

    @NotNull(message = "Duration is required")
    @Min(value = 1, message = "Duration must be at least 1 month")
    private Short durationMonths;

    @NotNull(message = "Price is required")
    @DecimalMin(value = "0.0", message = "Price must not be negative")
    @Digits(integer = 12, fraction = 0, message = "Price must have at most 12 digits and no decimal places")
    private BigDecimal price;

    // Cấp quyền lợi Package phải >= 1.
    @NotNull(message = "Package tier is required")
    @Min(value = 1, message = "Package tier must be at least 1")
    private Short tierLevel;

    @NotNull(message = "Maximum classes per week is required")
    @Min(value = 0, message = "Maximum classes per week cannot be negative")
    private Short maxClassesPerWeek;
}