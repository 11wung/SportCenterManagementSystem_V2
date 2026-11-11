package com.swp391.sportcentermanagementsystem_v2.service;

import com.swp391.sportcentermanagementsystem_v2.dto.responseDTO.RevenueLine;
import com.swp391.sportcentermanagementsystem_v2.dto.responseDTO.RevenuePaymentRow;
import com.swp391.sportcentermanagementsystem_v2.dto.responseDTO.RevenueReportResponse;
import com.swp391.sportcentermanagementsystem_v2.entity.Order;
import com.swp391.sportcentermanagementsystem_v2.entity.Payment;
import com.swp391.sportcentermanagementsystem_v2.entity.Refund;
import com.swp391.sportcentermanagementsystem_v2.entity.MemberSubscription;
import com.swp391.sportcentermanagementsystem_v2.repository.ReportPaymentRepository;
import com.swp391.sportcentermanagementsystem_v2.repository.ReportRefundRepository;
import com.swp391.sportcentermanagementsystem_v2.repository.CoachSlotRepository;
import com.swp391.sportcentermanagementsystem_v2.entity.CoachSlot;
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
    private final CoachSlotRepository coachSlotRepository;

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
        return getRevenueByCategory(from, to, groupBy, null);
    }

    // Báo cáo Membership riêng, không trộn doanh thu lớp hoặc 1-1.
    @Transactional(readOnly = true)
    public RevenueReportResponse getPackageRevenue(LocalDate from, LocalDate to, String groupBy) {
        return getRevenueByCategory(from, to, groupBy, "PACKAGE");
    }

    // Báo cáo hoạt động học riêng: gồm lớp nhóm và buổi 1-1.
    @Transactional(readOnly = true)
    public RevenueReportResponse getClassRevenue(LocalDate from, LocalDate to, String groupBy) {
        return getRevenueByCategory(from, to, groupBy, "CLASS");
    }

    private RevenueReportResponse getRevenueByCategory(
            LocalDate from, LocalDate to, String groupBy, String category) {
        String group = groupBy == null ? "DAY" : groupBy.trim().toUpperCase(Locale.ROOT);
        validateRange(from, to, group);

        List<RevenueItem> items = filterItems(loadRevenueItems(from, to), category);
        RevenueReportResponse response = buildResponse(from, to, group, items);
        response.setPaymentDetails(listPayments(from, to, null, null, null, category));

        long days = ChronoUnit.DAYS.between(from, to) + 1;
        LocalDate previousFrom = from.minusDays(days);
        LocalDate previousTo = from.minusDays(1);
        BigDecimal previousRevenue = totalOf(filterItems(
                loadRevenueItems(previousFrom, previousTo), category));
        BigDecimal changeAmount = response.getTotalRevenue().subtract(previousRevenue);

        addRefundStats(response, paymentsInCategory(from, to, category));
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

    /** Tiền hoàn của các giao dịch trong kỳ: đã hoàn xong, đang chờ hoàn, và tổng thu trước hoàn. */
    private void addRefundStats(RevenueReportResponse response, LocalDate from, LocalDate to) {
        addRefundStats(response, paymentsIn(from, to));
    }

    private void addRefundStats(RevenueReportResponse response, List<Payment> payments) {
        BigDecimal done = BigDecimal.ZERO;
        BigDecimal pending = BigDecimal.ZERO;
        long doneCount = 0;
        long pendingCount = 0;
        for (Refund refund : refundsOf(payments)) {
            if ("DONE".equals(refund.getStatus())) {
                done = done.add(safeAmount(refund.getAmount()));
                doneCount++;
            } else {
                pending = pending.add(safeAmount(refund.getAmount()));
                pendingCount++;
            }
        }
        response.setRefundedAmount(done);
        response.setRefundedCount(doneCount);
        response.setPendingRefundAmount(pending);
        response.setPendingRefundCount(pendingCount);
        response.setGrossRevenue(response.getTotalRevenue().add(done));
    }

    /**
     * Từng giao dịch của kỳ. method, item (nhãn khoản thu như trong báo cáo) và refund (DONE hoặc PENDING:
     * chỉ giao dịch có khoản hoàn ở trạng thái đó) để trống thì không lọc.
     */
    @Transactional(readOnly = true)
    public List<RevenuePaymentRow> listPayments(LocalDate from, LocalDate to, String method, String item, String refund) {
        return listPayments(from, to, method, item, refund, null);
    }

    @Transactional(readOnly = true)
    public List<RevenuePaymentRow> listPackagePayments(
            LocalDate from, LocalDate to, String method, String item, String refund) {
        return listPayments(from, to, method, item, refund, "PACKAGE");
    }

    @Transactional(readOnly = true)
    public List<RevenuePaymentRow> listClassPayments(
            LocalDate from, LocalDate to, String method, String item, String refund) {
        return listPayments(from, to, method, item, refund, "CLASS");
    }

    private List<RevenuePaymentRow> listPayments(
            LocalDate from, LocalDate to, String method, String item, String refund, String category) {
        validateRange(from, to, "DAY");
        List<Payment> payments = paymentsInCategory(from, to, category);
        Map<Long, List<Refund>> refundsByPayment = new HashMap<>();
        for (Refund r : refundsOf(payments)) {
            refundsByPayment.computeIfAbsent(r.getPayment().getId(), k -> new ArrayList<>()).add(r);
        }

        List<RevenuePaymentRow> rows = new ArrayList<>();
        for (Payment p : payments) {
            List<Refund> refunds = refundsByPayment.getOrDefault(p.getId(), List.of());
            String label = packageLabel(p.getOrder(), sourceOf(p.getOrder()));
            if (method != null && !method.isBlank() && !display(p.getPaymentMethod()).equals(method)) continue;
            if (item != null && !item.isBlank() && !label.equals(item)) continue;
            if (refund != null && !refund.isBlank() && refunds.stream().noneMatch(r -> refund.equals(r.getStatus()))) continue;

            Refund last = refunds.stream().filter(r -> "DONE".equals(r.getStatus()))
                    .max(java.util.Comparator.comparing(Refund::getProcessedAt)).orElse(null);
            String source = sourceOf(p.getOrder());
            rows.add(new RevenuePaymentRow(p.getId(), p.getInvoiceNumber(), p.getPaidAt(),
                    p.getPayer() == null ? null : p.getPayer().getFullName(),
                    p.getReceivedBy() == null ? null : p.getReceivedBy().getFullName(),
                    p.getPaymentMethod(), revenueTypeLabel(source), label, safeAmount(p.getAmount()),
                    sumByStatus(refunds, "DONE"), sumByStatus(refunds, "PENDING"),
                    refunds.isEmpty() ? null : refunds.get(refunds.size() - 1).getReason(),
                    last == null || last.getProcessedBy() == null ? null : last.getProcessedBy().getFullName(),
                    last == null ? null : last.getProcessedAt()));
        }
        return rows;
    }

    private List<Payment> paymentsIn(LocalDate from, LocalDate to) {
        OffsetDateTime start = from.atStartOfDay(VIETNAM_ZONE).toOffsetDateTime();
        OffsetDateTime end = to.plusDays(1).atStartOfDay(VIETNAM_ZONE).toOffsetDateTime();
        return paymentRepository.findByStatusInAndPaidAtGreaterThanEqualAndPaidAtLessThanOrderByPaidAtAsc(
                REVENUE_STATUSES, start, end);
    }

    private List<Payment> paymentsInCategory(LocalDate from, LocalDate to, String category) {
        List<Payment> payments = paymentsIn(from, to);
        if (category == null) {
            return payments;
        }
        return payments.stream().filter(payment -> matchesCategory(sourceOf(payment.getOrder()), category)).toList();
    }

    private List<RevenueItem> filterItems(List<RevenueItem> items, String category) {
        if (category == null) {
            return items;
        }
        return items.stream().filter(item -> matchesCategory(item.source(), category)).toList();
    }

    private boolean matchesCategory(String source, String category) {
        if ("PACKAGE".equals(category)) {
            return "PACKAGE".equals(source);
        }
        if ("CLASS".equals(category)) {
            return "CLASS_ENROLLMENT".equals(source) || "ONE_ON_ONE".equals(source);
        }
        return true;
    }

    private List<Refund> refundsOf(List<Payment> payments) {
        return payments.isEmpty() ? List.of()
                : refundRepository.findByPayment_IdIn(payments.stream().map(Payment::getId).toList());
    }

    private BigDecimal sumByStatus(List<Refund> refunds, String status) {
        return refunds.stream().filter(r -> status.equals(r.getStatus()))
                .map(r -> safeAmount(r.getAmount())).reduce(BigDecimal.ZERO, BigDecimal::add);
    }

    private void validateRange(LocalDate from, LocalDate to, String group) {
        if (from == null || to == null) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Cần có ngày bắt đầu và ngày kết thúc");
        }
        if (!group.equals("DAY") && !group.equals("MONTH") && !group.equals("QUARTER")) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "groupBy phải là DAY, MONTH hoặc QUARTER");
        }
        if (from.isAfter(to)) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Ngày bắt đầu không được sau ngày kết thúc");
        }
        if (ChronoUnit.DAYS.between(from, to) + 1 > MAX_DAYS) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST,
                    "Khoảng ngày không được quá " + MAX_DAYS + " days!");
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
        List<Refund> refunds = refundRepository.findByPayment_IdInAndStatus(paymentIds, "DONE");
        for (Refund refund : refunds) {
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
        Map<String, Total> byRevenueType = new TreeMap<>();
        Map<String, Total> bySource = new TreeMap<>();
        Map<String, Total> byPeriod = new TreeMap<>();
        BigDecimal totalRevenue = BigDecimal.ZERO;
        long needsReview = 0;

        for (RevenueItem item : items) {
            totalRevenue = totalRevenue.add(item.amount());
            byMethod.computeIfAbsent(display(item.method()), key -> new Total()).add(item.amount());
            byPackage.computeIfAbsent(item.packageName(), key -> new Total()).add(item.amount());
            byRevenueType.computeIfAbsent(revenueTypeLabel(item.source()), key -> new Total()).add(item.amount());
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
        response.setByRevenueType(toLines(byRevenueType));
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
            case "CLASS_ENROLLMENT" -> {
                String className = order.getRegistration() == null || order.getRegistration().getSportClass() == null
                        ? null : order.getRegistration().getSportClass().getName();
                yield className == null || className.isBlank() ? "Đăng ký lớp" : "Học phí lớp: " + className;
            }
            case "ONE_ON_ONE" -> {
                if (order.getCoachSlot() == null) yield "Học phí 1-1";
                CoachSlot anchor = order.getCoachSlot();
                String sport = anchor.getCategory() == null ? "Môn chưa xác định" : anchor.getCategory().getName();
                String coach = anchor.getCoach() == null ? "" : " · HLV " + anchor.getCoach().getFullName();
                List<CoachSlot> chain = anchor.getRequest() == null ? List.of(anchor)
                        : coachSlotRepository.findByRequest_IdInOrderByStartAt(List.of(anchor.getRequest().getId()));
                StringBuilder sessions = new StringBuilder();
                int number = 1;
                for (CoachSlot slot : chain) {
                    if (sessions.length() > 0) sessions.append("; ");
                    sessions.append("Buổi ").append(number++).append(": ")
                            .append(slot.getStartAt().toLocalDate()).append(" ")
                            .append(slot.getStartAt().toLocalTime()).append("-")
                            .append(slot.getEndAt().toLocalTime());
                }
                yield "Học phí 1-1: " + sport + coach + (sessions.length() == 0 ? "" : " · " + sessions);
            }
            default -> "Khoản thu khác";
        };
    }

    private String revenueTypeLabel(String source) {
        return switch (source) {
            case "PACKAGE" -> "Gói membership";
            case "CLASS_ENROLLMENT" -> "Học phí lớp";
            case "ONE_ON_ONE" -> "Học phí 1-1";
            default -> "Khoản thu khác";
        };
    }

    private String sourceLabel(String source) {
        return switch (source) {
            case "PACKAGE" -> "Gói membership";
            case "CLASS_ENROLLMENT" -> "Học phí lớp";
            case "ONE_ON_ONE" -> "Học phí 1-1";
            default -> "Khoản thu khác";
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
