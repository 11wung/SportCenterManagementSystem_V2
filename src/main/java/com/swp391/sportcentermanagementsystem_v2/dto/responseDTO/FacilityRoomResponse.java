package com.swp391.sportcentermanagementsystem_v2.dto.responseDTO;

import com.swp391.sportcentermanagementsystem_v2.entity.FacilityRoom;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;
import lombok.AllArgsConstructor;


@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
public class FacilityRoomResponse {
    private Long id;
    private String roomName;
    private String floor;
    private Integer capacity;
    private String description;
    private String maintenanceStatus;

    public FacilityRoomResponse(FacilityRoom room) {
        this.id = room.getId();
        this.roomName = room.getRoomName();
        this.floor = room.getFloor();
        this.capacity = room.getCapacity();
        this.description = room.getDescription();
        this.maintenanceStatus = room.getMaintenanceStatus();
    }
}
