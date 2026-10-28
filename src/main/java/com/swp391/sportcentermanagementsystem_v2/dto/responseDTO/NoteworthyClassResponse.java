package com.swp391.sportcentermanagementsystem_v2.dto.responseDTO;

import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;

@Getter
@Setter
@NoArgsConstructor
public class NoteworthyClassResponse {
    private Long classId;
    private String className;
    private String status;
    private LocalDate startDate;
    private long reservedSeats;
    private int maxCapacity;
    private BigDecimal fillPercent = BigDecimal.ZERO;
    private long absentCount;
    private long canceledSessionCount;
    private List<String> reasons = new ArrayList<>();
}
