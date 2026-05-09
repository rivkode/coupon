package com.promotion.serverc.domain.exception;

/** 요청한 eventId 가 존재하지 않을 때. */
public class EventNotFoundException extends RuntimeException {

    public EventNotFoundException(long eventId) {
        super("event not found: eventId=" + eventId);
    }
}
