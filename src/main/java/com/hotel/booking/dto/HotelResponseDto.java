package com.hotel.booking.dto;

import lombok.Builder;
import lombok.Data;

@Data
@Builder
public class HotelResponseDto {
    private Long id;
    private String name;
    private String city;
    private String address;
    private String description;
    private String features;
    private Double rating;
    private Integer wishlistCount;
}