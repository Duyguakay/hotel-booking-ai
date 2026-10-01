package com.hotel.booking.controller;

import com.hotel.booking.dto.AutoReservationOrderRequestDto;
import com.hotel.booking.dto.AutoReservationOrderResponseDto;
import com.hotel.booking.dto.PriceUpdateDto;
import com.hotel.booking.service.AutoReservationService;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.List;

@RestController
@RequestMapping("/api/auto-reservations")
@CrossOrigin(origins = "*")
@RequiredArgsConstructor
public class AutoReservationController {

    private final AutoReservationService autoReservationService;

    @PostMapping
    public ResponseEntity<AutoReservationOrderResponseDto> createOrder(@RequestBody AutoReservationOrderRequestDto requestDto) {
        AutoReservationOrderResponseDto response = autoReservationService.createOrder(requestDto);
        return ResponseEntity.ok(response);
    }

    @GetMapping("/user/{userId}")
    public ResponseEntity<List<AutoReservationOrderResponseDto>> getUserOrders(@PathVariable Long userId) {
        List<AutoReservationOrderResponseDto> orders = autoReservationService.getUserOrders(userId);
        return ResponseEntity.ok(orders);
    }

    @DeleteMapping("/{orderId}")
    public ResponseEntity<Void> cancelOrder(@PathVariable Long orderId) {
        autoReservationService.cancelOrder(orderId);
        return ResponseEntity.noContent().build();
    }

    @PostMapping("/simulate-price-drop")
    public ResponseEntity<List<AutoReservationOrderResponseDto>> simulatePriceDrop(@RequestBody PriceUpdateDto dto) {
        List<AutoReservationOrderResponseDto> triggered = autoReservationService.simulatePriceDrop(dto.getRoomId(), dto.getNewPrice());
        return ResponseEntity.ok(triggered);
    }
}
