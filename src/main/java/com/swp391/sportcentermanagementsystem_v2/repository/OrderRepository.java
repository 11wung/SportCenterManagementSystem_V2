package com.swp391.sportcentermanagementsystem_v2.repository;

import com.swp391.sportcentermanagementsystem_v2.entity.PurchaseOrder;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.Optional;

public interface OrderRepository extends JpaRepository<PurchaseOrder, Long> {

    Optional<PurchaseOrder> findBySubscription_Id(Long subscriptionId);

    Optional<PurchaseOrder> findByRegistration_Id(Long registrationId);

    // Mỗi suất chỉ có một lần đặt nên chỉ có một order
    Optional<PurchaseOrder> findByCoachSlot_Id(Long coachSlotId);
}
