package com.swp391.sportcentermanagementsystem_v2.repository;

import com.swp391.sportcentermanagementsystem_v2.entity.Payment;
import org.springframework.data.jpa.repository.EntityGraph;
import org.springframework.data.jpa.repository.JpaRepository;
import jakarta.persistence.LockModeType;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.math.BigDecimal;
import java.time.OffsetDateTime;
import java.util.List;
import java.util.Optional;

public interface PaymentRepository extends JpaRepository<Payment, Long> {

    // Find the latest active link reservation. Caller must hold Member row lock.
    @EntityGraph(attributePaths = "payer")
    Optional<Payment> findFirstByOrder_Subscription_IdAndPaymentMethodAndStatusInOrderByIdDesc(
            Long subscriptionId, String paymentMethod, List<String> statuses);

    // Payment QR mới nhất của một đăng ký class
    Optional<Payment> findFirstByOrder_Registration_IdAndPaymentMethodAndStatusInOrderByIdDesc(
            Long registrationId, String paymentMethod, List<String> statuses);

    // Payment đã thu của một đăng ký class (để hoàn tiền khi hủy)
    Optional<Payment> findFirstByOrder_Registration_IdAndStatusOrderByIdDesc(Long registrationId, String status);

    @Query("SELECT COUNT(p) > 0 FROM Payment p WHERE p.order.registration.id = :registrationId AND p.status = :status")
    boolean existsByRegistration_IdAndStatus(@Param("registrationId") Long registrationId,
                                             @Param("status") String status);

    @Query("SELECT COUNT(p) > 0 FROM Payment p WHERE p.order.registration.id = :registrationId AND p.status IN :statuses")
    boolean existsByRegistration_IdAndStatusIn(@Param("registrationId") Long registrationId,
                                               @Param("statuses") List<String> statuses);

    Optional<Payment> findByGatewayOrderCode(Long gatewayOrderCode);

    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("SELECT p FROM Payment p WHERE p.id = :id")
    Optional<Payment> findByIdForUpdate(@Param("id") Long id);

    // Kiểm tra Subscription đã có Payment với một trạng thái cụ thể.
    @Query("SELECT COUNT(p) > 0 FROM Payment p WHERE p.order.subscription.id = :subscriptionId AND p.status = :status")
    boolean existsBySubscription_IdAndStatus(@Param("subscriptionId") Long subscriptionId,
                                             @Param("status") String status);

    boolean existsByReceivedById(Long staffId);

    // Kiểm tra Subscription có Payment thuộc một trong nhiều trạng thái hay không.
    // Dùng để ngăn hủy đơn khi Payment đang xử lý hoặc đã SUCCESS.
    @Query("SELECT COUNT(p) > 0 FROM Payment p WHERE p.order.subscription.id = :subscriptionId AND p.status IN :statuses")
    boolean existsBySubscription_IdAndStatusIn(@Param("subscriptionId") Long subscriptionId,
                                               @Param("statuses") List<String> statuses);

    // Lịch sử payment của một Member, mới nhất trước (lễ tân xem, mọi trạng thái).
    @EntityGraph(attributePaths = "order.subscription")
    List<Payment> findByPayer_IdOrderByCreatedAtDesc(Long payerId);

    // Hóa đơn của Member: chỉ các payment đã thanh toán, mới nhất trước.
    @EntityGraph(attributePaths = "order.subscription")
    List<Payment> findByPayer_IdAndStatusOrderByPaidAtDesc(Long payerId, String status);

    // Payment kèm đơn, người trả và người thu để dựng hóa đơn trong một lần truy vấn.
    @EntityGraph(attributePaths = { "order.subscription", "payer", "receivedBy" })
    @Query("SELECT p FROM Payment p WHERE p.id = :id")
    Optional<Payment> findWithDetailsById(@Param("id") Long id);

    // Một dòng dữ liệu cho báo cáo doanh thu.
    interface RevenueRow {
        OffsetDateTime getPaidAt();

        BigDecimal getAmount();

        String getPaymentMethod();

        String getPackageName();

        String getGatewayResponse();
    }

    // Các payment đã thanh toán trong khoảng [start, end), kèm tên gói đã chụp lúc mua.
    // Thanh toán ghi danh class không có gói nên gom chung dưới nhãn "Class enrollment"
    // shortcut: nhãn tạm cho nguồn ghi danh, tách doanh thu theo từng nguồn khi làm báo cáo (BR-RPT-04)
    @Query("""
            SELECT p.paidAt AS paidAt,
                   p.amount AS amount,
                   p.paymentMethod AS paymentMethod,
                   COALESCE(s.packageNameSnapshot, 'Class enrollment') AS packageName,
                   p.gatewayResponse AS gatewayResponse
            FROM Payment p JOIN p.order o LEFT JOIN o.subscription s
            WHERE p.status = 'SUCCESS'
              AND p.paidAt >= :start
              AND p.paidAt < :end
            """)
    List<RevenueRow> findPaidBetween(
            @Param("start") OffsetDateTime start,
            @Param("end") OffsetDateTime end);

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