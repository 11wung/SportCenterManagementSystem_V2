package com.swp391.sportcentermanagementsystem_v2.repository;

import com.swp391.sportcentermanagementsystem_v2.entity.FacilityRoom;
import org.springframework.data.jpa.repository.JpaRepository;

@Repository
public interface FacilityRoomRepository extends JpaRepository<FacilityRoom, Long> {
}
