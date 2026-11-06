package com.swp391.sportcentermanagementsystem_v2.dto.responseDTO;

import com.swp391.sportcentermanagementsystem_v2.entity.CoachSlotRequest;
import com.swp391.sportcentermanagementsystem_v2.entity.CoachSlotRequestDay;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.LocalTime;
import java.time.OffsetDateTime;
import java.util.List;

@Getter
@Setter
@NoArgsConstructor
public class CoachSlotRequestResponse {
    private Long id;
    private Long memberId;
    private String memberName;
    private Long coachId;
    private String coachName;
    private Long sportId;
    private String sportName;
    private Long timeSlotId;
    private LocalTime startTime;
    private LocalTime endTime;
    private LocalDate firstDate;
    // Các cặp (thứ, giờ) của yêu cầu, thứ 1 = thứ 2 đến 7 = chủ nhật. Yêu cầu cũ chỉ có một cặp: thứ của firstDate và giờ trên
    private List<DayResponse> days;
    private int sessions;
    private String status;
    private String rejectReason;
    // PENDING: hạn để manager duyệt. APPROVED: hạn để member trả tiền
    private OffsetDateTime expiresAt;
    // Giá gốc cả chuỗi, ưu đãi theo hạng thành viên và số phải trả
    private BigDecimal listTotal;
    private BigDecimal discountAmount;
    private BigDecimal payableTotal;
    // Các buổi đã được tạo; rỗng khi chưa duyệt hoặc đã bị từ chối, hủy, hết hạn
    private List<CoachSlotResponse> slots;
    private OffsetDateTime createdAt;

    public CoachSlotRequestResponse(CoachSlotRequest r, List<CoachSlotRequestDay> days, BigDecimal listTotal, BigDecimal discountAmount,
                                    List<CoachSlotResponse> slots) {
        this.id = r.getId();
        this.memberId = r.getMember().getId();
        this.memberName = r.getMember().getFullName();
        this.coachId = r.getCoach().getId();
        this.coachName = r.getCoach().getFullName();
        this.sportId = r.getCategory().getId();
        this.sportName = r.getCategory().getName();
        this.timeSlotId = r.getTimeSlot().getId();
        this.startTime = r.getTimeSlot().getStartTime();
        this.endTime = r.getTimeSlot().getEndTime();
        this.firstDate = r.getFirstDate();
        this.days = days.isEmpty()
                ? List.of(new DayResponse(r.getFirstDate().getDayOfWeek().getValue(), r.getTimeSlot().getId(),
                        r.getTimeSlot().getStartTime(), r.getTimeSlot().getEndTime()))
                : days.stream().map(d -> new DayResponse(d.getWeekday(), d.getTimeSlot().getId(),
                        d.getTimeSlot().getStartTime(), d.getTimeSlot().getEndTime())).toList();
        this.sessions = r.getSessions();
        this.status = r.getStatus();
        this.rejectReason = r.getRejectReason();
        this.expiresAt = r.getExpiresAt();
        this.listTotal = listTotal;
        this.discountAmount = discountAmount;
        this.payableTotal = listTotal.subtract(discountAmount);
        this.slots = slots;
        this.createdAt = r.getCreatedAt();
    }

    public record DayResponse(int weekday, Long timeSlotId, LocalTime startTime, LocalTime endTime) {}
}
