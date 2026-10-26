package com.swp391.sportcentermanagementsystem_v2.service;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.time.LocalDate;

@Slf4j
@Component
@RequiredArgsConstructor
public class VoucherScheduler {

    private final VoucherService voucherService;

    // Mỗi ngày lúc 00:05 giờ Việt Nam (cùng giờ với ClassLifecycleScheduler): phát voucher sinh nhật, gia hạn sớm, quay lại.
    // Mỗi loại tự bắt lỗi riêng để một loại lỗi không chặn hai loại còn lại.
    // shortcut: nếu máy chủ tắt đúng lúc 00:05 thì ngày đó không ai được phát, chấp nhận bỏ lỡ như ClassLifecycleScheduler
    @Scheduled(cron = "0 5 0 * * *", zone = "Asia/Ho_Chi_Minh")
    public void issueDailyVouchers() {
        LocalDate today = LocalDate.now(SportClassService.CENTER_ZONE);
        try {
            log.info("Birthday vouchers issued: {}", voucherService.issueBirthday(today));
        } catch (RuntimeException ex) {
            log.error("Failed to issue birthday vouchers", ex);
        }
        try {
            log.info("Early renewal vouchers issued: {}", voucherService.issueEarlyRenewal(today));
        } catch (RuntimeException ex) {
            log.error("Failed to issue early renewal vouchers", ex);
        }
        try {
            log.info("Winback vouchers issued: {}", voucherService.issueWinback(today));
        } catch (RuntimeException ex) {
            log.error("Failed to issue winback vouchers", ex);
        }
    }
}
