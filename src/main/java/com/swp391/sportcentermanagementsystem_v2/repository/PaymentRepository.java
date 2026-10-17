package com.swp391.sportcentermanagementsystem_v2.repository;

import com.swp391.sportcentermanagementsystem_v2.entity.Payment;
import org.springframework.data.jpa.repository.EntityGraph;
import org.springframework.data.jpa.repository.JpaRepository;
import jakarta.persistence.LockModeType;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.time.OffsetDateTime;
import java.util.List;
import java.util.Optional;

public interface PaymentRepository extends JpaRepository<Payment, Long> {

    // Find the latest active link reservation. Caller must hold Member row lock.
    @EntityGraph(attributePaths = "payer")
    Optional<Payment> findFirstBySubscription_IdAndPaymentMethodAndStatusInOrderByIdDesc(
            Long subscriptionId, String paymentMethod, List<String> statuses);

    Optional<Payment> findByGatewayOrderCode(Long gatewayOrderCode);

    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("SELECT p FROM Payment p WHERE p.id = :id")
    Optional<Payment> findByIdForUpdate(@Param("id") Long id);

    // Kiểm tra Subscription đã có Payment với một trạng thái cụ thể.
    boolean existsBySubscription_IdAndStatus(Long subscriptionId,String status);

    // Kiểm tra Subscription có Payment thuộc một trong nhiều trạng thái hay không.
    // Dùng để ngăn hủy đơn khi Payment đang xử lý hoặc đã SUCCESS.
    boolean existsBySubscription_IdAndStatusIn(Long subscriptionId,List<String> statuses);

    // Lịch sử payment của một Member, mới nhất trước (lễ tân xem, mọi trạng thái).
    @EntityGraph(attributePaths = "subscription")
    List<Payment> findByPayer_IdOrderByCreatedAtDesc(Long payerId);

    // Hóa đơn của Member: chỉ các payment đã thanh toán, mới nhất trước.
    @EntityGraph(attributePaths = "subscription")
    List<Payment> findByPayer_IdAndStatusOrderByPaidAtDesc(Long payerId, String status);

    // Payment kèm đơn, người trả và người thu để dựng hóa đơn trong một lần truy vấn.
    @EntityGraph(attributePaths = { "subscription", "payer", "receivedBy" })
    @Query("SELECT p FROM Payment p WHERE p.id = :id")
    Optional<Payment> findWithDetailsById(@Param("id") Long id);

    // Payment QR vẫn CREATING/PENDING dù đã quá hạn QR (khoảng [from, to]).
    // Dùng để đối soát với PayOS rồi đóng payment lại.
    @Query("""
            SELECT p.id FROM Payment p
            WHERE p.paymentMethod = 'QR_GATEWAY'
              AND p.status IN ('CREATING', 'PENDING')
              AND p.expiresAt BETWEEN :from AND :to
            """)
    List<Long> findExpiredOnlineIds(
            @Param("from") OffsetDateTime from,
            @Param("to") OffsetDateTime to);

    // Payment đã nhận tiền nhưng chưa kích hoạt xong Membership.
    // Chỉ lấy giao dịch trong khoảng [from, to] để không thử lại mãi.
    @Query("""
            SELECT p.id FROM Payment p
            WHERE p.status = 'SUCCESS'
              AND p.gatewayResponse = :marker
              AND p.paidAt BETWEEN :from AND :to
            """)
    List<Long> findIdsAwaitingActivation(
            @Param("marker") String marker,
            @Param("from") OffsetDateTime from,
            @Param("to") OffsetDateTime to);
}