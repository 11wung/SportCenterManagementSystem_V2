package com.swp391.sportcentermanagementsystem_v2.repository;

import com.swp391.sportcentermanagementsystem_v2.entity.TimeSlot;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;

public interface TimeSlotRepository extends JpaRepository<TimeSlot, Long> {

    List<TimeSlot> findAllByOrderByStartTime();

    List<TimeSlot> findByActiveTrueOrderByStartTime();

    boolean existsBySlotNo(Short slotNo);
}
