package com.swp391.sportcentermanagementsystem_v2.service;

import com.swp391.sportcentermanagementsystem_v2.dto.requestDTO.OneOnOnePackageRequest;
import com.swp391.sportcentermanagementsystem_v2.dto.responseDTO.OneOnOnePackageResponse;
import com.swp391.sportcentermanagementsystem_v2.entity.OneOnOnePackage;
import com.swp391.sportcentermanagementsystem_v2.entity.SportCategory;
import com.swp391.sportcentermanagementsystem_v2.exception.BusinessRuleException;
import com.swp391.sportcentermanagementsystem_v2.repository.OneOnOnePackageRepository;
import com.swp391.sportcentermanagementsystem_v2.repository.SportCategoryRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;

import java.util.List;

// Gói 1-1 (manager tạo, member chọn khi gửi yêu cầu). Đổi giá chỉ áp cho yêu cầu duyệt sau; yêu cầu đã duyệt giữ giá đã chụp ở các buổi
@Service
@RequiredArgsConstructor
public class OneOnOnePackageService {

    private final OneOnOnePackageRepository packageRepository;
    private final SportCategoryRepository sportCategoryRepository;

    @Transactional(readOnly = true)
    public List<OneOnOnePackageResponse> listAll() {
        return packageRepository.findAllByOrderByCategory_NameAscSessionsAsc().stream().map(OneOnOnePackageResponse::new).toList();
    }

    // Gói đang bán, lọc theo môn nếu có
    @Transactional(readOnly = true)
    public List<OneOnOnePackageResponse> listActive(Long sportCategoryId) {
        return packageRepository.findByIsActiveTrueOrderByCategory_NameAscSessionsAsc().stream()
                .filter(p -> p.getCategory().getArchivedAt() == null)
                .filter(p -> sportCategoryId == null || p.getCategory().getId().equals(sportCategoryId))
                .map(OneOnOnePackageResponse::new).toList();
    }

    @Transactional
    public OneOnOnePackageResponse create(OneOnOnePackageRequest in) {
        OneOnOnePackage p = new OneOnOnePackage();
        apply(p, in);
        return new OneOnOnePackageResponse(packageRepository.save(p));
    }

    @Transactional
    public OneOnOnePackageResponse update(Long id, OneOnOnePackageRequest in) {
        OneOnOnePackage p = get(id);
        apply(p, in);
        return new OneOnOnePackageResponse(packageRepository.save(p));
    }

    // Ẩn gói thay vì xóa: yêu cầu cũ vẫn trỏ về gói
    @Transactional
    public OneOnOnePackageResponse setActive(Long id, boolean active) {
        OneOnOnePackage p = get(id);
        p.setIsActive(active);
        return new OneOnOnePackageResponse(packageRepository.save(p));
    }

    // Gói đang bán để member mua
    @Transactional(readOnly = true)
    public OneOnOnePackage requireActive(Long id) {
        OneOnOnePackage p = get(id);
        if (!Boolean.TRUE.equals(p.getIsActive()) || p.getCategory().getArchivedAt() != null) {
            throw new BusinessRuleException("Khóa 1-1 này đã ngừng bán, hãy chọn khóa khác");
        }
        return p;
    }

    private OneOnOnePackage get(Long id) {
        return packageRepository.findById(id)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "Không tìm thấy gói 1-1 có mã " + id));
    }

    private void apply(OneOnOnePackage p, OneOnOnePackageRequest in) {
        SportCategory sport = sportCategoryRepository.findById(in.getSportCategoryId())
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "Không tìm thấy môn"));
        SportCategoryService.requireInUse(sport);
        String name = in.getName().trim();
        if (packageRepository.existsByCategory_IdAndNameIgnoreCaseAndIdNot(sport.getId(), name, p.getId() == null ? -1L : p.getId())) {
            throw new BusinessRuleException("Môn " + sport.getName() + " đã có gói tên \"" + name + "\"");
        }
        p.setCategory(sport);
        p.setName(name);
        p.setDescription(in.getDescription() == null || in.getDescription().isBlank() ? null : in.getDescription().trim());
        p.setSessions(in.getSessions().shortValue());
        p.setPrice(in.getPrice());
    }
}
