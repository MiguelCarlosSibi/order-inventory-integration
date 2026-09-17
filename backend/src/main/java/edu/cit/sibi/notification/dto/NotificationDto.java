package edu.cit.sibi.notification.dto;

import java.time.OffsetDateTime;

public record NotificationDto(Long notificationId, String message, OffsetDateTime createdAt) {
}
