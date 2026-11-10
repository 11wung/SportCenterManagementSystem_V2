package com.swp391.sportcentermanagementsystem_v2.dto.responseDTO;

import lombok.AllArgsConstructor;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.util.Map;
import java.util.List;

@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
public class MemberSubscriptionResponse {

    private Long id;

    private Long memberId;
    private String memberName;

    private Long packageId;
    private String packageName;

    private Short months;
    // Quyền lợi đã chụp lúc mua
    private Map<String, Object> benefits;

    // Snapshot cấp quyền lợi khi tạo đơn
    private Short tierLevel;

    // NEW, RENEWAL hoặc UPGRADE
    private String purchaseType;

    // Subscription gốc nếu đây là đơn UPGRADE
    private Long upgradeFromSubscriptionId;

    // Giá trị gói cũ dùng để quy đổi thành ngày cộng thêm.
    private BigDecimal upgradeCreditAmount;
    // Khoản giảm thực tế được trừ khỏi số tiền phải thanh toán.
    private BigDecimal upgradeDiscountAmount;

    // Hạn thanh toán chung của đơn, hiện là 24 giờ từ lúc tạo.
    private OffsetDateTime paymentExpiresAt;
    // Tên rõ nghĩa để Member UI hiển thị hạn thanh toán tiền mặt tại quầy.
    private OffsetDateTime cashPaymentDeadline;
    // Đơn PENDING cho phép cả thanh toán tiền mặt tại quầy và thanh toán online.
    private List<String> availablePaymentMethods;

    private BigDecimal totalAmount;

    private LocalDate startDate;
    private LocalDate endDate;

    private String status;

    private String createdByStaffName;

    private OffsetDateTime createdAt;
}