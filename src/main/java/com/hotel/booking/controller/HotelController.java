package com.hotel.booking.controller;

import com.hotel.booking.dto.HotelResponseDto;
import com.hotel.booking.service.HotelService;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.List;

@RestController
@RequestMapping("/api/v1/hotels")
@RequiredArgsConstructor
public class HotelController {

    private final HotelService hotelService;

    @GetMapping("/search")
    public ResponseEntity<List<HotelResponseDto>> searchHotels(
            @RequestParam(required = false) String city
    ) {
        return ResponseEntity.ok(hotelService.searchHotelsByCity(city));
    }
}