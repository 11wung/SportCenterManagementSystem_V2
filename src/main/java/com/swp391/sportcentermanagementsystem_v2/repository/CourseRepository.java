package com.swp391.sportcentermanagementsystem_v2.repository;

import com.swp391.sportcentermanagementsystem_v2.entity.Course;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.List;
import java.util.Optional;

public interface CourseRepository extends JpaRepository<Course, Long> {

    boolean existsByCategoryIdAndName(Long categoryId, String name);

    boolean existsByCategoryIdAndNameAndIdNot(Long categoryId, String name, Long id);

    @Query("""
            SELECT c FROM Course c
            JOIN FETCH c.category
            WHERE c.active = :active
              AND (:categoryId IS NULL OR c.category.id = :categoryId)
              AND (:level IS NULL OR c.level = :level)
            ORDER BY c.name
            """)
    List<Course> search(@Param("active") boolean active,
                        @Param("categoryId") Long categoryId,
                        @Param("level") String level);

    @Query("SELECT c FROM Course c JOIN FETCH c.category WHERE c.id = :id")
    Optional<Course> findDetailById(@Param("id") Long id);
}
