package com.hotel.booking.controller;

import com.hotel.booking.entity.Room;
import com.hotel.booking.service.WishlistService;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.List;
import java.util.Map;

@RestController
@RequestMapping("/api/wishlist")
@CrossOrigin(origins = "*")
@RequiredArgsConstructor
public class WishlistController {

    private final WishlistService wishlistService;

    @PostMapping("/toggle")
    public ResponseEntity<Map<String, Object>> toggleWishlist(
            @RequestParam(defaultValue = "1") Long userId,
            @RequestParam Long roomId
    ) {
        return ResponseEntity.ok(wishlistService.toggleWishlist(userId, roomId));
    }

    @GetMapping("/user/{userId}")
    public ResponseEntity<List<Long>> getUserWishlistIds(@PathVariable Long userId) {
        return ResponseEntity.ok(wishlistService.getUserWishlistRoomIds(userId));
    }

    @GetMapping("/user/{userId}/rooms")
    public ResponseEntity<List<Room>> getUserWishlistRooms(@PathVariable Long userId) {
        return ResponseEntity.ok(wishlistService.getUserWishlistRooms(userId));
    }

    @GetMapping("/status")
    public ResponseEntity<Map<String, Object>> getWishlistStatus(@RequestParam(defaultValue = "1") Long userId) {
        return ResponseEntity.ok(wishlistService.getWishlistStatus(userId));
    }
}
