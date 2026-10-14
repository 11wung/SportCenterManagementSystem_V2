package com.swp391.sportcentermanagementsystem_v2.repository;

import com.swp391.sportcentermanagementsystem_v2.entity.SportCategory;
import org.springframework.data.jpa.repository.JpaRepository;

@Repository
public interface SportCategoryRepository extends JpaRepository<SportCategory, Long> {
}
