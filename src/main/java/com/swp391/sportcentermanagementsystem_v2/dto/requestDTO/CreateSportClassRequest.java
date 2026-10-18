package com.swp391.sportcentermanagementsystem_v2.dto.requestDTO;

import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import lombok.Getter;
import lombok.Setter;

@Getter
@Setter
public class CreateSportClassRequest {

    @NotNull(message = "Sport category ID is required")
    @Min(value = 1, message = "Sport category ID must be greater than 0")
    private Long categoryId;

    @NotNull(message = "Coach ID is required")
    @Min(value = 1, message = "Coach ID must be greater than 0")
    private Long coachId;

    @NotBlank(message = "Name is required")
    @Size(max = 150, message = "Name must not exceed 150 characters")
    private String name;

    private String description;

    @NotBlank(message = "Level is required")
    private String level = "BEGINNER";

    @NotNull(message = "Max capacity is required")
    @Min(value = 1, message = "Max capacity must be greater than 0")
    private Integer maxCapacity;

    @NotNull(message = "Tier is required")
    @Min(value = 1, message = "Tier must be greater than 0")
    private Short requiredTierLevel = 2;
}
