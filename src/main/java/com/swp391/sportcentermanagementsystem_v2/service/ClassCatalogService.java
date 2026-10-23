package com.swp391.sportcentermanagementsystem_v2.service;

import com.swp391.sportcentermanagementsystem_v2.dto.responseDTO.ClassCatalogDetailResponse;
import com.swp391.sportcentermanagementsystem_v2.dto.responseDTO.ClassCatalogResponse;
import com.swp391.sportcentermanagementsystem_v2.entity.SportClass;
import com.swp391.sportcentermanagementsystem_v2.repository.ClassRegistrationRepository;
import com.swp391.sportcentermanagementsystem_v2.repository.SportClassRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;

import java.util.HashMap;
import java.util.List;
import java.util.Map;

// Danh mục class công khai: khách chưa đăng nhập cũng xem được để cân nhắc trước khi đăng ký
@Service
@RequiredArgsConstructor
public class ClassCatalogService {

    private final SportClassRepository sportClassRepository;
    private final ClassRegistrationRepository classRegistrationRepository;
    private final ClassScheduleService classScheduleService;
    private final ClassSessionService classSessionService;

    @Transactional(readOnly = true)
    public List<ClassCatalogResponse> search(Long categoryId, String level, String keyword, boolean hasSeats) {
        String likeKeyword = (keyword == null || keyword.isBlank())
                ? null
                : "%" + keyword.trim().toLowerCase() + "%";
        String levelFilter = (level == null || level.isBlank()) ? null : level.trim().toUpperCase();

        List<SportClass> classes = sportClassRepository.searchOpen(categoryId, levelFilter, likeKeyword);
        Map<Long, Long> taken = takenByClass(classes);

        return classes.stream()
                .map(c -> new ClassCatalogResponse(c, taken.getOrDefault(c.getId(), 0L)))
                .filter(r -> !hasSeats || r.getSeatsLeft() > 0)
                .toList();
    }

    @Transactional(readOnly = true)
    public ClassCatalogDetailResponse getDetail(Long id) {
        SportClass sportClass = sportClassRepository.findOpenById(id)
                .orElseThrow(() -> new ResponseStatusException(
                        HttpStatus.NOT_FOUND, "Class is not open for registration or does not exist: " + id));

        ClassCatalogResponse info = new ClassCatalogResponse(
                sportClass, takenByClass(List.of(sportClass)).getOrDefault(id, 0L));
        return new ClassCatalogDetailResponse(
                info,
                classScheduleService.getSchedulesByClassId(id),
                classSessionService.getSessionsByClass(id));
    }

    private Map<Long, Long> takenByClass(List<SportClass> classes) {
        Map<Long, Long> taken = new HashMap<>();
        if (classes.isEmpty()) {
            return taken;
        }
        List<Long> ids = classes.stream().map(SportClass::getId).toList();
        for (Object[] row : classRegistrationRepository.countTakenByClassIds(ids)) {
            taken.put((Long) row[0], (Long) row[1]);
        }
        return taken;
    }
}
