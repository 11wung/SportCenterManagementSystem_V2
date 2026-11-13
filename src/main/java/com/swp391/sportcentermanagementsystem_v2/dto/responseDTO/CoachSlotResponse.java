package com.swp391.sportcentermanagementsystem_v2.dto.responseDTO;

import com.swp391.sportcentermanagementsystem_v2.entity.CoachSlot;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

import java.math.BigDecimal;
import java.time.OffsetDateTime;

@Getter
@Setter
@NoArgsConstructor
public class CoachSlotResponse {
    private Long id;
    private Long coachId;
    // Có khi suất thuộc một yêu cầu 1-1 nhiều buổi
    private Long requestId;
    // Trung tâm không dạy được buổi này, hội viên cần chọn giờ mới
    private boolean needsReschedule;
    private String coachName;
    private OffsetDateTime startAt;
    private OffsetDateTime endAt;
    private long minutes;
    private String status;
    // Người đặt: chỉ có khi HELD hoặc BOOKED
    private Long memberId;
    private String memberName;
    private OffsetDateTime heldUntil;
    // Suất OPEN: giá chung hiện tại; HELD/BOOKED: giá đã chụp lúc đặt
    private BigDecimal price;
    // Số tiền được hoàn khi trung tâm hủy buổi, null nếu không hoàn
    private BigDecimal refundAmount;
    // Môn và phòng của buổi 1-1; null với suất cũ chưa xếp phòng
    private Long sportId;
    private String sportName;
    private Long roomId;
    private String roomName;
    private String floor;
    // Vị trí của buổi trong chuỗi 1-1 cùng yêu cầu (không tính buổi đã hủy): buổi thứ mấy, tổng số buổi, số buổi đã học
    private Integer seriesNo;
    private Integer seriesTotal;
    private Integer seriesDone;

    public CoachSlotResponse(CoachSlot s, BigDecimal price) {
        this.id = s.getId();
        this.coachId = s.getCoach().getId();
        this.coachName = s.getCoach().getFullName();
        this.requestId = s.getRequest() == null ? null : s.getRequest().getId();
        this.needsReschedule = s.isNeedsReschedule();
        this.startAt = s.getStartAt();
        this.endAt = s.getEndAt();
        this.minutes = java.time.Duration.between(s.getStartAt(), s.getEndAt()).toMinutes();
        this.status = s.getStatus();
        if (s.getMember() != null) {
            this.memberId = s.getMember().getId();
            this.memberName = s.getMember().getFullName();
        }
        this.heldUntil = s.getHeldUntil();
        this.price = price;
        if (s.getCategory() != null) {
            this.sportId = s.getCategory().getId();
            this.sportName = s.getCategory().getName();
        }
        if (s.getRoom() != null) {
            this.roomId = s.getRoom().getId();
            this.roomName = s.getRoom().getRoomName();
            this.floor = s.getRoom().getFloor();
        }
    }
}
