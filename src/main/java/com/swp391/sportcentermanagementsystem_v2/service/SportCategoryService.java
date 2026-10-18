package com.swp391.sportcentermanagementsystem_v2.service;

import com.swp391.sportcentermanagementsystem_v2.dto.requestDTO.SportCategoryRequest;
import com.swp391.sportcentermanagementsystem_v2.dto.responseDTO.SportCategoryResponse;
import com.swp391.sportcentermanagementsystem_v2.entity.SportCategory;
import com.swp391.sportcentermanagementsystem_v2.exception.BusinessRuleException;
import com.swp391.sportcentermanagementsystem_v2.repository.SportCategoryRepository;
import jakarta.persistence.EntityNotFoundException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.ArrayList;
import java.util.List;

@Service
public class SportCategoryService {

    private final SportCategoryRepository sportCategoryRepository;

    public SportCategoryService(SportCategoryRepository sportCategoryRepository) {
        this.sportCategoryRepository = sportCategoryRepository;
    }

    public List<SportCategoryResponse> getAll() {
        List<SportCategory> categories = sportCategoryRepository.findAll();
        List<SportCategoryResponse> responses = new ArrayList<>();

        for (SportCategory category : categories) {
            responses.add(new SportCategoryResponse(category));
        }

        return responses;
    }

    public SportCategoryResponse getById(Long id) {
        SportCategory category = sportCategoryRepository.findById(id)
                .orElseThrow(() -> new EntityNotFoundException(
                        "This Sport Category does not exist with ID: " + id));

        return new SportCategoryResponse(category);
    }

    @Transactional
    public SportCategoryResponse create(SportCategoryRequest request) {
        String name = request.getName().trim();

        if (sportCategoryRepository.existsByName(name)) {
            throw new BusinessRuleException("Sport Category name have already exist");
        }

        SportCategory category = new SportCategory();
        category.setName(name);
        category.setDescription(request.getDescription());

        SportCategory savedCategory = sportCategoryRepository.save(category);

        return new SportCategoryResponse(savedCategory);
    }

    @Transactional
    public SportCategoryResponse update(Long id, SportCategoryRequest request) {
        SportCategory category = sportCategoryRepository.findById(id)
                .orElseThrow(() -> new EntityNotFoundException(
                        "This Sport Category does not exist with ID: " + id));

        String name = request.getName().trim();

        if (sportCategoryRepository.existsByNameAndIdNot(name, id)) {
            throw new BusinessRuleException("Sport Category name have already exist");
        }

        category.setName(name);
        category.setDescription(request.getDescription());

        SportCategory updatedCategory = sportCategoryRepository.save(category);

        return new SportCategoryResponse(updatedCategory);
    }

    @Transactional
    public void delete(Long id) {
        SportCategory category = sportCategoryRepository.findById(id)
                .orElseThrow(() -> new EntityNotFoundException(
                        "This Sport Category does not exist with ID: " + id));

        sportCategoryRepository.delete(category);
    }
}
