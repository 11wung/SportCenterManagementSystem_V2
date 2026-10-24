package com.swp391.sportcentermanagementsystem_v2.dto.requestDTO;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Pattern;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

@Getter
@Setter
@NoArgsConstructor
public class RoomMaintenanceRequest {

    @NotBlank(message = "Status is required")
    @Pattern(regexp = "AVAILABLE|MAINTENANCE|CLOSED", message = "Status must be AVAILABLE, MAINTENANCE or CLOSED")
    private String status;
}
