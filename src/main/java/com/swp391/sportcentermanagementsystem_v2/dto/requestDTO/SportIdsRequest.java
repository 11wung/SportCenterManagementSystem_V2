package com.swp391.sportcentermanagementsystem_v2.dto.requestDTO;

import jakarta.validation.constraints.NotNull;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

import java.util.Set;

@Getter
@Setter
@NoArgsConstructor
public class SportIdsRequest {

    // Danh sách rỗng nghĩa là gỡ hết môn khỏi phòng/coach
    @NotNull(message = "Vui lòng chọn môn")
    private Set<Long> sportCategoryIds;
}
