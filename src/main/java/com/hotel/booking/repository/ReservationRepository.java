package com.hotel.booking.repository;

import com.hotel.booking.entity.Reservation;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

import java.time.LocalDate;
import java.util.List;

@Repository
public interface ReservationRepository extends JpaRepository<Reservation, Long> {
    List<Reservation> findByUserId(Long userId);
    List<Reservation> findByUserIdOrderByIdDesc(Long userId);

    @Query("SELECT COUNT(r) > 0 FROM Reservation r WHERE r.room.id = :roomId AND r.status <> com.hotel.booking.entity.ReservationStatus.CANCELLED AND r.checkInDate < :checkOutDate AND r.checkOutDate > :checkInDate")
    boolean existsByRoomIdAndDatesOverlap(@Param("roomId") Long roomId, @Param("checkInDate") LocalDate checkInDate, @Param("checkOutDate") LocalDate checkOutDate);

    @Query("SELECT r FROM Reservation r WHERE r.room.hotel.id = :hotelId ORDER BY r.id DESC")
    List<Reservation> findByHotelIdOrderByIdDesc(@Param("hotelId") Long hotelId);

    @Query("SELECT r FROM Reservation r ORDER BY r.id DESC")
    List<Reservation> findAllOrderByIdDesc();
}