package com.swp391.sportcentermanagementsystem_v2.dto.requestDTO;

import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

@Getter
@Setter
@NoArgsConstructor
public class FacilityRoomRequest {

    @NotBlank(message = "Facility Room Name cannot be Blank")
    @Size(max = 100)
    private String roomName;

    @Size(max = 20, message = "Floor name must be below 3-4 words")
    private String floor;

    @NotNull(message = "Capacity of Facility Room cannot be Blank")
    @Min(value = 1, message = "Capacity must be greater 0")
    private Integer capacity;

    @Size(max = 500, message = "Description must be below 90 words.")
    private String description;
}
