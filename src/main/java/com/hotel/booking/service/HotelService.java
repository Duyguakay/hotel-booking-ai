package com.hotel.booking.service;

import com.hotel.booking.dto.HotelResponseDto;
import com.hotel.booking.entity.Hotel;
import com.hotel.booking.repository.HotelRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;

import java.util.List;
import java.util.stream.Collectors;

@Service
@RequiredArgsConstructor
public class HotelService {

    private final HotelRepository hotelRepository;

    public List<HotelResponseDto> searchHotelsByCity(String city) {
        List<Hotel> hotels;

        // Şehir belirtilmemişse tüm otelleri getir, belirtilmişse şehre göre filtrele
        if (city == null || city.trim().isEmpty()) {
            hotels = hotelRepository.findAll();
        } else {
            hotels = hotelRepository.findByCityIgnoreCase(city.trim());
        }

        return hotels.stream()
                .map(hotel -> HotelResponseDto.builder()
                        .id(hotel.getId())
                        .name(hotel.getName())
                        .city(hotel.getCity())
                        .address(hotel.getAddress())
                        .description(hotel.getDescription())
                        .features(hotel.getFeatures())
                        .rating(hotel.getRating())
                        .wishlistCount(hotel.getWishlistCount() != null ? hotel.getWishlistCount() : 0)
                        .build())
                .collect(Collectors.toList());
    }
}