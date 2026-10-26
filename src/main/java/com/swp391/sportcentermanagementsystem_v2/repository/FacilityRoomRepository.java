package com.swp391.sportcentermanagementsystem_v2.repository;

import com.swp391.sportcentermanagementsystem_v2.entity.FacilityRoom;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.stereotype.Repository;

import java.util.List;

@Repository
public interface FacilityRoomRepository extends JpaRepository<FacilityRoom, Long> {
    boolean existsByRoomName(String roomName);

    // Phòng đang hoạt động kèm môn, để xếp lịch không phải tải môn từng phòng một
    @Query("select distinct r from FacilityRoom r left join fetch r.sports where upper(r.maintenanceStatus) = 'AVAILABLE' order by r.roomName")
    List<FacilityRoom> findAvailableWithSports();

boolean existsByRoomNameAndIdNot(String roomName, Long id);
}
