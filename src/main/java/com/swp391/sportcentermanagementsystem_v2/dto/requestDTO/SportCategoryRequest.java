
package com.swp391.sportcentermanagementsystem_v2.dto.requestDTO;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

@Getter
@Setter
@NoArgsConstructor
public class SportCategoryRequest {

    @NotBlank(message = "Sport Name cannot be Blank.")
    @Size(max = 100)
    private String name;

    @Size(max = 500, message = "Description must be below 90 words.")
    private String description;
}
