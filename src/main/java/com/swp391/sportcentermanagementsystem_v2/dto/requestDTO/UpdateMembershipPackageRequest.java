package com.swp391.sportcentermanagementsystem_v2.dto.requestDTO;

import jakarta.validation.constraints.DecimalMin;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.Size;
import jakarta.validation.constraints.Digits;

import lombok.Getter;
import lombok.Setter;
import lombok.NoArgsConstructor;

import java.math.BigDecimal;

@Getter
@Setter
@NoArgsConstructor
public class UpdateMembershipPackageRequest {

    @Size(max = 150, message = "Package name must not exceed 150 characters")
    private String name;

    private String description;

    @Min(value = 1, message = "Duration must be at least 1 month")
    private Short durationMonths;

    // PATCH: Không bắt buộc truyền tierLevel.
    // Nếu có truyền thì phải >= 1.
    @Min(value = 1, message = "Package tier must be at least 1")
    private Short tierLevel;

    @DecimalMin(value = "0.0", message = "Price must not be negative")
    @Digits(integer = 12, fraction = 0, message = "Price must have at most 12 digits and no decimal places")
    private BigDecimal price;

    @Min(value = 0, message = "Maximum classes per week cannot be negative")
    private Short maxClassesPerWeek;

    private Boolean isActive;
}