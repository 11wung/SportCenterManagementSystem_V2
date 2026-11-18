package com.swp391.sportcentermanagementsystem_v2.service;

import com.swp391.sportcentermanagementsystem_v2.exception.BusinessRuleException;
import jakarta.persistence.EntityNotFoundException;
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

class VideoServiceTest {

    private final JdbcTemplate jdbc = mock(JdbcTemplate.class);
    private final VideoService service = new VideoService(jdbc);

    private static byte[] mp4(int size) {
        byte[] b = new byte[size];
        b[4] = 'f';
        b[5] = 't';
        b[6] = 'y';
        b[7] = 'p';
        return b;
    }

    private static MockMultipartFile file(byte[] data) {
        return new MockMultipartFile("file", "v.mp4", "video/mp4", data);
    }

    @Test
    void savesAValidMp4AndStoresItsRealType() {
        when(jdbc.update(anyString(), any(), any(), any())).thenReturn(1);

        service.save(2L, file(mp4(100)));

        verify(jdbc).update(anyString(), any(byte[].class), eq("video/mp4"), eq(2L));
    }

    @Test
    void recognizesWebm() {
        assertEquals("video/webm", VideoService.detectType(new byte[] {0x1A, 0x45, (byte) 0xDF, (byte) 0xA3, 0, 0}));
    }

    @Test
    void rejectsAVideoOverTheLimit() {
        BusinessRuleException e = assertThrows(BusinessRuleException.class,
                () -> service.save(2L, file(mp4(VideoService.MAX_BYTES + 1))));

        assertTrue(e.getMessage().contains("quá nặng"));
        verify(jdbc, never()).update(anyString(), any(), any(), any());
    }

    @Test
    void rejectsAFileThatIsNotAVideoEvenWithAVideoName() {
        BusinessRuleException e = assertThrows(BusinessRuleException.class,
                () -> service.save(2L, file("khong phai video, chi la chu".getBytes())));

        assertTrue(e.getMessage().contains("MP4 hoặc WEBM"));
        verify(jdbc, never()).update(anyString(), any(), any(), any());
    }

    @Test
    void rejectsAMissingSport() {
        when(jdbc.update(anyString(), any(), any(), any())).thenReturn(0);

        assertThrows(EntityNotFoundException.class, () -> service.save(99L, file(mp4(50))));
    }

    @Test
    void theVideoUrlChangesWhenTheVideoChanges() {
        assertNull(VideoService.url(2L, null, null));
        assertTrue(VideoService.url(2L, "video/mp4", OffsetDateTime.parse("2026-11-14T10:00:00+07:00"))
                .startsWith("/api/sport-categories/2/video?v="));
    }
}
