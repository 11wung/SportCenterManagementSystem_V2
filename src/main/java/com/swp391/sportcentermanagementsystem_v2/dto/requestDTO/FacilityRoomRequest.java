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

    @NotBlank(message = "Vui lòng nhập tên phòng")
    @Size(max = 100)
    private String roomName;

    @Size(max = 20, message = "Tên tầng tối đa 4 từ")
    private String floor;

    @NotNull(message = "Vui lòng nhập sức chứa của phòng")
    @Min(value = 1, message = "Sức chứa phải lớn hơn 0")
    private Integer capacity;

    @Size(max = 500, message = "Mô tả tối đa 90 từ")
    private String description;
}
