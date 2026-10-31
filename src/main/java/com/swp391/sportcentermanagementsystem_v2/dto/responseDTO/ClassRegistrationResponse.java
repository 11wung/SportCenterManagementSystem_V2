package com.swp391.sportcentermanagementsystem_v2.dto.responseDTO;

import lombok.AllArgsConstructor;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

import java.math.BigDecimal;
import java.time.LocalDate;
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
    // Số tiền được hoàn khi hủy đăng ký đã trả tiền, null nếu không có khoản hoàn
    private BigDecimal refundAmount;
    // Chỉ có khi mời từ danh sách chờ: hạn trả tiền cho chỗ được mời (BR-WL-03)
    private OffsetDateTime offerExpiresAt;
    // Chỉ có khi đang chờ: vị trí trong hàng chờ, 1 là người được mời trước
    private Integer waitlistPosition;
    // Trạng thái lớp (OPEN, IN_PROGRESS, COMPLETED, CANCELED...) và ngày khai giảng, để member thấy lớp đã kết thúc hay chưa
    private String classStatus;
    private LocalDate classStartDate;
}
