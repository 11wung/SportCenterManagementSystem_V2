package com.swp391.sportcentermanagementsystem_v2.service;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.time.LocalDate;

@Slf4j
@Component
@RequiredArgsConstructor
public class ReminderScheduler {

    private final ReminderService reminderService;

    // Mỗi ngày lúc 18:00 giờ Việt Nam: nhắc buổi học ngày mai và gói sắp hết hạn. Mỗi loại tự bắt lỗi riêng.
    // shortcut: máy chủ tắt đúng 18:00 thì ngày đó bỏ lỡ nhắc nhở, chấp nhận như ClassLifecycleScheduler
    @Scheduled(cron = "0 0 18 * * *", zone = "Asia/Ho_Chi_Minh")
    public void sendDailyReminders() {
        LocalDate today = LocalDate.now(SportClassService.CENTER_ZONE);
        try {
            log.info("Session reminders sent: {}", reminderService.remindTomorrowSessions(today));
        } catch (RuntimeException ex) {
            log.error("Failed to send session reminders", ex);
        }
        try {
            log.info("Expiry reminders sent: {}", reminderService.remindExpiringSubscriptions(today));
        } catch (RuntimeException ex) {
            log.error("Failed to send expiry reminders", ex);
        }
    }
}
