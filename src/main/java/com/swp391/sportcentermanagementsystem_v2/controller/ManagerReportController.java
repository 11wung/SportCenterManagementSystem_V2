package com.swp391.sportcentermanagementsystem_v2.controller;

import com.swp391.sportcentermanagementsystem_v2.dto.responseDTO.ManagerAuditLogResponse;
import com.swp391.sportcentermanagementsystem_v2.dto.responseDTO.ManagerTaskResponse;
import com.swp391.sportcentermanagementsystem_v2.dto.responseDTO.MemberStatsResponse;
import com.swp391.sportcentermanagementsystem_v2.dto.responseDTO.NoteworthyClassResponse;
import com.swp391.sportcentermanagementsystem_v2.dto.responseDTO.ReportDashboardResponse;
import com.swp391.sportcentermanagementsystem_v2.dto.responseDTO.RevenueReportResponse;
import com.swp391.sportcentermanagementsystem_v2.service.ManagerAuditLogService;
import com.swp391.sportcentermanagementsystem_v2.service.ManagerDashboardReportService;
import com.swp391.sportcentermanagementsystem_v2.service.MemberStatsService;
import com.swp391.sportcentermanagementsystem_v2.service.RevenueReportService;
import lombok.RequiredArgsConstructor;
import org.springframework.format.annotation.DateTimeFormat;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.time.LocalDate;
import java.util.List;

@RestController
@RequestMapping("/api/manager/reports")
@RequiredArgsConstructor
public class ManagerReportController {

    private final RevenueReportService revenueReportService;
    private final MemberStatsService memberStatsService;
    private final ManagerDashboardReportService dashboardReportService;
    private final ManagerAuditLogService auditLogService;

    // Doanh thu hiển thị trực tiếp trên website, có bộ lọc thời gian và kiểu gom dữ liệu.
    @GetMapping("/revenue")
    public ResponseEntity<RevenueReportResponse> getRevenue(
            @RequestParam @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate from,
            @RequestParam @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate to,
            @RequestParam(defaultValue = "DAY") String groupBy) {
        return ResponseEntity.ok(revenueReportService.getRevenue(from, to, groupBy));
    }

    @GetMapping("/members")
    public ResponseEntity<MemberStatsResponse> getMemberStats(
            @RequestParam(defaultValue = "7") int expiringWithinDays) {
        return ResponseEntity.ok(memberStatsService.getStats(expiringWithinDays));
    }

    @GetMapping("/dashboard")
    public ResponseEntity<ReportDashboardResponse> getDashboard(
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate from,
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate to) {
        return ResponseEntity.ok(dashboardReportService.getDashboard(from, to));
    }

    @GetMapping("/classes")
    public ResponseEntity<List<NoteworthyClassResponse>> getNoteworthyClasses() {
        return ResponseEntity.ok(dashboardReportService.getNoteworthyClasses());
    }

    @GetMapping("/tasks")
    public ResponseEntity<List<ManagerTaskResponse>> getTasks() {
        return ResponseEntity.ok(dashboardReportService.getTasks());
    }

    @GetMapping("/audit-logs")
    public ResponseEntity<List<ManagerAuditLogResponse>> getAuditLogs() {
        return ResponseEntity.ok(auditLogService.getRecentLogs());
    }
}
