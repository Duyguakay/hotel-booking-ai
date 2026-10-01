package com.hotel.booking.entity;

import com.fasterxml.jackson.annotation.JsonIgnore;
import jakarta.persistence.*;
import lombok.*;

import java.util.ArrayList;
import java.util.List;

@Entity
@Table(name = "hotels", indexes = {
        @Index(name = "idx_hotel_city", columnList = "city"),
        @Index(name = "idx_hotel_name", columnList = "name")
})
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class Hotel {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(nullable = false)
    private String name;

    @Column(nullable = false)
    private String city;

    private String address;

    @Column(length = 1000)
    private String description;

    @Column(name = "features")
    private String features;

    private Double rating;

    @Builder.Default
    @Column(name = "wishlist_count")
    private Integer wishlistCount = 0;

    @Builder.Default
    @OneToMany(mappedBy = "hotel", cascade = CascadeType.ALL, orphanRemoval = true)
    @JsonIgnore
    private List<Room> rooms = new ArrayList<>();
}