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
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.time.OffsetDateTime;
import java.util.List;

// BR-WL-03: mời người đứng đầu danh sách chờ bằng PENDING_PAYMENT. Member có 24 giờ để nộp tiền mặt;
// mã QR chỉ có hiệu lực tối đa 15 phút. Nếu quá hạn, hệ thống hủy lượt mời và chuyển chỗ cho người kế tiếp.
@Slf4j
@Service
@RequiredArgsConstructor
public class WaitlistService {

    private static final List<String> SEAT_STATUSES = List.of("PENDING_PAYMENT", "REGISTERED");

    private static final DateTimeFormatter OFFER_TIME = DateTimeFormatter.ofPattern("HH:mm 'ngày' dd/MM/yyyy")
            .withZone(ZoneId.of("Asia/Ho_Chi_Minh"));

    private final ClassRegistrationRepository classRegistrationRepository;
    private final SportClassRepository sportClassRepository;
    private final CoachSlotRepository coachSlotRepository;
    private final SystemSettingService systemSettingService;
    private final NotificationService notificationService;

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

        int bufferMinutes = systemSettingService.getScheduleBufferMinutes();
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
                OffsetDateTime until = OffsetDateTime.now(SportClassService.CENTER_ZONE).plusHours(24);
                candidate.setStatus("PENDING_PAYMENT");
                candidate.setHoldExpiresAt(until);
                candidate.setOfferExpiresAt(until);
            }
            classRegistrationRepository.save(candidate);
            free--;
            invited++;
            String mailText = candidate.getOfferExpiresAt() == null
                    ? "Lớp " + sportClass.getName() + " vừa có chỗ và bạn đã được ghi danh."
                    : "Lớp " + sportClass.getName() + " vừa có chỗ cho bạn. Hãy thanh toán trước "
                            + OFFER_TIME.format(candidate.getOfferExpiresAt()) + " để giữ chỗ, quá giờ này chỗ sẽ được mời người kế tiếp.";
            notificationService.sendWithEmail(memberId, "WAITLIST", "Có chỗ trống",
                    candidate.getOfferExpiresAt() == null
                            ? "Đã có chỗ trống ở lớp " + sportClass.getName() + " và bạn đã được ghi danh."
                            : "Có chỗ trống ở lớp " + sportClass.getName() + ". Hãy thanh toán trước "
                                    + candidate.getOfferExpiresAt() + " để giữ chỗ.",
                    "Sport Center - Đã có chỗ trong lớp " + sportClass.getName(), mailText);
        }
        return invited;
    }

    // Các class đang có người chờ, để job định kỳ mời bù những chỗ nhả ra mà không có sự kiện hủy đi kèm
    // (hết hạn giữ chỗ, tăng sức chứa)
    @Transactional(readOnly = true)
    public List<Long> classIdsWithWaitlist() {
        return classRegistrationRepository.findClassIdsWithWaitlist();
    }

}
