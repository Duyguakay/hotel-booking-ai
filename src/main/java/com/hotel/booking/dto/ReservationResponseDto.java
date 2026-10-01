package com.hotel.booking.dto;

import com.hotel.booking.entity.ReservationStatus;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.math.BigDecimal;
import java.time.LocalDate;

@Data
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class ReservationResponseDto {

    private Long id;
    private Long userId;
    private String guestName;
    private String guestEmail;
    private String guestPhone;
    
    private Long roomId;
    private String roomNumber;
    private String roomType;
    private String hotelName;
    private String hotelCity;

    private LocalDate checkInDate;
    private LocalDate checkOutDate;
    private BigDecimal totalPrice;
    private ReservationStatus status;
}