package com.hotel.booking.service;

import com.hotel.booking.dto.SmsNotificationDto;
import com.hotel.booking.entity.SmsNotification;
import com.hotel.booking.repository.SmsNotificationRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.client.RestTemplate;

import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

@Service
@RequiredArgsConstructor
public class SmsNotificationService {

    private final SmsNotificationRepository smsNotificationRepository;

    @Value("${telegram.bot.token:}")
    private String telegramBotToken;

    @Value("${telegram.chat.id:}")
    private String telegramChatId;

    private final RestTemplate restTemplate = new RestTemplate();

    @Transactional
    public SmsNotificationDto sendSms(Long userId, String phoneNumber, String title, String message, String badgeType) {
        return sendSms(userId, phoneNumber, title, message, badgeType, false);
    }

    @Transactional
    public SmsNotificationDto sendSms(Long userId, String phoneNumber, String title, String message, String badgeType, boolean sendToTelegram) {
        String cleanPhone = (phoneNumber != null && !phoneNumber.isBlank()) ? phoneNumber : "+90 555 123 45 67";
        
        SmsNotification notification = SmsNotification.builder()
                .userId(userId != null ? userId : 1L)
                .phoneNumber(cleanPhone)
                .title(title != null ? title : "BookingAI Bildirimi")
                .message(message)
                .badgeType(badgeType != null ? badgeType : "SMS")
                .isRead(false)
                .sentAt(LocalDateTime.now())
                .build();

        SmsNotification saved = smsNotificationRepository.save(notification);

        // 1. Konsolda görsel ve renkli SMS simülasyon çıktısı
        printSmsToConsole(saved);

        // 2. İstenirse Telegram Anlık Mobil Telefon Bildirimi Gönder
        if (sendToTelegram) {
            sendTelegramInstantNotification(saved.getTitle(), saved.getMessage());
        }

        return mapToDto(saved);
    }

    public boolean sendTelegramInstantNotification(String title, String message) {
        if (telegramBotToken == null || telegramBotToken.isBlank() || telegramChatId == null || telegramChatId.isBlank()) {
            return false;
        }

        try {
            String url = "https://api.telegram.org/bot" + telegramBotToken.trim() + "/sendMessage";
            
            HttpHeaders headers = new HttpHeaders();
            headers.setContentType(MediaType.APPLICATION_JSON);

            Map<String, Object> body = new HashMap<>();
            body.put("chat_id", telegramChatId.trim());
            body.put("text", "<b>BookingAI - " + title + "</b>\n\n" + message);
            body.put("parse_mode", "HTML");

            HttpEntity<Map<String, Object>> entity = new HttpEntity<>(body, headers);
            restTemplate.postForEntity(url, entity, String.class);
            System.out.println("[TELEGRAM ANLIK BILDIRIMI TELEFONA ILETILDI] -> Chat ID: " + telegramChatId);
            return true;
        } catch (Exception e) {
            System.err.println("Telegram bildirimi gönderilirken hata: " + e.getMessage());
            return false;
        }
    }

    @Transactional(readOnly = true)
    public List<SmsNotificationDto> getNotificationsForUser(Long userId) {
        return smsNotificationRepository.findByUserIdOrderBySentAtDesc(userId)
                .stream()
                .map(this::mapToDto)
                .collect(Collectors.toList());
    }

    @Transactional(readOnly = true)
    public long getUnreadCount(Long userId) {
        return smsNotificationRepository.countByUserIdAndIsReadFalse(userId);
    }

    @Transactional
    public void markAsRead(Long id) {
        smsNotificationRepository.findById(id).ifPresent(n -> {
            n.setIsRead(true);
            smsNotificationRepository.save(n);
        });
    }

    @Transactional
    public void markAllAsRead(Long userId) {
        List<SmsNotification> list = smsNotificationRepository.findByUserIdOrderBySentAtDesc(userId);
        list.forEach(n -> n.setIsRead(true));
        smsNotificationRepository.saveAll(list);
    }

    @Transactional
    public void deleteAllNotifications(Long userId) {
        List<SmsNotification> list = smsNotificationRepository.findByUserIdOrderBySentAtDesc(userId);
        smsNotificationRepository.deleteAll(list);
    }

    @Transactional
    public void deleteNotificationById(Long id) {
        smsNotificationRepository.deleteById(id);
    }

    private void printSmsToConsole(SmsNotification sms) {
        String timeStr = sms.getSentAt().format(DateTimeFormatter.ofPattern("dd.MM.yyyy HH:mm:ss"));
        System.out.println("\n" + "=".repeat(65));
        System.out.println("[BILDIRIM SIMULASYONU / GONDERILDI] - " + timeStr);
        System.out.println("   Kime (Telefon) : " + sms.getPhoneNumber());
        System.out.println("   Başlık         : " + sms.getTitle());
        System.out.println("   Mesaj İçeriği  : " + sms.getMessage());
        System.out.println("=".repeat(65) + "\n");
    }

    private SmsNotificationDto mapToDto(SmsNotification n) {
        return SmsNotificationDto.builder()
                .id(n.getId())
                .userId(n.getUserId())
                .phoneNumber(n.getPhoneNumber())
                .title(n.getTitle())
                .message(n.getMessage())
                .badgeType(n.getBadgeType())
                .isRead(n.getIsRead())
                .sentAt(n.getSentAt())
                .build();
    }
}
