package com.swp391.sportcentermanagementsystem_v2.service;

import com.swp391.sportcentermanagementsystem_v2.dto.responseDTO.RevenueLine;
import com.swp391.sportcentermanagementsystem_v2.dto.responseDTO.RevenueReportResponse;
import com.swp391.sportcentermanagementsystem_v2.entity.MemberSubscription;
import com.swp391.sportcentermanagementsystem_v2.entity.Order;
import com.swp391.sportcentermanagementsystem_v2.entity.Payment;
import com.swp391.sportcentermanagementsystem_v2.repository.ReportPaymentRepository;
import com.swp391.sportcentermanagementsystem_v2.repository.ReportRefundRepository;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
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
import static org.mockito.ArgumentMatchers.anyCollection;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
class RevenueReportServiceTest {

    @Mock
    ReportPaymentRepository paymentRepository;

    @Mock
    ReportRefundRepository refundRepository;

    @InjectMocks
    RevenueReportService reportService;

    private Payment payment(long id, String paidAt, String amount, String method, String packageName, String gateway) {
        MemberSubscription subscription = new MemberSubscription();
        subscription.setPackageNameSnapshot(packageName);

        Order order = new Order();
        order.setSubscription(subscription);

        Payment payment = new Payment();
        payment.setId(id);
        payment.setOrder(order);
        payment.setPaidAt(OffsetDateTime.parse(paidAt));
        payment.setAmount(new BigDecimal(amount));
        payment.setPaymentMethod(method);
        payment.setStatus("SUCCESS");
        payment.setGatewayResponse(gateway);
        return payment;
    }

    private void stubPayments(Payment... payments) {
        when(paymentRepository.findByStatusInAndPaidAtGreaterThanEqualAndPaidAtLessThanOrderByPaidAtAsc(
                anyCollection(), any(), any())).thenReturn(List.of(payments));
        when(refundRepository.findByPayment_IdIn(anyCollection())).thenReturn(List.of());
    }

    private RevenueReportResponse report(String from, String to, String groupBy) {
        return reportService.getRevenue(LocalDate.parse(from), LocalDate.parse(to), groupBy);
    }

    private List<String> labels(List<RevenueLine> lines) {
        return lines.stream().map(RevenueLine::getLabel).toList();
    }

    @Test
    void totalsAndBreakdownsAddUp() {
        stubPayments(
                payment(1, "2026-10-16T05:00:00Z", "250000", "CASH", "Basic", null),
                payment(2, "2026-10-16T06:00:00Z", "2000", "QR_GATEWAY", "Plus", "VERIFIED: PayOS payment confirmed"),
                payment(3, "2026-10-17T03:00:00Z", "2000", "QR_GATEWAY", "Plus", null));

        RevenueReportResponse result = report("2026-10-01", "2026-10-31", "DAY");

        assertEquals(new BigDecimal("254000"), result.getTotalRevenue());
        assertEquals(3, result.getPaymentCount());
        assertEquals(List.of("CASH", "QR_GATEWAY"), labels(result.getByMethod()));
        assertEquals(new BigDecimal("250000"), result.getByMethod().get(0).getAmount());
        assertEquals(new BigDecimal("4000"), result.getByMethod().get(1).getAmount());
        assertEquals(List.of("Basic", "Plus"), labels(result.getByPackage()));
        assertEquals(List.of("Membership package"), labels(result.getBySource()));
        assertEquals(List.of("2026-10-16", "2026-10-17"), labels(result.getByPeriod()));
    }

    @Test
    void paymentJustAfterMidnightInVietnamCountsOnTheNextDay() {
        stubPayments(payment(1, "2026-10-16T17:30:00Z", "100000", "CASH", "Basic", null));
        RevenueReportResponse result = report("2026-10-01", "2026-10-31", "DAY");
        assertEquals(List.of("2026-10-17"), labels(result.getByPeriod()));
    }

    @Test
    void monthGroupingUsesVietnamDates() {
        stubPayments(
                payment(1, "2026-09-30T05:00:00Z", "100", "CASH", "Basic", null),
                payment(2, "2026-09-30T17:30:00Z", "200", "CASH", "Basic", null),
                payment(3, "2026-10-10T05:00:00Z", "300", "CASH", "Basic", null));
        RevenueReportResponse result = report("2026-09-01", "2026-10-31", "MONTH");
        assertEquals(List.of("2026-09", "2026-10"), labels(result.getByPeriod()));
    }

    @Test
    void quarterGroupingSplitsAtTheQuarterBoundary() {
        stubPayments(
                payment(1, "2026-02-10T05:00:00Z", "100", "CASH", "Basic", null),
                payment(2, "2026-09-30T05:00:00Z", "200", "CASH", "Basic", null),
                payment(3, "2026-09-30T17:30:00Z", "300", "CASH", "Basic", null));
        RevenueReportResponse result = report("2026-01-01", "2026-12-31", "QUARTER");
        assertEquals(List.of("2026-Q1", "2026-Q3", "2026-Q4"), labels(result.getByPeriod()));
    }

    @Test
    void paymentsNeedingReviewAreCountedButStayInTheTotal() {
        stubPayments(
                payment(1, "2026-10-16T05:00:00Z", "1000", "CASH", "Basic", null),
                payment(2, "2026-10-16T06:00:00Z", "2000", "QR_GATEWAY", "Plus",
                        "REQUIRES_REVIEW: Received amount does not match the order"));
        RevenueReportResponse result = report("2026-10-01", "2026-10-31", "DAY");
        assertEquals(1, result.getNeedsReviewCount());
        assertEquals(new BigDecimal("3000"), result.getTotalRevenue());
    }

    @Test
    void emptyRangeGivesZeroAndEmptyLists() {
        stubPayments();
        RevenueReportResponse result = report("2026-10-01", "2026-10-31", "DAY");
        assertEquals(BigDecimal.ZERO, result.getTotalRevenue());
        assertEquals(0, result.getPaymentCount());
        assertEquals(0, result.getByPeriod().size());
    }

    @Test
    void groupByIsNotCaseSensitive() {
        stubPayments();
        assertEquals("MONTH", report("2026-10-01", "2026-10-31", "month").getGroupBy());
    }

    @Test
    void rangeOf366DaysIsStillAllowed() {
        stubPayments();
        assertEquals(0, report("2026-01-01", "2027-01-01", "DAY").getPaymentCount());
    }

    @Test
    void invalidInputIsRejectedWithBadRequest() {
        assertBadRequest(() -> report("2026-10-31", "2026-10-01", "DAY"));
        assertBadRequest(() -> report("2026-01-01", "2027-01-02", "DAY"));
        assertBadRequest(() -> report("2026-10-01", "2026-10-31", "WEEK"));
    }

    private void assertBadRequest(Runnable action) {
        ResponseStatusException ex = assertThrows(ResponseStatusException.class, action::run);
        assertEquals(HttpStatus.BAD_REQUEST, ex.getStatusCode());
    }
}
