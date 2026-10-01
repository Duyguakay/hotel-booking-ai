package com.hotel.booking.service;

import com.hotel.booking.entity.Room;
import com.hotel.booking.entity.User;
import com.hotel.booking.entity.Wishlist;
import com.hotel.booking.exception.ResourceNotFoundException;
import com.hotel.booking.repository.RoomRepository;
import com.hotel.booking.repository.UserRepository;
import com.hotel.booking.repository.WishlistRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

@Service
@RequiredArgsConstructor
public class WishlistService {

    private final WishlistRepository wishlistRepository;
    private final RoomRepository roomRepository;
    private final UserRepository userRepository;

    @Transactional
    public Map<String, Object> toggleWishlist(Long userId, Long roomId) {
        Room room = roomRepository.findById(roomId)
                .orElseThrow(() -> new ResourceNotFoundException("Oda bulunamadı: " + roomId));

        User user = userRepository.findById(userId)
                .orElseThrow(() -> new ResourceNotFoundException("Kullanıcı bulunamadı: " + userId));

        boolean exists = wishlistRepository.existsByUserIdAndRoomId(userId, roomId);
        boolean inWishlist;
        int currentCount = room.getWishlistCount() != null ? room.getWishlistCount() : 0;

        if (exists) {
            wishlistRepository.deleteByUserIdAndRoomId(userId, roomId);
            inWishlist = false;
            currentCount = Math.max(0, currentCount - 1);
        } else {
            Wishlist wishlist = Wishlist.builder()
                    .user(user)
                    .room(room)
                    .createdAt(LocalDateTime.now())
                    .build();
            wishlistRepository.save(wishlist);
            inWishlist = true;
            currentCount = currentCount + 1;
        }

        room.setWishlistCount(currentCount);
        roomRepository.save(room);

        Map<String, Object> response = new HashMap<>();
        response.put("roomId", roomId);
        response.put("inWishlist", inWishlist);
        response.put("wishlistCount", currentCount);
        response.put("message", inWishlist ? "Oda listenize eklendi" : "Oda listenizden çıkarıldı");
        return response;
    }

    @Transactional(readOnly = true)
    public List<Long> getUserWishlistRoomIds(Long userId) {
        return wishlistRepository.findByUserIdOrderByCreatedAtDesc(userId)
                .stream()
                .map(w -> w.getRoom().getId())
                .collect(Collectors.toList());
    }

    @Transactional(readOnly = true)
    public List<Room> getUserWishlistRooms(Long userId) {
        return wishlistRepository.findByUserIdOrderByCreatedAtDesc(userId)
                .stream()
                .map(Wishlist::getRoom)
                .collect(Collectors.toList());
    }

    @Transactional(readOnly = true)
    public Map<String, Object> getWishlistStatus(Long userId) {
        List<Long> roomIds = getUserWishlistRoomIds(userId);
        Map<String, Object> result = new HashMap<>();
        result.put("userId", userId);
        result.put("wishlistRoomIds", roomIds);
        result.put("totalCount", roomIds.size());
        return result;
    }
}
