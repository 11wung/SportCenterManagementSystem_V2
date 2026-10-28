package com.swp391.sportcentermanagementsystem_v2.dto.responseDTO;

import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

import java.math.BigDecimal;
import java.time.OffsetDateTime;

@Getter
@Setter
@NoArgsConstructor
public class ManagerTaskResponse {
    private Long id;
    private String type;
    private String memberName;
    private String invoiceNumber;
    private BigDecimal amount;
    private OffsetDateTime createdAt;
    private long daysWaiting;
    private boolean overdue;
    private String title;
}
