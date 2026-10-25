package com.swp391.sportcentermanagementsystem_v2.repository;

import com.swp391.sportcentermanagementsystem_v2.entity.Order;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.Optional;

public interface OrderRepository extends JpaRepository<Order, Long> {

    Optional<Order> findBySubscription_Id(Long subscriptionId);

    Optional<Order> findByRegistration_Id(Long registrationId);

    // Mỗi suất chỉ có một lần đặt nên chỉ có một order
    Optional<Order> findByCoachSlot_Id(Long coachSlotId);
}
