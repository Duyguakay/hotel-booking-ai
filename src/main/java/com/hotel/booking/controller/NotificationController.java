package com.hotel.booking.controller;

import com.hotel.booking.dto.SmsNotificationDto;
import com.hotel.booking.service.SmsNotificationService;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.List;
import java.util.Map;

@RestController
@RequestMapping("/api/notifications")
@CrossOrigin(origins = "*")
@RequiredArgsConstructor
public class NotificationController {

    private final SmsNotificationService smsNotificationService;

    @GetMapping("/user/{userId}")
    public ResponseEntity<List<SmsNotificationDto>> getUserNotifications(@PathVariable Long userId) {
        List<SmsNotificationDto> list = smsNotificationService.getNotificationsForUser(userId);
        return ResponseEntity.ok(list);
    }

    @GetMapping("/user/{userId}/unread-count")
    public ResponseEntity<Map<String, Long>> getUnreadCount(@PathVariable Long userId) {
        long count = smsNotificationService.getUnreadCount(userId);
        return ResponseEntity.ok(Map.of("unreadCount", count));
    }

    @PostMapping("/{id}/read")
    public ResponseEntity<Void> markAsRead(@PathVariable Long id) {
        smsNotificationService.markAsRead(id);
        return ResponseEntity.ok().build();
    }

    @PostMapping("/user/{userId}/read-all")
    public ResponseEntity<Void> markAllAsRead(@PathVariable Long userId) {
        smsNotificationService.markAllAsRead(userId);
        return ResponseEntity.ok().build();
    }

    @DeleteMapping("/user/{userId}")
    public ResponseEntity<Void> deleteAllNotifications(@PathVariable Long userId) {
        smsNotificationService.deleteAllNotifications(userId);
        return ResponseEntity.ok().build();
    }

    @DeleteMapping("/{id}")
    public ResponseEntity<Void> deleteNotification(@PathVariable Long id) {
        smsNotificationService.deleteNotificationById(id);
        return ResponseEntity.ok().build();
    }

    @PostMapping("/test-phone")
    public ResponseEntity<?> sendTestPhoneNotification(@RequestParam(required = false, defaultValue = "Test Bildirimi") String title,
                                                        @RequestParam(required = false, defaultValue = "BookingAI telefon bildirim testi başarıyla ulaştı.") String message) {
        boolean sent = smsNotificationService.sendTelegramInstantNotification(title, message);
        return ResponseEntity.ok(Map.of(
                "success", sent,
                "message", sent ? "Telefonunuza anlık bildirim gönderildi." : "Telegram ayarları application.properties dosyasında tanımlı değil."
        ));
    }
}
