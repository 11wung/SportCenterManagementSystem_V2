package com.swp391.sportcentermanagementsystem_v2.dto.responseDTO;

import com.swp391.sportcentermanagementsystem_v2.entity.Refund;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

import java.math.BigDecimal;
import java.time.OffsetDateTime;

@Getter
@Setter
@NoArgsConstructor
public class RefundResponse {
    private Long id;
    private Long paymentId;
    private String invoiceNumber;
    private String memberName;
    private String memberEmail;
    private BigDecimal amount;
    private String method;
    private String reason;
    private String status;
    private OffsetDateTime requestedAt;
    private String processedByName;
    private OffsetDateTime processedAt;
    // Khoản chờ quá số ngày cấu hình (BR-OVW-04): giao diện tô đỏ
    private boolean overdue;

    public RefundResponse(Refund r, boolean overdue) {
        this.id = r.getId();
        this.paymentId = r.getPayment().getId();
        this.invoiceNumber = r.getPayment().getInvoiceNumber();
        this.memberName = r.getPayment().getPayer().getFullName();
        this.memberEmail = r.getPayment().getPayer().getEmail();
        this.amount = r.getAmount();
        this.method = r.getMethod();
        this.reason = r.getReason();
        this.status = r.getStatus();
        this.requestedAt = r.getRequestedAt();
        this.processedByName = r.getProcessedBy() == null ? null : r.getProcessedBy().getFullName();
        this.processedAt = r.getProcessedAt();
        this.overdue = overdue;
    }
}
