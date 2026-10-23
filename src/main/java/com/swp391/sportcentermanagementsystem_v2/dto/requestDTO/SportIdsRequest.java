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
    @NotNull(message = "Sport category ids cannot be null.")
    private Set<Long> sportCategoryIds;
}
