package com.swp391.sportcentermanagementsystem_v2.dto.requestDTO;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

@Getter
@Setter
@NoArgsConstructor
public class CancelClassRequest {

    // Lý do được ghi vào các buổi bị hủy và nhật ký thao tác
    @NotBlank(message = "Vui lòng nhập lý do")
    @Size(max = 255, message = "Lý do tối đa 255 ký tự")
    private String reason;
}
