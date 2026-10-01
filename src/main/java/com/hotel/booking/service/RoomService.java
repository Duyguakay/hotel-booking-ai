package com.hotel.booking.service;

import com.hotel.booking.dto.RoomResponseDto;
import com.hotel.booking.entity.Room;
import com.hotel.booking.repository.RoomRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;

import java.time.LocalDate;
import java.util.List;
import java.util.stream.Collectors;

@Service
@RequiredArgsConstructor
public class RoomService {

    private final RoomRepository roomRepository;
    private final AutoReservationService autoReservationService;

    public List<RoomResponseDto> findAvailableRooms(Long hotelId, LocalDate checkIn, LocalDate checkOut) {
        List<Room> rooms;

        if (checkIn == null || checkOut == null) {
            rooms = roomRepository.findByHotelId(hotelId);
        } else {
            rooms = roomRepository.findAvailableRooms(hotelId, checkIn, checkOut);
        }

        return rooms.stream()
                .map(this::mapToDto)
                .collect(Collectors.toList());
    }

    public RoomResponseDto updateRoomPrice(Long roomId, java.math.BigDecimal newPrice) {
        Room room = roomRepository.findById(roomId)
                .orElseThrow(() -> new RuntimeException("Oda bulunamadı: " + roomId));
        room.setPricePerNight(newPrice);
        Room saved = roomRepository.save(room);

        // Fiyat düşünce otomatik rezervasyon emirlerini kontrol et ve tetikle
        try {
            autoReservationService.checkAndTriggerAutoReservations(roomId, newPrice);
        } catch (Exception e) {
            System.err.println("Otomatik rezervasyon tetikleme uyarısı: " + e.getMessage());
        }

        return mapToDto(saved);
    }

    private RoomResponseDto mapToDto(Room room) {
        return RoomResponseDto.builder()
                .id(room.getId())
                .roomNumber(room.getRoomNumber())
                .roomType(room.getRoomType()) // .name() kaldırıldı, doğrudan Enum veriliyor
                .capacity(room.getCapacity())
                .pricePerNight(room.getPricePerNight())
                .hotelId(room.getHotel().getId())
                .hotelName(room.getHotel().getName())
                .build();
    }
}