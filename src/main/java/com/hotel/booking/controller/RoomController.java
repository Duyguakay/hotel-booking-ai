package com.hotel.booking.controller;

import com.hotel.booking.entity.Role;
import com.hotel.booking.entity.Room;
import com.hotel.booking.entity.RoomType;
import com.hotel.booking.entity.User;
import com.hotel.booking.repository.RoomJdbcRepository;
import com.hotel.booking.repository.UserRepository;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;

@RestController
@RequestMapping("/api/rooms")
@CrossOrigin(origins = "*")
public class RoomController {

    private final RoomJdbcRepository roomJdbcRepository;
    private final com.hotel.booking.service.RoomService roomService;
    private final UserRepository userRepository;

    public RoomController(RoomJdbcRepository roomJdbcRepository,
                          com.hotel.booking.service.RoomService roomService,
                          UserRepository userRepository) {
        this.roomJdbcRepository = roomJdbcRepository;
        this.roomService = roomService;
        this.userRepository = userRepository;
    }

    @PutMapping("/{id}/price")
    public ResponseEntity<?> updatePrice(@PathVariable Long id, @RequestParam BigDecimal newPrice) {
        var res = roomService.updateRoomPrice(id, newPrice);
        return ResponseEntity.ok(res);
    }

    @GetMapping("/manager/{userId}")
    public ResponseEntity<?> getRoomsForManager(@PathVariable Long userId) {
        User user = userRepository.findById(userId).orElse(null);
        if (user == null) {
            return ResponseEntity.badRequest().body("Kullanıcı bulunamadı.");
        }

        if (user.getRole() == Role.ADMIN) {
            List<Room> allRooms = roomJdbcRepository.findRoomsJdbc(null, null, 0, null, null, null, null, null, "price_asc", 0, 100);
            return ResponseEntity.ok(allRooms);
        } else if (user.getRole() == Role.HOTEL_MANAGER && user.getHotelId() != null) {
            List<Room> hotelRooms = roomJdbcRepository.findRoomsByHotelIdJdbc(user.getHotelId(), null, null);
            return ResponseEntity.ok(hotelRooms);
        } else {
            return ResponseEntity.status(403).body("Bu panele erişim yetkiniz bulunmuyor. Yalnızca otel yöneticileri erişebilir.");
        }
    }

    @GetMapping
    public ResponseEntity<List<Room>> getRooms(
            @RequestParam(required = false) String city,
            @RequestParam(required = false) RoomType roomType,
            @RequestParam(required = false, defaultValue = "0") int capacity,
            @RequestParam(required = false) BigDecimal maxPrice,
            @RequestParam(required = false) String hotelName,
            @RequestParam(required = false) String feature,
            @RequestParam(required = false) LocalDate checkInDate,
            @RequestParam(required = false) LocalDate checkOutDate,
            @RequestParam(required = false, defaultValue = "price_asc") String sortBy,
            @RequestParam(required = false, defaultValue = "0") int page,
            @RequestParam(required = false, defaultValue = "20") int size
    ) {
        // Doğrudan JDBC SQL sorgusu ile veritabanından optimize filtreleme:
        List<Room> rooms = roomJdbcRepository.findRoomsJdbc(
                city, roomType, capacity, maxPrice, hotelName, feature,
                checkInDate, checkOutDate, sortBy, page, size
        );

        return ResponseEntity.ok(rooms);
    }

    @GetMapping("/hotel/{hotelId}")
    public ResponseEntity<List<Room>> getRoomsByHotel(
            @PathVariable Long hotelId,
            @RequestParam(required = false) LocalDate checkInDate,
            @RequestParam(required = false) LocalDate checkOutDate
    ) {
        List<Room> rooms = roomJdbcRepository.findRoomsByHotelIdJdbc(hotelId, checkInDate, checkOutDate);
        return ResponseEntity.ok(rooms);
    }
}