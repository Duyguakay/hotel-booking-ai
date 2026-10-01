package com.hotel.booking.dto;

import com.hotel.booking.entity.AutoReservationStatus;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.LocalDateTime;

@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class AutoReservationOrderResponseDto {
    private Long id;
    private Long userId;
    private String userName;
    private Long roomId;
    private String roomNumber;
    private String hotelName;
    private String roomType;
    private BigDecimal currentPrice;
    private BigDecimal targetPrice;
    private LocalDate checkInDate;
    private LocalDate checkOutDate;
    private String phoneNumber;
    private Boolean autoBook;
    private AutoReservationStatus status;
    private Long reservationId;
    private LocalDateTime createdAt;
    private LocalDateTime triggeredAt;
}
