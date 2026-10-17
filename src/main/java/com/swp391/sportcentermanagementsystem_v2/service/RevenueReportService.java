package com.swp391.sportcentermanagementsystem_v2.service;

import com.swp391.sportcentermanagementsystem_v2.dto.responseDTO.RevenueLine;
import com.swp391.sportcentermanagementsystem_v2.dto.responseDTO.RevenueReportResponse;
import com.swp391.sportcentermanagementsystem_v2.repository.PaymentRepository;

import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.time.ZoneId;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.TreeMap;

@Service
@RequiredArgsConstructor
public class RevenueReportService {

    private final PaymentRepository paymentRepository;

    private static final ZoneId VIETNAM_ZONE = ZoneId.of("Asia/Ho_Chi_Minh");

    private static final long MAX_DAYS = 366;

    // Tổng tiền và số giao dịch của một nhóm.
    private static class Total {
        BigDecimal amount = BigDecimal.ZERO;
        long count = 0;

        void add(BigDecimal value) {
            amount = amount.add(value);
            count++;
        }
    }

    // Doanh thu = tiền thực nhận của các payment SUCCESS, tính theo ngày trả (giờ Việt Nam).
    //
    // shortcut: lấy các dòng payment trong khoảng rồi cộng nhóm bằng Java (đủ nhanh cho
    // vài nghìn payment mỗi năm); khi lên hàng trăm nghìn thì chuyển sang cộng nhóm trong SQL.
    @Transactional(readOnly = true)
    public RevenueReportResponse getRevenue(LocalDate from, LocalDate to, String groupBy) {

        String group = groupBy == null
                ? "DAY"
                : groupBy.trim().toUpperCase(Locale.ROOT);

        if (!group.equals("DAY") && !group.equals("MONTH") && !group.equals("QUARTER")) {
            throw new ResponseStatusException(
                    HttpStatus.BAD_REQUEST,
                    "groupBy must be DAY, MONTH or QUARTER!");
        }

        if (from.isAfter(to)) {
            throw new ResponseStatusException(
                    HttpStatus.BAD_REQUEST,
                    "from must not be after to!");
        }

        if (ChronoUnit.DAYS.between(from, to) + 1 > MAX_DAYS) {
            throw new ResponseStatusException(
                    HttpStatus.BAD_REQUEST,
                    "The date range must not exceed " + MAX_DAYS + " days!");
        }

        // [00:00 ngày from, 00:00 ngày sau to) theo giờ Việt Nam.
        OffsetDateTime start = from.atStartOfDay(VIETNAM_ZONE).toOffsetDateTime();
        OffsetDateTime end = to.plusDays(1).atStartOfDay(VIETNAM_ZONE).toOffsetDateTime();

        List<PaymentRepository.RevenueRow> rows = paymentRepository.findPaidBetween(start, end);

        // TreeMap: nhãn ngày, tháng, quý đều sắp xếp đúng thứ tự theo chữ.
        Map<String, Total> byMethod = new TreeMap<>();
        Map<String, Total> byPackage = new TreeMap<>();
        Map<String, Total> byPeriod = new TreeMap<>();

        BigDecimal totalRevenue = BigDecimal.ZERO;
        long needsReview = 0;

        for (PaymentRepository.RevenueRow row : rows) {

            BigDecimal amount = row.getAmount();

            totalRevenue = totalRevenue.add(amount);

            byMethod.computeIfAbsent(row.getPaymentMethod(), k -> new Total()).add(amount);
            byPackage.computeIfAbsent(row.getPackageName(), k -> new Total()).add(amount);
            byPeriod.computeIfAbsent(periodLabel(row.getPaidAt(), group), k -> new Total()).add(amount);

            String gatewayResponse = row.getGatewayResponse();

            if (gatewayResponse != null && gatewayResponse.startsWith("REQUIRES_REVIEW:")) {
                needsReview++;
            }
        }

        RevenueReportResponse response = new RevenueReportResponse();

        response.setFrom(from);
        response.setTo(to);
        response.setGroupBy(group);
        response.setTotalRevenue(totalRevenue);
        response.setPaymentCount(rows.size());
        response.setNeedsReviewCount(needsReview);
        response.setByMethod(toLines(byMethod));
        response.setByPackage(toLines(byPackage));
        response.setByPeriod(toLines(byPeriod));

        return response;
    }

    // Nhãn kỳ theo NGÀY giờ Việt Nam, để khoản trả sát nửa đêm không bị lệch kỳ.
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

        totals.forEach((label, total) -> lines.add(
                new RevenueLine(label, total.amount, total.count)));

        return lines;
    }
}
