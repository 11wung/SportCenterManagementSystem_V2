package com.swp391.sportcentermanagementsystem_v2.repository;

import com.swp391.sportcentermanagementsystem_v2.entity.SportCategory;
import org.springframework.data.jpa.repository.JpaRepository;

public interface SportCategoryRepository extends JpaRepository<SportCategory, Long> {
    boolean existsByName(String name);

    boolean existsByNameAndIdNot(String name, Long id);
}
