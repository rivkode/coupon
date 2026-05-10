package com.promotion.serverc.application;

import com.promotion.serverc.api.dto.EventResponse;
import com.promotion.serverc.domain.EventStatus;
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
import java.util.List;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * Refresh-Ahead 동작 — IN_PROGRESS 상태의 모든 이벤트가 갱신 대상이 되는지.
 * (실 시간축 / Redis 동작은 통합 테스트 영역. 본 테스트는 mock 기반 invariant.)
 */
@ExtendWith(MockitoExtension.class)
class EventCacheRefresherTest {

    @Mock
    private EventJpaRepository eventRepository;

    @Mock
    private EventCacheStore cacheStore;

    @InjectMocks
    private EventCacheRefresher refresher;

    @Test
    void refreshesEachInProgressEvent() {
        EventJpaEntity a = new EventJpaEntity("a", "x",
                LocalDateTime.now().minusHours(1), LocalDateTime.now().plusHours(1),
                EventStatus.IN_PROGRESS);
        EventJpaEntity b = new EventJpaEntity("b", "y",
                LocalDateTime.now().minusHours(2), LocalDateTime.now().plusHours(2),
                EventStatus.IN_PROGRESS);
        EventJpaEntity c = new EventJpaEntity("c", "z",
                LocalDateTime.now().minusMinutes(30), LocalDateTime.now().plusMinutes(30),
                EventStatus.IN_PROGRESS);
        ReflectionTestUtils.setField(a, "eventId", 1L);
        ReflectionTestUtils.setField(b, "eventId", 2L);
        ReflectionTestUtils.setField(c, "eventId", 3L);
        when(eventRepository.findByStatus(EventStatus.IN_PROGRESS)).thenReturn(List.of(a, b, c));

        refresher.refreshActiveEvents();

        verify(cacheStore, times(3)).put(any(EventResponse.class));
    }

    @Test
    void doesNothingWhenNoActiveEvents() {
        when(eventRepository.findByStatus(EventStatus.IN_PROGRESS)).thenReturn(List.of());

        refresher.refreshActiveEvents();

        verify(cacheStore, never()).put(any());
    }

    @Test
    void onlyInProgressEventsAreFetched() {
        when(eventRepository.findByStatus(EventStatus.IN_PROGRESS)).thenReturn(List.of());

        refresher.refreshActiveEvents();

        // CREATED / ENDED / CANCELLED 는 갱신 대상 아님 — repository 가 IN_PROGRESS 만 조회
        verify(eventRepository).findByStatus(EventStatus.IN_PROGRESS);
        verify(eventRepository, never()).findByStatus(EventStatus.CREATED);
        verify(eventRepository, never()).findByStatus(EventStatus.ENDED);
    }
}
