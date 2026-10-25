package com.swp391.sportcentermanagementsystem_v2.dto.responseDTO;

import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

import java.time.LocalDate;

@Getter
@Setter
@NoArgsConstructor
public class SubscriptionFreezeResponse {

    private Long id;
    private Long subscriptionId;
    private LocalDate startDate;
    private LocalDate endDate;
    private LocalDate endedOn;
    private String status;
    // Số ngày đã dùng (DB tính)
    private Short daysUsed;
    // Ngày hết hạn mới của gói sau khi cộng ngày bảo lưu
    private LocalDate subscriptionEndDate;
}
