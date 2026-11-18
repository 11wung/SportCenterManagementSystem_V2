package com.swp391.sportcentermanagementsystem_v2.controller;

import com.swp391.sportcentermanagementsystem_v2.dto.requestDTO.SportCategoryRequest;
import com.swp391.sportcentermanagementsystem_v2.dto.responseDTO.RemovalResponse;
import com.swp391.sportcentermanagementsystem_v2.dto.responseDTO.SportCategoryResponse;
import com.swp391.sportcentermanagementsystem_v2.service.ImageService;
import com.swp391.sportcentermanagementsystem_v2.service.SportCategoryService;
import com.swp391.sportcentermanagementsystem_v2.service.VideoService;
import jakarta.validation.Valid;
import org.springframework.core.io.ByteArrayResource;
import org.springframework.core.io.Resource;
import org.springframework.http.CacheControl;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.multipart.MultipartFile;

import java.util.List;

@RestController
@RequestMapping("/api/sport-categories")
public class SportCategoryController {

    private final SportCategoryService sportCategoryService;
    private final ImageService imageService;
    private final VideoService videoService;

    public SportCategoryController(SportCategoryService sportCategoryService, ImageService imageService,
                                   VideoService videoService) {
        this.sportCategoryService = sportCategoryService;
        this.imageService = imageService;
        this.videoService = videoService;
    }

    // includeArchived=true: manager xem cả môn đã ngừng sử dụng; mặc định chỉ môn đang dùng
    @GetMapping
    public List<SportCategoryResponse> getAll(@RequestParam(defaultValue = "false") boolean includeArchived) {
        return sportCategoryService.getAll(includeArchived);
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

    // Chưa từng dùng thì xóa hẳn, đã dùng thì ngừng sử dụng (result trong body cho biết đã làm gì)
    @DeleteMapping("/{id}")
    public RemovalResponse delete(@PathVariable Long id) {
        return sportCategoryService.delete(id);
    }

    @GetMapping("/{id}/usage")
    public RemovalResponse usage(@PathVariable Long id) {
        return sportCategoryService.usage(id);
    }

    @PostMapping("/{id}/restore")
    public SportCategoryResponse restore(@PathVariable Long id) {
        return sportCategoryService.restore(id);
    }

    // Ảnh minh họa của môn: ai cũng xem được (trang chủ công khai); manager thêm, đổi hoặc xóa
    @GetMapping("/{id}/image")
    public ResponseEntity<byte[]> image(@PathVariable Long id) {
        return ImageResponses.of(imageService.read(ImageService.SPORT, id));
    }

    @PutMapping(value = "/{id}/image", consumes = MediaType.MULTIPART_FORM_DATA_VALUE)
    public SportCategoryResponse uploadImage(@PathVariable Long id, @RequestPart("file") MultipartFile file) {
        imageService.save(ImageService.SPORT, id, file);
        return sportCategoryService.getById(id);
    }

    @DeleteMapping("/{id}/image")
    public SportCategoryResponse deleteImage(@PathVariable Long id) {
        imageService.clear(ImageService.SPORT, id);
        return sportCategoryService.getById(id);
    }

    // Video minh họa của môn (tùy chọn): ai cũng xem được; manager tải lên hoặc thay. Trả Resource để trình duyệt tải từng đoạn (Range)
    @GetMapping("/{id}/video")
    public ResponseEntity<Resource> video(@PathVariable Long id) {
        VideoService.Video video = videoService.read(id);
        if (video == null) {
            return ResponseEntity.status(HttpStatus.NOT_FOUND).build();
        }
        return ResponseEntity.ok()
                .contentType(MediaType.parseMediaType(video.type()))
                .cacheControl(CacheControl.maxAge(java.time.Duration.ofDays(7)).cachePublic())
                .body(new ByteArrayResource(video.data()));
    }

    @PutMapping(value = "/{id}/video", consumes = MediaType.MULTIPART_FORM_DATA_VALUE)
    public SportCategoryResponse uploadVideo(@PathVariable Long id, @RequestPart("file") MultipartFile file) {
        videoService.save(id, file);
        return sportCategoryService.getById(id);
    }
}
