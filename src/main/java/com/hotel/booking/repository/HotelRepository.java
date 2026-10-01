package com.hotel.booking.repository;

import com.hotel.booking.entity.Hotel;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.List;

@Repository
public interface HotelRepository extends JpaRepository<Hotel, Long> {
    // Şehre göre otelleri bulma (Büyük/küçük harf duyarsız)
    List<Hotel> findByCityIgnoreCase(String city);
}