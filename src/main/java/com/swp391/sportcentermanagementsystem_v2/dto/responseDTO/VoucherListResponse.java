package com.swp391.sportcentermanagementsystem_v2.dto.responseDTO;

import lombok.AllArgsConstructor;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

import java.math.BigDecimal;
import java.util.List;

@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
public class VoucherListResponse {
    // Ưu đãi của gói cho đơn đang xét (null nếu chưa chọn đơn), để so với từng voucher
    private BigDecimal tierDiscountAmount;
    private List<VoucherResponse> vouchers;
}
