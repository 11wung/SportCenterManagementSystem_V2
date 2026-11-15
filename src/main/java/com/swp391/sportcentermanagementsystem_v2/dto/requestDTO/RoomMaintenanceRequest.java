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

    @NotBlank(message = "Vui lòng chọn trạng thái")
    @Pattern(regexp = "AVAILABLE|MAINTENANCE|CLOSED", message = "Trạng thái phòng chỉ có: Đang dùng, Bảo trì, Đóng cửa")
    private String status;
}
