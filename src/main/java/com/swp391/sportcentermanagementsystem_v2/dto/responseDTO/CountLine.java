package com.swp391.sportcentermanagementsystem_v2.dto.responseDTO;

import lombok.AllArgsConstructor;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

// Một dòng đếm: nhãn và số lượng.
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
public class CountLine {

    private String label;
    private long count;
}
