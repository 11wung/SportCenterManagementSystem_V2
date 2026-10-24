package com.swp391.sportcentermanagementsystem_v2.repository;

import com.swp391.sportcentermanagementsystem_v2.entity.ClassRegistration;
import jakarta.persistence.LockModeType;
import org.springframework.data.jpa.repository.EntityGraph;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

import java.time.OffsetDateTime;
import java.util.Collection;
import java.util.List;
import java.util.Optional;

@Repository
public interface ClassRegistrationRepository extends JpaRepository<ClassRegistration, Long> {

    boolean existsByUserId(Long userId);

    boolean existsBySportClass_IdAndUser_IdAndStatusIn(Long classId, Long userId, Collection<String> statuses);

    Long countBySportClass_IdAndStatusIn(Long classId, Collection<String> statuses);

    Optional<ClassRegistration> findFirstBySportClass_IdAndUser_IdAndStatus(Long classId, Long userId, String status);

    @EntityGraph(attributePaths = { "sportClass", "user" })
    List<ClassRegistration> findByUser_IdOrderByRegisteredAtDesc(Long userId);

    @EntityGraph(attributePaths = { "user" })
    List<ClassRegistration> findBySportClass_IdAndStatusInOrderById(Long classId, Collection<String> statuses);

    // Khóa dòng đăng ký để xác nhận thanh toán, hủy và nhả chỗ không chạy chen nhau
    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("select r from ClassRegistration r where r.id = :id")
    Optional<ClassRegistration> findByIdForUpdate(@Param("id") Long id);

    // Mỗi dòng: [classId, số chỗ đã giữ hoặc đã ghi danh]. Một query cho cả danh sách, tránh đếm từng class
    @Query("""
            select r.sportClass.id, count(r) from ClassRegistration r
            where r.sportClass.id in :classIds and r.status in ('PENDING_PAYMENT', 'REGISTERED')
            group by r.sportClass.id
            """)
    List<Object[]> countTakenByClassIds(@Param("classIds") Collection<Long> classIds);

    // BR-ENR-03: nhả chỗ các đăng ký quá hạn giữ chỗ. Bỏ qua đăng ký có thanh toán chưa ngã ngũ
    // (đang tạo, đang chờ, hoặc đã thu tiền): tiền có thể đang về, việc đó do đối soát thanh toán quyết định
    @Modifying
    @Query(value = """
            UPDATE class_registrations r SET status = 'CANCELED', canceled_at = :now
            WHERE r.status = 'PENDING_PAYMENT' AND r.hold_expires_at < :now
              AND NOT EXISTS (
                  SELECT 1 FROM orders o JOIN payments p ON p.order_id = o.id
                  WHERE o.registration_id = r.id AND p.status IN ('CREATING', 'PENDING', 'SUCCESS'))
            """, nativeQuery = true)
    int expireHolds(@Param("now") OffsetDateTime now);

    // BR-WL-02: danh sách chờ của class theo thứ tự được mời: hạng gói cao trước, cùng hạng thì vào trước
    @EntityGraph(attributePaths = { "user", "sportClass" })
    @Query("""
            select r from ClassRegistration r
            where r.sportClass.id = :classId and r.status = 'WAITLISTED'
            order by r.waitlistRank desc, r.registeredAt, r.id
            """)
    List<ClassRegistration> findWaitlist(@Param("classId") Long classId);

    @Query("select distinct r.sportClass.id from ClassRegistration r where r.status = 'WAITLISTED'")
    List<Long> findClassIdsWithWaitlist();

    // Số người đứng trước một dòng chờ (cùng thứ tự với findWaitlist)
    @Query("""
            select count(o) from ClassRegistration o
            where o.sportClass.id = :classId and o.status = 'WAITLISTED'
              and (o.waitlistRank > :rank
                   or (o.waitlistRank = :rank and (o.registeredAt < :at or (o.registeredAt = :at and o.id < :id))))
            """)
    long countWaitlistAhead(@Param("classId") Long classId, @Param("rank") Short rank,
                            @Param("at") OffsetDateTime at, @Param("id") Long id);

    // BR-WL-02: hạng chờ theo gói đang hiệu lực của member (0 nếu không có gói)
    @Query(value = """
            SELECT CAST(COALESCE(MAX(p.waitlist_priority), 0) AS integer)
            FROM member_subscriptions s JOIN membership_packages p ON p.id = s.package_id
            WHERE s.user_id = :userId AND s.status = 'ACTIVE' AND :today BETWEEN s.start_date AND s.end_date
            """, nativeQuery = true)
    Integer findWaitlistPriority(@Param("userId") Long userId, @Param("today") java.time.LocalDate today);

    // BR-WL-04: đến ngày khai giảng, danh sách chờ đóng
    @Modifying
    @Query("""
            update ClassRegistration r set r.status = 'CANCELED', r.canceledAt = :now
            where r.status = 'WAITLISTED'
              and r.sportClass.id in (select c.id from SportClass c where c.startDate <= :today)
            """)
    int closeWaitlistsOfStartedClasses(@Param("now") OffsetDateTime now, @Param("today") java.time.LocalDate today);

    // BR-SCH-03: tên class member đang giữ chỗ hoặc đã ghi danh mà có buổi trùng giờ với class này
    // (chồng giờ hoặc cách nhau dưới bufferMinutes phút). Rỗng nếu không trùng.
    // shortcut: phép cộng giờ không vòng qua nửa đêm, buổi sát 00:00 có thể lọt, nâng cấp nếu có lớp giờ khuya
    @Query(value = """
            SELECT oc.name
            FROM class_sessions a
            JOIN class_sessions b ON b.session_date = a.session_date
                 AND b.class_id <> a.class_id
                 AND b.status IN ('SCHEDULED', 'DONE')
                 AND a.start_time < b.end_time + make_interval(mins => :bufferMinutes)
                 AND b.start_time < a.end_time + make_interval(mins => :bufferMinutes)
            JOIN class_registrations r ON r.class_id = b.class_id
                 AND r.user_id = :userId
                 AND r.status IN ('PENDING_PAYMENT', 'REGISTERED')
            JOIN sport_classes oc ON oc.id = b.class_id
            WHERE a.class_id = :classId AND a.status = 'SCHEDULED'
            LIMIT 1
            """, nativeQuery = true)
    Optional<String> findConflictingClassName(@Param("userId") Long userId,
                                              @Param("classId") Long classId,
                                              @Param("bufferMinutes") int bufferMinutes);
}
