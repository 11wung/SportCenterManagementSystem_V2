
package com.swp391.sportcentermanagementsystem_v2.repository;

import com.swp391.sportcentermanagementsystem_v2.entity.MemberSubscription;

import jakarta.persistence.LockModeType;

import org.springframework.data.jpa.repository.EntityGraph;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.util.List;
import java.util.Optional;

public interface MemberSubscriptionRepository
        extends JpaRepository<MemberSubscription, Long> {

    // Lấy toàn bộ lịch sử Subscription của Member.
    // Sắp xếp theo thời điểm tạo mới nhất.
    @EntityGraph(attributePaths = {
            "user", "membershipPackage", "createdBy"
    })
    List<MemberSubscription> findByUserIdOrderByCreatedAtDesc(Long userId);

    // Kiểm tra Member có Subscription ACTIVE và còn thời hạn.
    // startDate: ngày đầu tiên được sử dụng.
    // endDate: ngày đầu tiên KHÔNG còn được sử dụng.
    @EntityGraph(attributePaths = {
            "user", "membershipPackage", "createdBy"
    })
    @Query("""
            SELECT s FROM MemberSubscription s
            WHERE s.user.id = :userId
              AND s.status = 'ACTIVE'
              AND s.startDate <= :currentDate
              AND :currentDate < s.endDate
            """)
    Optional<MemberSubscription> findValidActiveSubscription(
            @Param("userId") Long userId,
            @Param("currentDate") LocalDate currentDate);

    // Kiểm tra Member có đơn PENDING hay không.
    // Dùng để ngăn Member tạo nhiều đơn chưa thanh toán.
    boolean existsByUserIdAndStatus(
            Long userId,
            String status);

    // Kiểm tra Member từng có Subscription hay chưa.
    boolean existsByUserId(Long userId);

    // Khóa Subscription trước khi ghi nhận thanh toán.
    // Phải gọi trong @Transactional.
    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("SELECT s FROM MemberSubscription s WHERE s.id = :id")
    Optional<MemberSubscription> findByIdForUpdate(
            @Param("id") Long id);

    // Tìm Subscription theo Member và trạng thái,
    // ưu tiên gói có ngày kết thúc muộn nhất.
    // Hiện dùng để tìm gói SCHEDULED khi tạo đơn.
    Optional<MemberSubscription> findFirstByUserIdAndStatusOrderByEndDateDesc(Long userId, String status);

    // Lấy danh sách Subscription của Member theo trạng thái.
    // Dùng để xác định lịch sử gói đã thanh toán,
    // không tính đơn PENDING và CANCELED.
    List<MemberSubscription> findByUserIdAndStatusIn(Long userId, List<String> statuses);

    // Tìm Subscription ACTIVE hoặc SCHEDULED có ngày kết thúc muộn nhất.
    // Dùng khi tính ngày bắt đầu cho đơn NEW/RENEWAL sau thanh toán.
    Optional<MemberSubscription> findFirstByUserIdAndStatusInOrderByEndDateDesc(Long userId, List<String> statuses);

    // Đơn PENDING được tạo trước thời điểm cutoff.
    // Dùng để tự hủy đơn bị bỏ quên.
    @Query("""
            SELECT s.id FROM MemberSubscription s
            WHERE s.status = 'PENDING'
              AND s.createdAt < :cutoff
            """)
    List<Long> findPendingIdsCreatedBefore(
            @Param("cutoff") OffsetDateTime cutoff);

    // Tìm Member có Subscription cần đồng bộ trạng thái.
    // Chỉ lấy những Member có gói đã đến ngày bắt đầu hoặc kết thúc.
    @Query("""
            SELECT DISTINCT s.user.id
            FROM MemberSubscription s
            WHERE
                (s.status = 'ACTIVE'
                    AND s.endDate <= :today)
                OR
                (s.status = 'SCHEDULED'
                    AND s.startDate <= :today)
            """)
    List<Long> findMemberIdsNeedingStatusUpdate(
            @Param("today") LocalDate today);

}
