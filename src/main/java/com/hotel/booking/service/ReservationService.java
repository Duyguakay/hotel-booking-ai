package com.hotel.booking.service;

import com.hotel.booking.dto.ReservationRequestDto;
import com.hotel.booking.dto.ReservationResponseDto;

import java.util.List;

public interface ReservationService {

    ReservationResponseDto createReservation(ReservationRequestDto requestDto);

    List<ReservationResponseDto> getReservationsByUserId(Long userId);

    List<ReservationResponseDto> getReservationsForManager(Long managerUserId);

    void cancelReservation(Long id);
}