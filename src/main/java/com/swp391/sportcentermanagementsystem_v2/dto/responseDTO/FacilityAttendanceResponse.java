package com.swp391.sportcentermanagementsystem_v2.dto.responseDTO;

import com.swp391.sportcentermanagementsystem_v2.entity.FacilityAttendance;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

import java.time.OffsetDateTime;

@Getter
@Setter
@NoArgsConstructor
public class FacilityAttendanceResponse {
    private Long id;
    private Long memberId;
    private String memberName;
    private String memberEmail;
    private OffsetDateTime checkInTime;
    private OffsetDateTime checkOutTime;
    private String recordedByName;
    private String notes;

    public FacilityAttendanceResponse(FacilityAttendance a) {
        this.id = a.getId();
        this.memberId = a.getUser().getId();
        this.memberName = a.getUser().getFullName();
        this.memberEmail = a.getUser().getEmail();
        this.checkInTime = a.getCheckInTime();
        this.checkOutTime = a.getCheckOutTime();
        this.recordedByName = a.getRecordedBy() == null ? null : a.getRecordedBy().getFullName();
        this.notes = a.getNotes();
    }
}
