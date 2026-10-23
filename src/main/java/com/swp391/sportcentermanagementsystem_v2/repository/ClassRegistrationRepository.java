package com.swp391.sportcentermanagementsystem_v2.repository;

import com.swp391.sportcentermanagementsystem_v2.entity.ClassRegistration;
import org.springframework.data.jpa.repository.JpaRepository;
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

    Optional<ClassRegistration> findBySportClass_IdAndUser_IdAndStatusIn(
            Long classId, Long userId, Collection<String> statuses);

    List<ClassRegistration> findBySportClass_IdAndUser_IdOrderByIdDesc(
            Long classId, Long userId);

    long countBySportClass_IdAndStatus(Long classId, String status);

    long countByUser_IdAndStatusAndRegisteredAtBetween(
            Long userId, String status, OffsetDateTime start, OffsetDateTime end);

   Long countBySportClass_IdAndStatusIn(Long classId, Collection<String> statuses);

    List<ClassRegistration> findByUser_IdOrderByRegisteredAtDesc(Long userId);

    // Mỗi dòng: [classId, số chỗ đã giữ hoặc đã ghi danh]. Một query cho cả danh sách, tránh đếm từng class
    @Query("""
            select r.sportClass.id, count(r) from ClassRegistration r
            where r.sportClass.id in :classIds and r.status in ('PENDING_PAYMENT', 'REGISTERED')
            group by r.sportClass.id
            """)
    List<Object[]> countTakenByClassIds(@Param("classIds") Collection<Long> classIds);

    Optional<ClassRegistration> findFirstBySportClass_IdAndStatusOrderByRegisteredAtAsc(Long sportClassId, String status);
}
