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

    @NotNull(message = "Vui lòng chọn hội viên")
    private Long memberId;

    @Size(max = 500, message = "Ghi chú tối đa 500 ký tự")
    private String notes;
}
