package com.hotel.booking.repository;

import com.hotel.booking.entity.AutoReservationOrder;
import com.hotel.booking.entity.AutoReservationStatus;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.List;

@Repository
public interface AutoReservationOrderRepository extends JpaRepository<AutoReservationOrder, Long> {

    List<AutoReservationOrder> findByRoomIdAndStatus(Long roomId, AutoReservationStatus status);

    List<AutoReservationOrder> findByUserIdOrderByCreatedAtDesc(Long userId);

    List<AutoReservationOrder> findByStatus(AutoReservationStatus status);
}
