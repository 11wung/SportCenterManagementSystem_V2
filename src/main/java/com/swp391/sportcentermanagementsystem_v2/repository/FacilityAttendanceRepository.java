package com.swp391.sportcentermanagementsystem_v2.repository;

import com.swp391.sportcentermanagementsystem_v2.entity.FacilityAttendance;
import jakarta.persistence.LockModeType;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.time.OffsetDateTime;
import java.util.List;
import java.util.Optional;

public interface FacilityAttendanceRepository extends JpaRepository<FacilityAttendance, Long> {

    // Còn lượt vào chưa ra không: chặn check-in lần hai
    boolean existsByUser_IdAndCheckOutTimeIsNull(Long userId);

    // Xác định hội viên đã từng sử dụng trung tâm kể từ ngày bắt đầu gói đầu tiên.
    boolean existsByUser_IdAndCheckInTimeGreaterThanEqual(Long userId, OffsetDateTime from);

    // Khóa dòng để hai người không cùng bấm check-out
    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("select a from FacilityAttendance a where a.id = :id")
    Optional<FacilityAttendance> findByIdForUpdate(@Param("id") Long id);

    // Lượt vào trong khoảng [from, to), lọc theo tên khi name khác rỗng, mới nhất trước
    @Query("""
            select a from FacilityAttendance a
            join fetch a.user u
            left join fetch a.recordedBy
            where a.checkInTime >= :from and a.checkInTime < :to
              and (:name = '' or lower(u.fullName) like lower(concat('%', :name, '%')))
            order by a.checkInTime desc
            """)
    List<FacilityAttendance> search(@Param("from") OffsetDateTime from, @Param("to") OffsetDateTime to,
                                    @Param("name") String name);
}
