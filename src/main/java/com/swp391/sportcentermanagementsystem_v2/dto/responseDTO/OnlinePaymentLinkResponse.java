package com.swp391.sportcentermanagementsystem_v2.dto.responseDTO;

import lombok.Getter;
import lombok.Setter;
import lombok.NoArgsConstructor;

import java.time.OffsetDateTime;

@Getter
@Setter
@NoArgsConstructor
public class OnlinePaymentLinkResponse {
    private Long paymentId;
    private Long subscriptionId;
    private Long orderCode;
    private String checkoutUrl;
    private OffsetDateTime expiresAt;
    private String paymentStatus;
}
