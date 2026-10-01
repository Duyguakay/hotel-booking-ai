package com.hotel.booking.dto;

import jakarta.validation.constraints.Future;
import jakarta.validation.constraints.NotNull;
import lombok.Getter;
import lombok.Setter;

import java.time.LocalDate;

@Getter
@Setter
public class ReservationRequestDto {

    @NotNull(message = "Kullanıcı ID boş bırakılamaz")
    private Long userId;

    @NotNull(message = "Oda ID boş bırakılamaz")
    private Long roomId;

    @NotNull(message = "Giriş tarihi boş bırakılamaz")
    @Future(message = "Giriş tarihi bugünden sonraki bir tarih olmalıdır")
    private LocalDate checkInDate;

    @NotNull(message = "Çıkış tarihi boş bırakılamaz")
    @Future(message = "Çıkış tarihi bugünden sonraki bir tarih olmalıdır")
    private LocalDate checkOutDate;
}