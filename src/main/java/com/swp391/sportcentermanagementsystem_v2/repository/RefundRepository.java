package com.swp391.sportcentermanagementsystem_v2.repository;

import com.swp391.sportcentermanagementsystem_v2.entity.Refund;
import jakarta.persistence.LockModeType;
import org.springframework.data.jpa.repository.EntityGraph;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.List;
import java.util.Optional;

public interface RefundRepository extends JpaRepository<Refund, Long> {

    boolean existsByPayment_Id(Long paymentId);

    boolean existsByPayment_IdAndReason(Long paymentId, String reason);

    // Còn khoản hoàn nào khác chưa xử lý của cùng payment không (để biết khi nào payment được coi là đã hoàn xong)
    boolean existsByPayment_IdAndStatusAndIdNot(Long paymentId, String status, Long id);

    // Tổng đã yêu cầu hoàn cho một payment (cả khoản chờ và khoản đã hoàn)
    @Query("select coalesce(sum(r.amount), 0) from Refund r where r.payment.id = :paymentId")
    java.math.BigDecimal sumAmountByPaymentId(@Param("paymentId") Long paymentId);

    // Khóa dòng hoàn tiền để hai người không cùng bấm "đã hoàn"
    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("select r from Refund r where r.id = :id")
    Optional<Refund> findByIdForUpdate(@Param("id") Long id);

    // Cũ nhất trước: khoản chờ lâu nhất phải được xử lý trước
    @EntityGraph(attributePaths = { "payment", "payment.payer", "processedBy" })
    List<Refund> findByStatusOrderByRequestedAtAsc(String status);

    @EntityGraph(attributePaths = { "payment", "payment.payer", "processedBy" })
    List<Refund> findAllByOrderByRequestedAtDesc();
}
