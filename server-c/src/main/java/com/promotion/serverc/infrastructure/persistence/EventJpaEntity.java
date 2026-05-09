package com.promotion.serverc.infrastructure.persistence;

import com.promotion.serverc.domain.EventStatus;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;

import java.time.LocalDateTime;

@Entity
@Table(name = "event")
public class EventJpaEntity {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    @Column(name = "event_id")
    private Long eventId;

    @Column(name = "name", nullable = false)
    private String name;

    @Column(name = "content")
    private String content;

    @Column(name = "started_at", nullable = false)
    private LocalDateTime startedAt;

    @Column(name = "ended_at", nullable = false)
    private LocalDateTime endedAt;

    @Enumerated(EnumType.STRING)
    @Column(name = "status", nullable = false, length = 20)
    private EventStatus status;

    @Column(name = "created_at", nullable = false, insertable = false, updatable = false)
    private LocalDateTime createdAt;

    @Column(name = "updated_at", nullable = false, insertable = false, updatable = false)
    private LocalDateTime updatedAt;

    protected EventJpaEntity() {}

    public EventJpaEntity(String name, String content, LocalDateTime startedAt, LocalDateTime endedAt) {
        this(name, content, startedAt, endedAt, EventStatus.CREATED);
    }

    public EventJpaEntity(String name, String content, LocalDateTime startedAt, LocalDateTime endedAt,
                          EventStatus status) {
        this.name = name;
        this.content = content;
        this.startedAt = startedAt;
        this.endedAt = endedAt;
        this.status = status;
    }

    public boolean isActive(LocalDateTime now) {
        return !now.isBefore(startedAt) && !now.isAfter(endedAt);
    }

    public Long getEventId() { return eventId; }
    public String getName() { return name; }
    public String getContent() { return content; }
    public LocalDateTime getStartedAt() { return startedAt; }
    public LocalDateTime getEndedAt() { return endedAt; }
    public EventStatus getStatus() { return status; }
}
