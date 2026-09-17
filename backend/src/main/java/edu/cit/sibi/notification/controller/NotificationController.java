package edu.cit.sibi.notification.controller;

import edu.cit.sibi.notification.dto.NotificationDto;
import edu.cit.sibi.notification.model.Notification;
import edu.cit.sibi.notification.repository.NotificationRepository;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

@RestController
@RequestMapping("/api/notifications")
public class NotificationController {

    private final NotificationRepository notificationRepository;

    public NotificationController(NotificationRepository notificationRepository) {
        this.notificationRepository = notificationRepository;
    }

    @GetMapping
    public List<NotificationDto> getAll() {
        return notificationRepository.findAllByOrderByCreatedAtDesc().stream()
                .map(this::toDto)
                .toList();
    }

    private NotificationDto toDto(Notification notification) {
        return new NotificationDto(notification.getNotificationId(), notification.getMessage(), notification.getCreatedAt());
    }
}
