package com.swp391.sportcentermanagementsystem_v2.service;

import com.swp391.sportcentermanagementsystem_v2.entity.ClassRegistration;
import com.swp391.sportcentermanagementsystem_v2.entity.SportClass;
import com.swp391.sportcentermanagementsystem_v2.repository.ClassRegistrationRepository;
import com.swp391.sportcentermanagementsystem_v2.repository.CoachSlotRepository;
import com.swp391.sportcentermanagementsystem_v2.repository.SportClassRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.time.temporal.ChronoUnit;
import java.util.List;

// BR-WL-03: có chỗ trống thì mời người đứng đầu danh sách chờ bằng cách chuyển họ sang PENDING_PAYMENT với hạn
// giữ chỗ dài (12 giờ, còn dưới 3 ngày đến khai giảng thì 3 giờ). Từ đó mọi thứ chạy như đăng ký thường:
// trả QR, thu tiền mặt, hủy, và hết hạn thì bị hủy rồi chỗ chuyển cho người kế tiếp
@Slf4j
@Service
@RequiredArgsConstructor
public class WaitlistService {

    private static final List<String> SEAT_STATUSES = List.of("PENDING_PAYMENT", "REGISTERED");

    private final ClassRegistrationRepository classRegistrationRepository;
    private final SportClassRepository sportClassRepository;
    private final CoachSlotRepository coachSlotRepository;
    private final SystemSettingService systemSettingService;

    // Mời từ danh sách chờ cho tới khi hết chỗ trống. Gọi sau mọi lần nhả chỗ. Trả số người được mời
    @Transactional
    public int promote(Long classId) {
        SportClass sportClass = sportClassRepository.findByIdForUpdate(classId).orElse(null);
        LocalDate today = LocalDate.now(SportClassService.CENTER_ZONE);
        // Danh sách chờ chỉ có trước ngày khai giảng (BR-WL-04)
        if (sportClass == null || !"OPEN".equals(sportClass.getStatus()) || !today.isBefore(sportClass.getStartDate())) {
            return 0;
        }

        long free = sportClass.getMaxCapacity()
                - classRegistrationRepository.countBySportClass_IdAndStatusIn(classId, SEAT_STATUSES);
        if (free <= 0) {
            return 0;
        }

        int bufferMinutes = systemSettingService.getInt("schedule.buffer_minutes");
        int invited = 0;
        for (ClassRegistration listed : classRegistrationRepository.findWaitlist(classId)) {
            if (free <= 0) {
                break;
            }
            // Khóa rồi đọc lại: người đó có thể vừa rời hàng chờ
            ClassRegistration candidate = classRegistrationRepository.findByIdForUpdate(listed.getId()).orElse(null);
            if (candidate == null || !"WAITLISTED".equals(candidate.getStatus())) {
                continue;
            }
            // BR-WL-04: trùng giờ với class hoặc buổi 1-1 họ có sau khi vào chờ thì bỏ qua, họ vẫn ở lại hàng chờ
            Long memberId = candidate.getUser().getId();
            if (classRegistrationRepository.findConflictingClassName(memberId, classId, bufferMinutes).isPresent()
                    || coachSlotRepository.findMemberSlotConflictForClass(memberId, classId, bufferMinutes).isPresent()) {
                continue;
            }

            if (sportClass.getTuition().signum() == 0) {
                candidate.setStatus("REGISTERED"); // lớp miễn phí, không cần thanh toán
            } else {
                OffsetDateTime until = OffsetDateTime.now().plusHours(holdHours(sportClass, today));
                candidate.setStatus("PENDING_PAYMENT");
                candidate.setHoldExpiresAt(until);
                candidate.setOfferExpiresAt(until);
            }
            classRegistrationRepository.save(candidate);
            free--;
            invited++;
            // shortcut: chỉ ghi log, thay bằng NotificationService của Flow 1 khi có (BR-NTF-01)
            log.info("NOTIFY user {}: a seat is available in class {}, pay before {}",
                    memberId, classId, candidate.getOfferExpiresAt());
        }
        return invited;
    }

    // Các class đang có người chờ, để job định kỳ mời bù những chỗ nhả ra mà không có sự kiện hủy đi kèm
    // (hết hạn giữ chỗ, tăng sức chứa)
    @Transactional(readOnly = true)
    public List<Long> classIdsWithWaitlist() {
        return classRegistrationRepository.findClassIdsWithWaitlist();
    }

    // BR-WL-03: giữ chỗ 12 giờ, còn dưới 3 ngày đến khai giảng thì 3 giờ
    private long holdHours(SportClass sportClass, LocalDate today) {
        boolean nearStart = ChronoUnit.DAYS.between(today, sportClass.getStartDate())
                < systemSettingService.getInt("waitlist.near_start_days");
        return systemSettingService.getInt(nearStart ? "waitlist.hold_hours_near_start" : "waitlist.hold_hours");
    }
}
