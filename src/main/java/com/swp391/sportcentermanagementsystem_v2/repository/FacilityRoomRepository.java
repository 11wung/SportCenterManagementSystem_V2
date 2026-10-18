package com.swp391.sportcentermanagementsystem_v2.repository;

import com.swp391.sportcentermanagementsystem_v2.entity.FacilityRoom;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;
@Repository
public interface FacilityRoomRepository extends JpaRepository<FacilityRoom, Long> {
    boolean existsByRoomName(String roomName);

boolean existsByRoomNameAndIdNot(String roomName, Long id);
}
