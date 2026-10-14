
package com.swp391.sportcentermanagementsystem_v2.repository;

import com.swp391.sportcentermanagementsystem_v2.entity.Payment;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.Optional;

public interface PaymentRepository extends JpaRepository<Payment, Long> {

    Optional<Payment> findByTransactionCode(String transactionCode);

    boolean existsByInvoiceNumber(String invoiceNumber);

    // Kiểm tra Subscription đã có thanh toán SUCCESS chưa
    boolean existsBySubscription_IdAndStatus(
            Long subscriptionId,
            String status
    );

    // Lấy lịch sử thanh toán của một Member
    List<Payment> findByPayer_IdOrderByCreatedAtDesc(Long payerId);
}
