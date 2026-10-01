package com.hotel.booking.dto;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.time.LocalDateTime;

@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class SmsNotificationDto {
    private Long id;
    private Long userId;
    private String phoneNumber;
    private String title;
    private String message;
    private String badgeType;
    private Boolean isRead;
    private LocalDateTime sentAt;
}
