package com.swp391.sportcentermanagementsystem_v2.service;

import com.swp391.sportcentermanagementsystem_v2.dto.responseDTO.RevenueLine;
import com.swp391.sportcentermanagementsystem_v2.dto.responseDTO.RevenueReportResponse;
import com.swp391.sportcentermanagementsystem_v2.entity.Order;
import com.swp391.sportcentermanagementsystem_v2.entity.Payment;
import com.swp391.sportcentermanagementsystem_v2.entity.Refund;
import com.swp391.sportcentermanagementsystem_v2.entity.MemberSubscription;
import com.swp391.sportcentermanagementsystem_v2.repository.ReportPaymentRepository;
import com.swp391.sportcentermanagementsystem_v2.repository.ReportRefundRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.time.ZoneId;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.Collection;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.TreeMap;

@Service
@RequiredArgsConstructor
public class RevenueReportService {

    private final ReportPaymentRepository paymentRepository;
    private final ReportRefundRepository refundRepository;

    private static final ZoneId VIETNAM_ZONE = ZoneId.of("Asia/Ho_Chi_Minh");
    private static final long MAX_DAYS = 366;
    private static final List<String> REVENUE_STATUSES = List.of("SUCCESS", "PENDING_REFUND", "REFUNDED");

    private static class Total {
        private BigDecimal amount = BigDecimal.ZERO;
        private long count;

        private void add(BigDecimal value) {
            amount = amount.add(value);
            count++;
        }
    }

    private record RevenueItem(
            OffsetDateTime paidAt,
            BigDecimal amount,
            String method,
            String packageName,
            String source,
            String gatewayResponse) {
    }

    /** Revenue data for the Manager web page; queries use Spring Data JPA derived methods. */
    @Transactional(readOnly = true)
    public RevenueReportResponse getRevenue(LocalDate from, LocalDate to, String groupBy) {
        String group = groupBy == null ? "DAY" : groupBy.trim().toUpperCase(Locale.ROOT);
        validateRange(from, to, group);

        List<RevenueItem> items = loadRevenueItems(from, to);
        RevenueReportResponse response = buildResponse(from, to, group, items);

        long days = ChronoUnit.DAYS.between(from, to) + 1;
        LocalDate previousFrom = from.minusDays(days);
        LocalDate previousTo = from.minusDays(1);
        BigDecimal previousRevenue = totalOf(loadRevenueItems(previousFrom, previousTo));
        BigDecimal changeAmount = response.getTotalRevenue().subtract(previousRevenue);

        response.setPreviousPeriodFrom(previousFrom);
        response.setPreviousPeriodTo(previousTo);
        response.setPreviousPeriodRevenue(previousRevenue);
        response.setChangeAmount(changeAmount);
        response.setChangePercent(previousRevenue.compareTo(BigDecimal.ZERO) == 0
                ? null
                : changeAmount.multiply(new BigDecimal("100"))
                        .divide(previousRevenue, 2, RoundingMode.HALF_UP));
        return response;
    }

    private void validateRange(LocalDate from, LocalDate to, String group) {
        if (from == null || to == null) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "from and to are required!");
        }
        if (!group.equals("DAY") && !group.equals("MONTH") && !group.equals("QUARTER")) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "groupBy must be DAY, MONTH or QUARTER!");
        }
        if (from.isAfter(to)) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "from must not be after to!");
        }
        if (ChronoUnit.DAYS.between(from, to) + 1 > MAX_DAYS) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST,
                    "The date range must not exceed " + MAX_DAYS + " days!");
        }
    }

    private List<RevenueItem> loadRevenueItems(LocalDate from, LocalDate to) {
        OffsetDateTime start = from.atStartOfDay(VIETNAM_ZONE).toOffsetDateTime();
        OffsetDateTime end = to.plusDays(1).atStartOfDay(VIETNAM_ZONE).toOffsetDateTime();

        List<Payment> payments = paymentRepository
                .findByStatusInAndPaidAtGreaterThanEqualAndPaidAtLessThanOrderByPaidAtAsc(
                        REVENUE_STATUSES, start, end);
        if (payments.isEmpty()) {
            return List.of();
        }

        List<Long> paymentIds = payments.stream().map(Payment::getId).toList();
        Map<Long, BigDecimal> refundedByPayment = new HashMap<>();
        for (Refund refund : refundRepository.findByPayment_IdIn(paymentIds)) {
            Long paymentId = refund.getPayment().getId();
            refundedByPayment.merge(paymentId, safeAmount(refund.getAmount()), BigDecimal::add);
        }

        List<RevenueItem> items = new ArrayList<>();
        for (Payment payment : payments) {
            BigDecimal amount = safeAmount(payment.getAmount());
            BigDecimal refunded = refundedByPayment.getOrDefault(payment.getId(), BigDecimal.ZERO);
            BigDecimal net = amount.subtract(refunded);

            // A pending-refund payment without a refund request is not yet a valid refund task.
            if ("PENDING_REFUND".equals(payment.getStatus()) && refunded.compareTo(BigDecimal.ZERO) <= 0) {
                continue;
            }
            if (net.compareTo(BigDecimal.ZERO) <= 0) {
                continue;
            }

            Order order = payment.getOrder();
            String source = sourceOf(order);
            String packageName = packageLabel(order, source);
            items.add(new RevenueItem(payment.getPaidAt(), net, payment.getPaymentMethod(), packageName,
                    source, payment.getGatewayResponse()));
        }
        return items;
    }

    private RevenueReportResponse buildResponse(LocalDate from, LocalDate to, String group, List<RevenueItem> items) {
        Map<String, Total> byMethod = new TreeMap<>();
        Map<String, Total> byPackage = new TreeMap<>();
        Map<String, Total> bySource = new TreeMap<>();
        Map<String, Total> byPeriod = new TreeMap<>();
        BigDecimal totalRevenue = BigDecimal.ZERO;
        long needsReview = 0;

        for (RevenueItem item : items) {
            totalRevenue = totalRevenue.add(item.amount());
            byMethod.computeIfAbsent(display(item.method()), key -> new Total()).add(item.amount());
            byPackage.computeIfAbsent(item.packageName(), key -> new Total()).add(item.amount());
            bySource.computeIfAbsent(sourceLabel(item.source()), key -> new Total()).add(item.amount());
            byPeriod.computeIfAbsent(periodLabel(item.paidAt(), group), key -> new Total()).add(item.amount());
            if (item.gatewayResponse() != null && item.gatewayResponse().startsWith("REQUIRES_REVIEW:")) {
                needsReview++;
            }
        }

        RevenueReportResponse response = new RevenueReportResponse();
        response.setFrom(from);
        response.setTo(to);
        response.setGroupBy(group);
        response.setTotalRevenue(totalRevenue);
        response.setPaymentCount(items.size());
        response.setNeedsReviewCount(needsReview);
        response.setByMethod(toLines(byMethod));
        response.setByPackage(toLines(byPackage));
        response.setBySource(toLines(bySource));
        response.setByPeriod(toLines(byPeriod));
        return response;
    }

    private BigDecimal totalOf(Collection<RevenueItem> items) {
        BigDecimal total = BigDecimal.ZERO;
        for (RevenueItem item : items) {
            total = total.add(item.amount());
        }
        return total;
    }

    private String sourceOf(Order order) {
        if (order == null) return "OTHER";
        if (order.getSubscription() != null) return "PACKAGE";
        if (order.getRegistration() != null) return "CLASS_ENROLLMENT";
        if (order.getCoachSlot() != null) return "ONE_ON_ONE";
        return "OTHER";
    }

    private String packageLabel(Order order, String source) {
        if ("PACKAGE".equals(source)) {
            MemberSubscription subscription = order.getSubscription();
            return subscription.getPackageNameSnapshot() == null
                    ? "Membership package" : subscription.getPackageNameSnapshot();
        }
        return switch (source) {
            case "CLASS_ENROLLMENT" -> "Class enrollment";
            case "ONE_ON_ONE" -> "One-on-one";
            default -> "Other";
        };
    }

    private String sourceLabel(String source) {
        return switch (source) {
            case "PACKAGE" -> "Membership package";
            case "CLASS_ENROLLMENT" -> "Class enrollment";
            case "ONE_ON_ONE" -> "One-on-one";
            default -> "Other";
        };
    }

    private String display(String value) {
        return value == null || value.isBlank() ? "Unknown" : value;
    }

    private String periodLabel(OffsetDateTime paidAt, String group) {
        LocalDate date = paidAt.atZoneSameInstant(VIETNAM_ZONE).toLocalDate();
        return switch (group) {
            case "MONTH" -> String.format("%d-%02d", date.getYear(), date.getMonthValue());
            case "QUARTER" -> date.getYear() + "-Q" + ((date.getMonthValue() - 1) / 3 + 1);
            default -> date.toString();
        };
    }

    private List<RevenueLine> toLines(Map<String, Total> totals) {
        List<RevenueLine> lines = new ArrayList<>();
        totals.forEach((label, total) -> lines.add(new RevenueLine(label, total.amount, total.count)));
        return lines;
    }

    private BigDecimal safeAmount(BigDecimal value) {
        return value == null ? BigDecimal.ZERO : value;
    }
}
