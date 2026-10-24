package com.swp391.sportcentermanagementsystem_v2.dto.responseDTO;

import lombok.AllArgsConstructor;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

import java.math.BigDecimal;
import java.time.OffsetDateTime;

@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
public class ClassRegistrationResponse {

    private Long id;
    private Long classId;
    private String className;
    private Long userId;
    private String memberName;
    private OffsetDateTime registeredAt;
    private String status;
    private OffsetDateTime canceledAt;
    // Hạn giữ chỗ khi đang chờ thanh toán, null nếu không còn chờ
    private OffsetDateTime holdExpiresAt;
    private BigDecimal tuition;
}
