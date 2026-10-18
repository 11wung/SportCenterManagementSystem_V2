package com.swp391.sportcentermanagementsystem_v2.controller;

import com.swp391.sportcentermanagementsystem_v2.dto.requestDTO.SportCategoryRequest;
import com.swp391.sportcentermanagementsystem_v2.dto.responseDTO.SportCategoryResponse;
import com.swp391.sportcentermanagementsystem_v2.service.SportCategoryService;
import jakarta.validation.Valid;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.*;

import java.util.List;

@RestController
@RequestMapping("/api/sport-categories")
public class SportCategoryController {

    private final SportCategoryService sportCategoryService;

    public SportCategoryController(SportCategoryService sportCategoryService) {
        this.sportCategoryService = sportCategoryService;
    }

    @GetMapping
    public List<SportCategoryResponse> getAll() {
        return sportCategoryService.getAll();
    }

    @GetMapping("/{id}")
    public SportCategoryResponse getById(@PathVariable Long id) {
        return sportCategoryService.getById(id);
    }

    @PostMapping
    @ResponseStatus(HttpStatus.CREATED)
    public SportCategoryResponse create(@Valid @RequestBody SportCategoryRequest request) {
        return sportCategoryService.create(request);
    }

    @PutMapping("/{id}")
    public SportCategoryResponse update(@PathVariable Long id, @Valid @RequestBody SportCategoryRequest request) {
        return sportCategoryService.update(id, request);
    }

    @DeleteMapping("/{id}")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public void delete(@PathVariable Long id) {
        sportCategoryService.delete(id);
    }
}
