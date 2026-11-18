package com.swp391.sportcentermanagementsystem_v2.service;

import com.swp391.sportcentermanagementsystem_v2.dto.requestDTO.SportCategoryRequest;
import com.swp391.sportcentermanagementsystem_v2.dto.responseDTO.SportCategoryResponse;
import com.swp391.sportcentermanagementsystem_v2.entity.SportCategory;
import com.swp391.sportcentermanagementsystem_v2.exception.BusinessRuleException;
import com.swp391.sportcentermanagementsystem_v2.repository.SportCategoryRepository;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;

import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class SportCategoryServiceTest {

    private final SportCategoryRepository repository = mock(SportCategoryRepository.class);
    private final SportCategoryService service = new SportCategoryService(repository, mock(JdbcTemplate.class));

    private static SportCategoryRequest request(String name, String description) {
        SportCategoryRequest r = new SportCategoryRequest();
        r.setName(name);
        r.setDescription(description);
        return r;
    }

    @Test
    void aNewSportCanBeAddedWithoutAnImage() {
        when(repository.existsByName("Pilates")).thenReturn(false);
        when(repository.save(any())).thenAnswer(i -> i.getArgument(0));

        SportCategoryResponse created = service.create(request(" Pilates ", "x"));

        assertEquals("Pilates", created.getName());
        assertNull(created.getImageUrl());
    }

    @Test
    void aDuplicateSportNameIsRejected() {
        when(repository.existsByName("Yoga")).thenReturn(true);

        assertThrows(BusinessRuleException.class, () -> service.create(request("Yoga", "x")));
        verify(repository, never()).save(any());
    }

    @Test
    void theNameOfASportCannotChange() {
        SportCategory yoga = new SportCategory();
        yoga.setId(2L);
        yoga.setName("Yoga");
        when(repository.findById(2L)).thenReturn(Optional.of(yoga));

        assertThrows(BusinessRuleException.class, () -> service.update(2L, request("Yoga Moi", "x")));
        verify(repository, never()).save(any());
    }

    @Test
    void theDescriptionOfASportCanChange() {
        SportCategory yoga = new SportCategory();
        yoga.setId(2L);
        yoga.setName("Yoga");
        when(repository.findById(2L)).thenReturn(Optional.of(yoga));
        when(repository.save(any())).thenAnswer(i -> i.getArgument(0));

        assertEquals("Mô tả mới", service.update(2L, request(" Yoga ", "Mô tả mới")).getDescription());
    }
}
