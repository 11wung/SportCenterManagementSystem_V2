package com.swp391.sportcentermanagementsystem_v2.dto.responseDTO;

import java.math.BigDecimal;
import java.time.OffsetDateTime;

// Một giao dịch trong báo cáo doanh thu: ai trả, ai thu, thu cho khoản nào. amount là số tiền khách đã trả;
// refundedAmount là phần đã hoàn xong, pendingRefundAmount là phần đang chờ hoàn.
public record RevenuePaymentRow(
        Long paymentId,
        String invoiceNumber,
        OffsetDateTime paidAt,
        String payerName,
        String receivedByName,
        String method,
        String item,
        BigDecimal amount,
        BigDecimal refundedAmount,
        BigDecimal pendingRefundAmount,
        String refundReason,
        String refundedByName,
        OffsetDateTime refundedAt) {
}
