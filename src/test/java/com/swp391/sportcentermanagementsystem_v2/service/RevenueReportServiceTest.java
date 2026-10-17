package com.swp391.sportcentermanagementsystem_v2.service;

import com.swp391.sportcentermanagementsystem_v2.dto.responseDTO.RevenueLine;
import com.swp391.sportcentermanagementsystem_v2.dto.responseDTO.RevenueReportResponse;
import com.swp391.sportcentermanagementsystem_v2.repository.PaymentRepository;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.mockito.junit.jupiter.MockitoSettings;
import org.mockito.quality.Strictness;
import org.springframework.http.HttpStatus;
import org.springframework.web.server.ResponseStatusException;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
class RevenueReportServiceTest {

    @Mock
    PaymentRepository paymentRepository;

    @InjectMocks
    RevenueReportService reportService;

    // Một dòng dữ liệu mẫu: giờ trả tính theo UTC.
    private record Row(String paidAtUtc, String amount, String method, String packageName, String gateway)
            implements PaymentRepository.RevenueRow {

        public OffsetDateTime getPaidAt() {
            return OffsetDateTime.parse(paidAtUtc);
        }

        public BigDecimal getAmount() {
            return new BigDecimal(amount);
        }

        public String getPaymentMethod() {
            return method;
        }

        public String getPackageName() {
            return packageName;
        }

        public String getGatewayResponse() {
            return gateway;
        }
    }

    private Row cash(String paidAtUtc, String amount) {
        return new Row(paidAtUtc, amount, "CASH", "Basic", null);
    }

    private Row qr(String paidAtUtc, String amount) {
        return new Row(paidAtUtc, amount, "QR_GATEWAY", "Plus", "VERIFIED: PayOS payment confirmed");
    }

    private void stubRows(Row... rows) {
        when(paymentRepository.findPaidBetween(any(), any())).thenReturn(List.of(rows));
    }

    private RevenueReportResponse report(String from, String to, String groupBy) {
        return reportService.getRevenue(LocalDate.parse(from), LocalDate.parse(to), groupBy);
    }

    private List<String> labels(List<RevenueLine> lines) {
        return lines.stream().map(RevenueLine::getLabel).toList();
    }

    @Test
    void totalsAndBreakdownsAddUp() {
        stubRows(
                cash("2026-10-16T05:00:00Z", "250000"),
                qr("2026-10-16T06:00:00Z", "2000"),
                qr("2026-10-17T03:00:00Z", "2000"));

        RevenueReportResponse result = report("2026-10-01", "2026-10-31", "DAY");

        assertEquals(new BigDecimal("254000"), result.getTotalRevenue());
        assertEquals(3, result.getPaymentCount());

        assertEquals(List.of("CASH", "QR_GATEWAY"), labels(result.getByMethod()));
        assertEquals(new BigDecimal("250000"), result.getByMethod().get(0).getAmount());
        assertEquals(new BigDecimal("4000"), result.getByMethod().get(1).getAmount());
        assertEquals(2, result.getByMethod().get(1).getPaymentCount());

        assertEquals(List.of("Basic", "Plus"), labels(result.getByPackage()));

        assertEquals(List.of("2026-10-16", "2026-10-17"), labels(result.getByPeriod()));
        assertEquals(new BigDecimal("252000"), result.getByPeriod().get(0).getAmount());
        assertEquals(new BigDecimal("2000"), result.getByPeriod().get(1).getAmount());
    }

    @Test
    void paymentJustAfterMidnightInVietnamCountsOnTheNextDay() {
        // 17:30 UTC ngày 16/10 là 00:30 ngày 17/10 giờ Việt Nam.
        stubRows(cash("2026-10-16T17:30:00Z", "100000"));

        RevenueReportResponse result = report("2026-10-01", "2026-10-31", "DAY");

        assertEquals(List.of("2026-10-17"), labels(result.getByPeriod()));
    }

    @Test
    void monthGroupingUsesVietnamDates() {
        stubRows(
                cash("2026-09-30T05:00:00Z", "100"),   // 30/9 giờ VN
                cash("2026-09-30T17:30:00Z", "200"),   // 00:30 ngày 1/10 giờ VN
                cash("2026-10-10T05:00:00Z", "300"));

        RevenueReportResponse result = report("2026-09-01", "2026-10-31", "MONTH");

        assertEquals(List.of("2026-09", "2026-10"), labels(result.getByPeriod()));
        assertEquals(new BigDecimal("100"), result.getByPeriod().get(0).getAmount());
        assertEquals(new BigDecimal("500"), result.getByPeriod().get(1).getAmount());
    }

    @Test
    void quarterGroupingSplitsAtTheQuarterBoundary() {
        stubRows(
                cash("2026-02-10T05:00:00Z", "100"),   // Q1
                cash("2026-09-30T05:00:00Z", "200"),   // Q3
                cash("2026-09-30T17:30:00Z", "300"));  // 1/10 giờ VN: Q4

        RevenueReportResponse result = report("2026-01-01", "2026-12-31", "QUARTER");

        assertEquals(List.of("2026-Q1", "2026-Q3", "2026-Q4"), labels(result.getByPeriod()));
        assertEquals(new BigDecimal("300"), result.getByPeriod().get(2).getAmount());
    }

    @Test
    void queryCoversWholeFirstAndLastDayInVietnamTime() {
        stubRows();

        report("2026-10-01", "2026-10-31", "DAY");

        ArgumentCaptor<OffsetDateTime> start = ArgumentCaptor.forClass(OffsetDateTime.class);
        ArgumentCaptor<OffsetDateTime> end = ArgumentCaptor.forClass(OffsetDateTime.class);
        verify(paymentRepository).findPaidBetween(start.capture(), end.capture());

        assertEquals(OffsetDateTime.parse("2026-10-01T00:00:00+07:00").toInstant(), start.getValue().toInstant());
        assertEquals(OffsetDateTime.parse("2026-11-01T00:00:00+07:00").toInstant(), end.getValue().toInstant());
    }

    @Test
    void paymentsNeedingReviewAreCountedButStayInTheTotal() {
        stubRows(
                cash("2026-10-16T05:00:00Z", "1000"),
                new Row("2026-10-16T06:00:00Z", "2000", "QR_GATEWAY", "Plus",
                        "REQUIRES_REVIEW: Received amount does not match the order"));

        RevenueReportResponse result = report("2026-10-01", "2026-10-31", "DAY");

        assertEquals(1, result.getNeedsReviewCount());
        assertEquals(new BigDecimal("3000"), result.getTotalRevenue());
    }

    @Test
    void emptyRangeGivesZeroAndEmptyLists() {
        stubRows();

        RevenueReportResponse result = report("2026-10-01", "2026-10-31", "DAY");

        assertEquals(BigDecimal.ZERO, result.getTotalRevenue());
        assertEquals(0, result.getPaymentCount());
        assertEquals(0, result.getByPeriod().size());
    }

    @Test
    void groupByIsNotCaseSensitive() {
        stubRows();

        assertEquals("MONTH", report("2026-10-01", "2026-10-31", "month").getGroupBy());
    }

    @Test
    void rangeOf366DaysIsStillAllowed() {
        stubRows();

        // 01/01/2026 đến 01/01/2027 tính cả hai đầu là đúng 366 ngày.
        assertEquals(0, report("2026-01-01", "2027-01-01", "DAY").getPaymentCount());
    }

    @Test
    void invalidInputIsRejectedWithBadRequest() {
        assertBadRequest(() -> report("2026-10-31", "2026-10-01", "DAY"));    // from sau to
        assertBadRequest(() -> report("2026-01-01", "2027-01-02", "DAY"));    // 367 ngày: quá giới hạn
        assertBadRequest(() -> report("2026-10-01", "2026-10-31", "WEEK"));   // groupBy lạ
    }

    private void assertBadRequest(Runnable action) {
        ResponseStatusException ex = assertThrows(ResponseStatusException.class, action::run);
        assertEquals(HttpStatus.BAD_REQUEST, ex.getStatusCode());
    }
}
