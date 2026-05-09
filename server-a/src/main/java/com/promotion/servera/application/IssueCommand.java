package com.promotion.servera.application;

public record IssueCommand(long userId, long eventId, long couponTypeId) {
}
