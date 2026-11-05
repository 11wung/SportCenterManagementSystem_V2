package com.swp391.sportcentermanagementsystem_v2.repository;

import com.swp391.sportcentermanagementsystem_v2.entity.CoachSlotRequestDay;
import org.springframework.data.jpa.repository.EntityGraph;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.Collection;
import java.util.List;

public interface CoachSlotRequestDayRepository extends JpaRepository<CoachSlotRequestDay, Long> {

    // Các cặp (thứ, giờ) của nhiều yêu cầu một lần, kèm khung giờ để khỏi tải thêm
    @EntityGraph(attributePaths = { "request", "timeSlot" })
    List<CoachSlotRequestDay> findByRequest_IdInOrderByWeekday(Collection<Long> requestIds);
}
