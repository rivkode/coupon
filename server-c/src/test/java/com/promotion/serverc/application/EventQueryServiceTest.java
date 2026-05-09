package com.promotion.serverc.application;

import com.promotion.serverc.api.dto.EventResponse;
import com.promotion.serverc.domain.EventStatus;
import com.promotion.serverc.domain.exception.EventNotFoundException;
import com.promotion.serverc.infrastructure.persistence.EventJpaEntity;
import com.promotion.serverc.infrastructure.persistence.EventJpaRepository;
import com.promotion.serverc.infrastructure.redis.EventCacheStore;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.test.util.ReflectionTestUtils;

import java.time.LocalDateTime;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * Cache-aside 흐름 — cache hit / miss / not found 분기 검증.
 */
@ExtendWith(MockitoExtension.class)
class EventQueryServiceTest {

    @Mock
    private EventCacheStore cacheStore;

    @Mock
    private EventJpaRepository eventRepository;

    @InjectMocks
    private EventQueryService service;

    @Test
    void cacheHitDoesNotTouchDatabase() {
        EventResponse cached = new EventResponse(
                1L, "presale", "concert",
                LocalDateTime.now().minusHours(1), LocalDateTime.now().plusHours(1),
                EventStatus.IN_PROGRESS);
        when(cacheStore.get(1L)).thenReturn(Optional.of(cached));

        EventResponse result = service.findById(1L);

        assertEquals(cached, result);
        verify(eventRepository, never()).findById(any());
        verify(cacheStore, never()).put(any());
    }

    @Test
    void cacheMissLoadsFromDatabaseAndPopulatesCache() {
        when(cacheStore.get(2L)).thenReturn(Optional.empty());
        EventJpaEntity entity = new EventJpaEntity(
                "presale", "concert",
                LocalDateTime.now().minusHours(1), LocalDateTime.now().plusHours(1),
                EventStatus.IN_PROGRESS);
        ReflectionTestUtils.setField(entity, "eventId", 2L);
        when(eventRepository.findById(2L)).thenReturn(Optional.of(entity));

        EventResponse result = service.findById(2L);

        assertEquals(EventStatus.IN_PROGRESS, result.status());
        verify(cacheStore).put(result);
    }

    @Test
    void notFoundWhenAbsentInCacheAndDatabase() {
        when(cacheStore.get(3L)).thenReturn(Optional.empty());
        when(eventRepository.findById(3L)).thenReturn(Optional.empty());

        assertThrows(EventNotFoundException.class, () -> service.findById(3L));
        verify(cacheStore, never()).put(any());
    }
}
