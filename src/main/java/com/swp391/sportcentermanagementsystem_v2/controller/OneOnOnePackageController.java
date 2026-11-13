package com.swp391.sportcentermanagementsystem_v2.controller;

import com.swp391.sportcentermanagementsystem_v2.dto.requestDTO.OneOnOnePackageRequest;
import com.swp391.sportcentermanagementsystem_v2.dto.responseDTO.OneOnOnePackageResponse;
import com.swp391.sportcentermanagementsystem_v2.service.OneOnOnePackageService;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.*;

import java.util.List;

// Gói 1-1: ai cũng xem được gói đang bán; manager tạo, sửa, ẩn/hiện gói (quyền ở SecurityConfig)
@RestController
@RequiredArgsConstructor
public class OneOnOnePackageController {

    private final OneOnOnePackageService packageService;

    @GetMapping("/api/one-on-one-packages")
    public List<OneOnOnePackageResponse> active(@RequestParam(required = false) Long sportCategoryId) {
        return packageService.listActive(sportCategoryId);
    }

    @GetMapping("/api/manager/one-on-one-packages")
    public List<OneOnOnePackageResponse> all() {
        return packageService.listAll();
    }

    @PostMapping("/api/manager/one-on-one-packages")
    @ResponseStatus(HttpStatus.CREATED)
    public OneOnOnePackageResponse create(@Valid @RequestBody OneOnOnePackageRequest request) {
        return packageService.create(request);
    }

    @PutMapping("/api/manager/one-on-one-packages/{id}")
    public OneOnOnePackageResponse update(@PathVariable Long id, @Valid @RequestBody OneOnOnePackageRequest request) {
        return packageService.update(id, request);
    }

    @PatchMapping("/api/manager/one-on-one-packages/{id}/active")
    public OneOnOnePackageResponse setActive(@PathVariable Long id, @RequestParam boolean value) {
        return packageService.setActive(id, value);
    }
}
