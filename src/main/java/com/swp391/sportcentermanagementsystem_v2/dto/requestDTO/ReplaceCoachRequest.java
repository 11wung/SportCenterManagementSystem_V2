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
public class ReplaceCoachRequest {

    @NotNull(message = "Vui lòng chọn huấn luyện viên")
    @Min(value = 1, message = "Vui lòng chọn huấn luyện viên")
    private Long coachId;

    @NotBlank(message = "Vui lòng nhập lý do")
    @Size(max = 200, message = "Lý do tối đa 200 ký tự")
    private String reason;
}
