package com.swp391.sportcentermanagementsystem_v2.service;

import com.swp391.sportcentermanagementsystem_v2.entity.Course;
import com.swp391.sportcentermanagementsystem_v2.entity.SportCategory;
import com.swp391.sportcentermanagementsystem_v2.entity.SportClass;
import com.swp391.sportcentermanagementsystem_v2.exception.BusinessRuleException;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.mock.web.MockMultipartFile;

import java.time.OffsetDateTime;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class ImageServiceTest {

    private final JdbcTemplate jdbc = mock(JdbcTemplate.class);
    private final ImageService service = new ImageService(jdbc);

    private static byte[] png(int size) {
        byte[] b = new byte[size];
        b[0] = (byte) 0x89;
        b[1] = 'P';
        b[2] = 'N';
        b[3] = 'G';
        return b;
    }

    private static MockMultipartFile file(byte[] data) {
        return new MockMultipartFile("file", "a.png", "image/png", data);
    }

    @Test
    void savesAValidPngAndStoresItsRealType() {
        when(jdbc.update(anyString(), any(), any(), any())).thenReturn(1);

        service.save(ImageService.SPORT, 3L, file(png(100)));

        verify(jdbc).update(anyString(), any(byte[].class), eq("image/png"), eq(3L));
    }

    @Test
    void rejectsAFileOverTheLimit() {
        BusinessRuleException e = assertThrows(BusinessRuleException.class,
                () -> service.save(ImageService.SPORT, 3L, file(png(ImageService.MAX_BYTES + 1))));

        assertTrue(e.getMessage().contains("quá lớn"));
        verify(jdbc, never()).update(anyString(), any(), any(), any());
    }

    @Test
    void rejectsAFileThatIsNotAnImageEvenWithAnImageName() {
        byte[] text = "hello, this is not an image".getBytes();

        BusinessRuleException e = assertThrows(BusinessRuleException.class,
                () -> service.save(ImageService.CLASS, 5L, file(text)));

        assertTrue(e.getMessage().contains("JPG, PNG hoặc WEBP"));
        verify(jdbc, never()).update(anyString(), any(), any(), any());
    }

    @Test
    void rejectsAMissingTarget() {
        when(jdbc.update(anyString(), any(), any(), any())).thenReturn(0);

        assertThrows(jakarta.persistence.EntityNotFoundException.class, () -> service.save(ImageService.SPORT, 99L, file(png(50))));
    }

    @Test
    void recognizesJpegAndWebp() {
        assertEquals("image/jpeg", ImageService.detectType(new byte[] {(byte) 0xFF, (byte) 0xD8, (byte) 0xFF, 0}));
        byte[] webp = new byte[12];
        webp[0] = 'R'; webp[1] = 'I'; webp[2] = 'F'; webp[3] = 'F';
        webp[8] = 'W'; webp[9] = 'E'; webp[10] = 'B'; webp[11] = 'P';
        assertEquals("image/webp", ImageService.detectType(webp));
    }

    @Test
    void aClassUsesItsOwnImageThenFallsBackToTheSportImage() {
        SportCategory sport = new SportCategory();
        sport.setId(2L);
        Course course = new Course();
        course.setCategory(sport);
        SportClass sportClass = new SportClass();
        sportClass.setId(7L);
        sportClass.setCourse(course);

        assertNull(ClassImages.effectiveUrl(sportClass));

        sport.setImageType("image/png");
        sport.setImageUpdatedAt(OffsetDateTime.parse("2026-11-14T10:00:00+07:00"));
        assertTrue(ClassImages.effectiveUrl(sportClass).startsWith("/api/sport-categories/2/image?v="));

        sportClass.setImageType("image/jpeg");
        sportClass.setImageUpdatedAt(OffsetDateTime.parse("2026-11-14T11:00:00+07:00"));
        assertTrue(ClassImages.effectiveUrl(sportClass).startsWith("/api/sport-classes/7/image?v="));
    }
}
