package com.swp391.sportcentermanagementsystem_v2.dto.requestDTO;

import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

@Getter
@Setter
@NoArgsConstructor
public class CheckInRequest {

    @NotNull(message = "Member is required")
    private Long memberId;

    @Size(max = 500, message = "Notes must not exceed 500 characters")
    private String notes;
}
