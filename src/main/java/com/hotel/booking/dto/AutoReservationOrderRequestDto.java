package com.hotel.booking.dto;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.math.BigDecimal;
import java.time.LocalDate;

@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class AutoReservationOrderRequestDto {
    private Long userId;
    private Long roomId;
    private BigDecimal targetPrice;
    private LocalDate checkInDate;
    private LocalDate checkOutDate;
    private String phoneNumber;
    private Boolean autoBook;
}
