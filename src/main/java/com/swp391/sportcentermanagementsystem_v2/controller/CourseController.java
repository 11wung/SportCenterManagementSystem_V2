package com.swp391.sportcentermanagementsystem_v2.controller;

import com.swp391.sportcentermanagementsystem_v2.dto.requestDTO.CourseActiveRequest;
import com.swp391.sportcentermanagementsystem_v2.dto.requestDTO.CourseRequest;
import com.swp391.sportcentermanagementsystem_v2.dto.responseDTO.CourseResponse;
import com.swp391.sportcentermanagementsystem_v2.service.CourseService;
import jakarta.validation.Valid;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.*;

import java.util.List;

@RestController
@RequestMapping("/api/courses")
public class CourseController {

    private final CourseService courseService;

    public CourseController(CourseService courseService) {
        this.courseService = courseService;
    }

    // Mặc định chỉ trả khóa đang bán; manager truyền active=false để xem khóa đã ngừng bán
    @GetMapping
    public List<CourseResponse> search(@RequestParam(required = false) Long sportCategoryId,
                                       @RequestParam(required = false) String level,
                                       @RequestParam(defaultValue = "true") boolean active) {
        return courseService.search(sportCategoryId, level, active);
    }

    @GetMapping("/{id}")
    public CourseResponse getById(@PathVariable Long id) {
        return courseService.getById(id);
    }

    @PostMapping
    @ResponseStatus(HttpStatus.CREATED)
    public CourseResponse create(@Valid @RequestBody CourseRequest request) {
        return courseService.create(request);
    }

    @PutMapping("/{id}")
    public CourseResponse update(@PathVariable Long id, @Valid @RequestBody CourseRequest request) {
        return courseService.update(id, request);
    }

    @PatchMapping("/{id}/active")
    public CourseResponse setActive(@PathVariable Long id, @Valid @RequestBody CourseActiveRequest request) {
        return courseService.setActive(id, request.getActive());
    }
}
