package com.swp391.sportcentermanagementsystem_v2.controller;

import com.swp391.sportcentermanagementsystem_v2.dto.responseDTO.ClassCatalogDetailResponse;
import com.swp391.sportcentermanagementsystem_v2.dto.responseDTO.ClassCatalogResponse;
import com.swp391.sportcentermanagementsystem_v2.service.ClassCatalogService;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.*;

import java.util.List;

// Công khai (không cần đăng nhập): xem các class đang mở đăng ký
@RestController
@RequestMapping("/api/classes")
@RequiredArgsConstructor
public class ClassCatalogController {

    private final ClassCatalogService classCatalogService;

    @GetMapping
    public List<ClassCatalogResponse> search(@RequestParam(required = false) Long sportCategoryId,
                                             @RequestParam(required = false) String level,
                                             @RequestParam(required = false) String keyword,
                                             @RequestParam(defaultValue = "false") boolean hasSeats) {
        return classCatalogService.search(sportCategoryId, level, keyword, hasSeats);
    }

    @GetMapping("/{id}")
    public ClassCatalogDetailResponse getDetail(@PathVariable Long id) {
        return classCatalogService.getDetail(id);
    }
}
