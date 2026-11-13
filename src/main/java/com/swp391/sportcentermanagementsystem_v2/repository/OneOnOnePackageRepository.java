package com.swp391.sportcentermanagementsystem_v2.repository;

import com.swp391.sportcentermanagementsystem_v2.entity.OneOnOnePackage;
import org.springframework.data.jpa.repository.EntityGraph;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;

public interface OneOnOnePackageRepository extends JpaRepository<OneOnOnePackage, Long> {

    @EntityGraph(attributePaths = "category")
    List<OneOnOnePackage> findAllByOrderByCategory_NameAscSessionsAsc();

    @EntityGraph(attributePaths = "category")
    List<OneOnOnePackage> findByIsActiveTrueOrderByCategory_NameAscSessionsAsc();

    boolean existsByCategory_IdAndNameIgnoreCaseAndIdNot(Long categoryId, String name, Long id);
}
