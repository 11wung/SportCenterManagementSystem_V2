package com.swp391.sportcentermanagementsystem_v2.controller;

import com.swp391.sportcentermanagementsystem_v2.dto.responseDTO.RevenueReportResponse;
import com.swp391.sportcentermanagementsystem_v2.service.RevenueReportService;

import lombok.RequiredArgsConstructor;

import org.springframework.format.annotation.DateTimeFormat;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.time.LocalDate;

@RestController
@RequestMapping("/api/manager/reports")
@RequiredArgsConstructor
public class ManagerReportController {

    private final RevenueReportService revenueReportService;

    // API 1: GET /api/manager/reports/revenue?from=2026-10-01&to=2026-10-31&groupBy=MONTH
    // Manager xem doanh thu (payment SUCCESS) theo khoảng ngày giờ Việt Nam.
    // groupBy: DAY (mặc định), MONTH hoặc QUARTER.
    @GetMapping("/revenue")
    public ResponseEntity<RevenueReportResponse> getRevenue(
            @RequestParam @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate from,
            @RequestParam @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate to,
            @RequestParam(defaultValue = "DAY") String groupBy) {

        return ResponseEntity.ok(revenueReportService.getRevenue(from, to, groupBy));
    }
}
