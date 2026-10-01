package com.hotel.booking.repository;

import com.hotel.booking.entity.Room;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.JpaSpecificationExecutor;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

import java.time.LocalDate;
import java.util.List;

@Repository
public interface RoomRepository extends JpaRepository<Room, Long>, JpaSpecificationExecutor<Room> {

    List<Room> findByHotelId(Long hotelId);

    @Query("SELECT r FROM Room r JOIN FETCH r.hotel")
    List<Room> findAllWithHotel();

    @Query("SELECT r FROM Room r WHERE r.hotel.id = :hotelId AND r.id NOT IN (" +
            "SELECT res.room.id FROM Reservation res WHERE " +
            "res.status = 'CONFIRMED' AND " +
            "((res.checkInDate < :checkOutDate) AND (res.checkOutDate > :checkInDate)))")
    List<Room> findAvailableRooms(
            @Param("hotelId") Long hotelId,
            @Param("checkInDate") LocalDate checkInDate,
            @Param("checkOutDate") LocalDate checkOutDate
    );
}