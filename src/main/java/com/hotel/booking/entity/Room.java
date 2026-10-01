package com.hotel.booking.entity;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import jakarta.persistence.*;
import lombok.*;

import java.math.BigDecimal;

@Entity
@Table(name = "rooms", indexes = {
        @Index(name = "idx_room_price", columnList = "pricePerNight"),
        @Index(name = "idx_room_type", columnList = "roomType"),
        @Index(name = "idx_room_capacity", columnList = "capacity"),
        @Index(name = "idx_room_hotel_id", columnList = "hotel_id")
})
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class Room {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @ManyToOne(fetch = FetchType.EAGER)
    @JoinColumn(name = "hotel_id", nullable = false)
    @JsonIgnoreProperties({"hibernateLazyInitializer", "handler"})
    private Hotel hotel;

    @Column(nullable = false)
    private String roomNumber;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false)
    private RoomType roomType;

    @Column(nullable = false)
    private BigDecimal pricePerNight;

    @Column(nullable = false)
    private Integer capacity;

    private String features;

    private String imageUrl;

    @Column(name = "wishlist_count")
    @Builder.Default
    private Integer wishlistCount = 0;

    @Transient
    @Builder.Default
    private Boolean isAvailable = true;

    @Transient
    @Builder.Default
    private String availabilityBadge = "Müsait";

    @Transient
    public String getAllSearchableText() {
        return ((hotel != null ? hotel.getName() + " " + hotel.getCity() + " " + hotel.getFeatures() + " " + hotel.getDescription() : "")
                + " " + (features != null ? features : "") + " " + (roomType != null ? roomType.name() : "")).toLowerCase();
    }
}