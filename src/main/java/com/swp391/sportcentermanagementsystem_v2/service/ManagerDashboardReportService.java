package com.swp391.sportcentermanagementsystem_v2.service;

import com.swp391.sportcentermanagementsystem_v2.dto.responseDTO.ManagerTaskResponse;
import com.swp391.sportcentermanagementsystem_v2.dto.responseDTO.NoteworthyClassResponse;
import com.swp391.sportcentermanagementsystem_v2.dto.responseDTO.ReportDashboardResponse;
import com.swp391.sportcentermanagementsystem_v2.dto.responseDTO.RevenueReportResponse;
import com.swp391.sportcentermanagementsystem_v2.entity.ClassAttendance;
import com.swp391.sportcentermanagementsystem_v2.entity.ClassRegistration;
import com.swp391.sportcentermanagementsystem_v2.entity.ClassSession;
import com.swp391.sportcentermanagementsystem_v2.entity.Refund;
import com.swp391.sportcentermanagementsystem_v2.entity.SportClass;
import com.swp391.sportcentermanagementsystem_v2.repository.ClassAttendanceRepository;
import com.swp391.sportcentermanagementsystem_v2.repository.ReportClassRegistrationRepository;
import com.swp391.sportcentermanagementsystem_v2.repository.ReportClassSessionRepository;
import com.swp391.sportcentermanagementsystem_v2.repository.ReportRefundRepository;
import com.swp391.sportcentermanagementsystem_v2.repository.ReportSportClassRepository;
import com.swp391.sportcentermanagementsystem_v2.repository.ReportUserRepository;
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
import java.util.Map;

@Service
@RequiredArgsConstructor
public class ManagerDashboardReportService {

    private static final ZoneId VIETNAM_ZONE = ZoneId.of("Asia/Ho_Chi_Minh");
    private static final Collection<String> ACTIVE_CLASS_STATUSES = List.of("OPEN", "IN_PROGRESS");
    private static final Collection<String> OCCUPYING_REGISTRATION_STATUSES = List.of("PENDING_PAYMENT", "REGISTERED");

    private final RevenueReportService revenueReportService;
    private final ReportUserRepository userRepository;
    private final ReportRefundRepository refundRepository;
    private final ReportSportClassRepository sportClassRepository;
    private final ReportClassRegistrationRepository registrationRepository;
    private final ReportClassSessionRepository sessionRepository;
    private final ClassAttendanceRepository attendanceRepository;
    private final ReportSettingService reportSettingService;

    @Transactional(readOnly = true)
    public ReportDashboardResponse getDashboard(LocalDate requestedFrom, LocalDate requestedTo) {
        LocalDate today = LocalDate.now(VIETNAM_ZONE);
        LocalDate from = requestedFrom == null ? today.minusDays(29) : requestedFrom;
        LocalDate to = requestedTo == null ? today : requestedTo;
        if (from.isAfter(to)) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "from must not be after to!");
        }

        RevenueReportResponse revenue = revenueReportService.getRevenue(from, to, "DAY");
        OffsetDateTime start = from.atStartOfDay(VIETNAM_ZONE).toOffsetDateTime();
        OffsetDateTime end = to.plusDays(1).atStartOfDay(VIETNAM_ZONE).toOffsetDateTime();

        ReportDashboardResponse response = new ReportDashboardResponse();
        response.setFrom(from);
        response.setTo(to);
        response.setTotalRevenue(revenue.getTotalRevenue());
        response.setNewMembers(userRepository.countByRole_CodeAndCreatedAtGreaterThanEqualAndCreatedAtLessThan(
                "MEMBER", start, end));

        List<SportClass> activeClasses = sportClassRepository.findByStatusInOrderByStartDateAsc(ACTIVE_CLASS_STATUSES);
        List<Long> classIds = activeClasses.stream().map(SportClass::getId).toList();
        Map<Long, Long> occupiedByClass = occupiedSeatsByClass(classIds);
        long occupied = 0;
        long capacity = 0;
        for (SportClass sportClass : activeClasses) {
            occupied += occupiedByClass.getOrDefault(sportClass.getId(), 0L);
            capacity += Math.max(0, sportClass.getMaxCapacity() == null ? 0 : sportClass.getMaxCapacity());
        }
        response.setOccupiedSeats(occupied);
        response.setTotalCapacity(capacity);
        response.setOccupancyRatePercent(capacity == 0 ? BigDecimal.ZERO.setScale(2, RoundingMode.HALF_UP)
                : BigDecimal.valueOf(occupied).multiply(new BigDecimal("100"))
                        .divide(BigDecimal.valueOf(capacity), 2, RoundingMode.HALF_UP));

        List<Refund> pendingRefunds = refundRepository.findByStatusOrderByRequestedAtAsc("PENDING");
        BigDecimal pendingAmount = BigDecimal.ZERO;
        for (Refund refund : pendingRefunds) {
            if (refund.getAmount() != null) pendingAmount = pendingAmount.add(refund.getAmount());
        }
        response.setPendingRefundCount(pendingRefunds.size());
        response.setPendingRefundAmount(pendingAmount);
        return response;
    }

    @Transactional(readOnly = true)
    public List<NoteworthyClassResponse> getNoteworthyClasses() {
        LocalDate today = LocalDate.now(VIETNAM_ZONE);
        int lowFillPercent = reportSettingService.getIntField("overview.notable_class", "low_fill_pct", 50);
        int nearStartDays = reportSettingService.getIntField("overview.notable_class", "near_start_days", 7);
        int canceledThreshold = reportSettingService.getIntField("overview.notable_class", "canceled_sessions", 2);

        List<SportClass> activeClasses = sportClassRepository.findByStatusInOrderByStartDateAsc(ACTIVE_CLASS_STATUSES);
        if (activeClasses.isEmpty()) return List.of();

        List<Long> classIds = activeClasses.stream().map(SportClass::getId).toList();
        Map<Long, Long> occupiedByClass = occupiedSeatsByClass(classIds);
        Map<Long, Long> canceledByClass = new HashMap<>();
        for (ClassSession session : sessionRepository.findBySportClass_IdInAndStatusIn(
                classIds, List.of("CANCELED_BY_CENTER", "SCHEDULED", "DONE", "PROPOSED"))) {
            if ("CANCELED_BY_CENTER".equals(session.getStatus())) {
                canceledByClass.merge(session.getSportClass().getId(), 1L, Long::sum);
            }
        }
        Map<Long, Long> absentByClass = new HashMap<>();
        for (ClassAttendance attendance : attendanceRepository.findBySession_SportClass_IdInAndStatus(classIds, "ABSENT")) {
            absentByClass.merge(attendance.getSession().getSportClass().getId(), 1L, Long::sum);
        }

        List<NoteworthyClassResponse> result = new ArrayList<>();
        for (SportClass sportClass : activeClasses) {
            long occupied = occupiedByClass.getOrDefault(sportClass.getId(), 0L);
            int capacity = Math.max(0, sportClass.getMaxCapacity() == null ? 0 : sportClass.getMaxCapacity());
            long absent = absentByClass.getOrDefault(sportClass.getId(), 0L);
            long canceled = canceledByClass.getOrDefault(sportClass.getId(), 0L);
            BigDecimal fill = capacity == 0 ? BigDecimal.ZERO
                    : BigDecimal.valueOf(occupied).multiply(new BigDecimal("100"))
                            .divide(BigDecimal.valueOf(capacity), 2, RoundingMode.HALF_UP);
            List<String> reasons = new ArrayList<>();

            if (absent > 0) reasons.add("Có " + absent + " lượt vắng");
            if (capacity > 0 && occupied >= capacity) reasons.add("Đã kín chỗ");
            if (sportClass.getStartDate() != null) {
                long daysUntilStart = ChronoUnit.DAYS.between(today, sportClass.getStartDate());
                if (daysUntilStart >= 0 && daysUntilStart <= nearStartDays
                        && fill.compareTo(BigDecimal.valueOf(lowFillPercent)) < 0) {
                    reasons.add("Sắp khai giảng trong " + daysUntilStart + " ngày nhưng mới lấp đầy "
                            + fill.stripTrailingZeros().toPlainString() + "%");
                }
            }
            if (canceled >= canceledThreshold) {
                reasons.add("Có từ " + canceledThreshold + " buổi bị trung tâm hủy");
            }
            if (reasons.isEmpty()) continue;

            NoteworthyClassResponse item = new NoteworthyClassResponse();
            item.setClassId(sportClass.getId());
            item.setClassName(sportClass.getName());
            item.setStatus(sportClass.getStatus());
            item.setStartDate(sportClass.getStartDate());
            item.setReservedSeats(occupied);
            item.setMaxCapacity(capacity);
            item.setFillPercent(fill);
            item.setAbsentCount(absent);
            item.setCanceledSessionCount(canceled);
            item.setReasons(reasons);
            result.add(item);
        }
        return result;
    }

    @Transactional(readOnly = true)
    public List<ManagerTaskResponse> getTasks() {
        LocalDate today = LocalDate.now(VIETNAM_ZONE);
        int overdueAfterDays = reportSettingService.getInt("overview.overdue_refund_days", 3);
        List<ManagerTaskResponse> result = new ArrayList<>();
        for (Refund refund : refundRepository.findByStatusOrderByRequestedAtAsc("PENDING")) {
            OffsetDateTime requestedAt = refund.getRequestedAt();
            long daysWaiting = requestedAt == null ? 0 : Math.max(0,
                    ChronoUnit.DAYS.between(requestedAt.atZoneSameInstant(VIETNAM_ZONE).toLocalDate(), today));

            ManagerTaskResponse item = new ManagerTaskResponse();
            item.setId(refund.getId());
            item.setType("REFUND");
            item.setMemberName(refund.getPayment() == null || refund.getPayment().getPayer() == null
                    ? "Unknown member" : refund.getPayment().getPayer().getFullName());
            item.setInvoiceNumber(refund.getPayment() == null ? null : refund.getPayment().getInvoiceNumber());
            item.setAmount(refund.getAmount());
            item.setCreatedAt(requestedAt);
            item.setDaysWaiting(daysWaiting);
            item.setOverdue(daysWaiting > overdueAfterDays);
            item.setTitle("Xử lý hoàn tiền #" + refund.getId() + " - " + item.getMemberName());
            result.add(item);
        }
        return result;
    }

    private Map<Long, Long> occupiedSeatsByClass(Collection<Long> classIds) {
        Map<Long, Long> countByClass = new HashMap<>();
        if (classIds == null || classIds.isEmpty()) return countByClass;
        for (ClassRegistration registration : registrationRepository.findBySportClass_IdInAndStatusIn(
                classIds, OCCUPYING_REGISTRATION_STATUSES)) {
            Long classId = registration.getSportClass().getId();
            countByClass.merge(classId, 1L, Long::sum);
        }
        return countByClass;
    }
}
