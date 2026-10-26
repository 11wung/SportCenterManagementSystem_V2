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
public class VoucherCampaignResponse {
    private Long id;
    private String code;
    private String kind;
    private BigDecimal value;
    private String appliesTo;
    private Integer maxUses;
    // Số member đã nhập mã
    private long redeemed;
    private OffsetDateTime startsAt;
    private OffsetDateTime endsAt;
    // Đang trong thời gian hiệu lực và còn lượt
    private boolean active;
}
