package com.swp391.sportcentermanagementsystem_v2.dto.requestDTO;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

// Lý do của một thao tác của manager (hủy buổi, coach nghỉ việc...), ghi vào nhật ký và thông báo
@Getter
@Setter
@NoArgsConstructor
public class ReasonRequest {

    @NotBlank(message = "Vui lòng nhập lý do")
    @Size(max = 200, message = "Lý do tối đa 200 ký tự")
    private String reason;
}
