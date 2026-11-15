package com.swp391.sportcentermanagementsystem_v2.dto.requestDTO;

import jakarta.validation.constraints.NotNull;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

import java.time.LocalDate;

@Getter
@Setter
@NoArgsConstructor
public class ChooseMakeupRequest {

    @NotNull(message = "Vui lòng chọn ngày")
    private LocalDate date;
}
