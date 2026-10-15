package com.swp391.sportcentermanagementsystem_v2.repository;

import com.swp391.sportcentermanagementsystem_v2.entity.MemberSubscription;
import org.springframework.data.jpa.repository.EntityGraph;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import java.time.LocalDate;
import java.util.List;
import java.util.Optional;

public interface MemberSubscriptionRepository extends JpaRepository<MemberSubscription, Long> {
    // hàm này để list ra toàn bộ lịch sử gói tập đã đăng ksi của member
    @EntityGraph(attributePaths = { "user", "membershipPackage", "createdBy" })
    List<MemberSubscription> findByUserIdOrderByCreatedAtDesc(Long userId);

    // hàm này để lấy ra gói tập đầu tiên và ngày đăng kí gần nhất của member
    @EntityGraph(attributePaths = { "user", "membershipPackage", "createdBy" })
    Optional<MemberSubscription> findFirstByUserIdAndStatusOrderByEndDateDesc(Long userId, String status);

    // hàm này validate gói tập
    @EntityGraph(attributePaths = { "user", "membershipPackage", "createdBy" })
    @Query("""
                SELECT s FROM MemberSubscription s
                WHERE s.user.id = :userId
                  AND s.status = 'ACTIVE'
                  AND :currentDate BETWEEN s.startDate AND s.endDate
            """)
    Optional<MemberSubscription> findValidActiveSubscription(
            @Param("userId") Long userId,
            @Param("currentDate") LocalDate currentDate);

}
