package com.hotel.booking.repository;

import com.hotel.booking.entity.SmsNotification;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.List;

@Repository
public interface SmsNotificationRepository extends JpaRepository<SmsNotification, Long> {

    List<SmsNotification> findByUserIdOrderBySentAtDesc(Long userId);

    long countByUserIdAndIsReadFalse(Long userId);
}
