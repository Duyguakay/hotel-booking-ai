package com.hotel.booking.service;

import com.hotel.booking.dto.ReservationRequestDto;
import com.hotel.booking.dto.ReservationResponseDto;
import com.hotel.booking.entity.Reservation;
import com.hotel.booking.entity.ReservationStatus;
import com.hotel.booking.entity.Role;
import com.hotel.booking.entity.Room;
import com.hotel.booking.entity.User;
import com.hotel.booking.repository.ReservationRepository;
import com.hotel.booking.repository.RoomRepository;
import com.hotel.booking.repository.UserRepository;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.time.temporal.ChronoUnit;
import java.util.Collections;
import java.util.List;
import java.util.stream.Collectors;

@Service
public class ReservationServiceImpl implements ReservationService {

    private final ReservationRepository reservationRepository;
    private final RoomRepository roomRepository;
    private final UserRepository userRepository;
    private final SmsNotificationService smsNotificationService;

    public ReservationServiceImpl(ReservationRepository reservationRepository,
                                  RoomRepository roomRepository,
                                  UserRepository userRepository,
                                  SmsNotificationService smsNotificationService) {
        this.reservationRepository = reservationRepository;
        this.roomRepository = roomRepository;
        this.userRepository = userRepository;
        this.smsNotificationService = smsNotificationService;
    }

    @Override
    @Transactional
    public ReservationResponseDto createReservation(ReservationRequestDto requestDto) {
        Room room = roomRepository.findById(requestDto.getRoomId())
                .orElseThrow(() -> new RuntimeException("Oda bulunamadı: " + requestDto.getRoomId()));

        User user = userRepository.findById(requestDto.getUserId())
                .orElseThrow(() -> new RuntimeException("Kullanıcı bulunamadı: " + requestDto.getUserId()));

        long nights = ChronoUnit.DAYS.between(requestDto.getCheckInDate(), requestDto.getCheckOutDate());
        if (nights <= 0) {
            throw new RuntimeException("Çıkış tarihi giriş tarihinden sonra olmalıdır.");
        }

        // Çift rezervasyon kontrolü (Aynı oda aynı tarihler için tekrar rezerve edilemez)
        boolean isAlreadyBooked = reservationRepository.existsByRoomIdAndDatesOverlap(
                room.getId(),
                requestDto.getCheckInDate(),
                requestDto.getCheckOutDate()
        );
        if (isAlreadyBooked) {
            String hotelTitle = (room.getHotel() != null ? room.getHotel().getName() : "") + " (Oda " + room.getRoomNumber() + ")";
            throw new RuntimeException(hotelTitle + " belirtilen tarihlerde (" + requestDto.getCheckInDate() + " - " + requestDto.getCheckOutDate() + ") zaten doludur ve rezerve edilmiştir.");
        }

        BigDecimal totalPrice = room.getPricePerNight().multiply(BigDecimal.valueOf(nights));

        Reservation reservation = new Reservation();
        reservation.setUser(user);
        reservation.setRoom(room);
        reservation.setCheckInDate(requestDto.getCheckInDate());
        reservation.setCheckOutDate(requestDto.getCheckOutDate());
        reservation.setTotalPrice(totalPrice);
        reservation.setStatus(ReservationStatus.CONFIRMED);

        Reservation saved = reservationRepository.save(reservation);

        // Otel yöneticisine özel bildirim oluştur
        if (room.getHotel() != null) {
            try {
                List<User> managers = userRepository.findByHotelId(room.getHotel().getId());
                for (User mgr : managers) {
                    String mgrTitle = "Yeni Otel Rezervasyonu Alındı";
                    String mgrMsg = String.format(
                            "Oteliniz %s (Oda %s) için %s tarafından rezervasyon yapıldı. Tarihler: %s -> %s. Tutar: %.0f ₺. İletişim: %s",
                            room.getHotel().getName(),
                            room.getRoomNumber(),
                            user.getFullName(),
                            saved.getCheckInDate(),
                            saved.getCheckOutDate(),
                            saved.getTotalPrice(),
                            user.getPhoneNumber() != null ? user.getPhoneNumber() : "+90 555 123 45 67"
                    );
                    smsNotificationService.sendSms(mgr.getId(), mgr.getPhoneNumber(), mgrTitle, mgrMsg, "HOTEL_MANAGER", false);
                }
            } catch (Exception e) {
                System.err.println("Yönetici bildirimi oluşturma uyarısı: " + e.getMessage());
            }
        }

        return mapToDto(saved);
    }

    @Override
    @Transactional(readOnly = true)
    public List<ReservationResponseDto> getReservationsByUserId(Long userId) {
        return reservationRepository.findByUserIdOrderByIdDesc(userId)
                .stream()
                .map(this::mapToDto)
                .collect(Collectors.toList());
    }

    @Override
    @Transactional(readOnly = true)
    public List<ReservationResponseDto> getReservationsForManager(Long managerUserId) {
        User manager = userRepository.findById(managerUserId)
                .orElseThrow(() -> new RuntimeException("Kullanıcı bulunamadı: " + managerUserId));

        if (manager.getRole() == Role.ADMIN) {
            return reservationRepository.findAllOrderByIdDesc()
                    .stream()
                    .map(this::mapToDto)
                    .collect(Collectors.toList());
        }

        if (manager.getRole() == Role.HOTEL_MANAGER && manager.getHotelId() != null) {
            return reservationRepository.findByHotelIdOrderByIdDesc(manager.getHotelId())
                    .stream()
                    .map(this::mapToDto)
                    .collect(Collectors.toList());
        }

        return Collections.emptyList();
    }

    @Override
    @Transactional
    public void cancelReservation(Long id) {
        Reservation res = reservationRepository.findById(id)
                .orElseThrow(() -> new RuntimeException("Rezervasyon bulunamadı: " + id));

        Room room = res.getRoom();
        User user = res.getUser();

        // 1. Otel yöneticisine iptal bildirimi gönder
        if (room != null && room.getHotel() != null) {
            try {
                List<User> managers = userRepository.findByHotelId(room.getHotel().getId());
                for (User mgr : managers) {
                    String mgrTitle = "Rezervasyon İptal Bildirimi";
                    String mgrMsg = String.format(
                            "Oteliniz %s (Oda %s) için #%d numaralı rezervasyon (%s) iptal edilmiştir. Tarihler: %s -> %s. Oda tekrar müsait durumdadır.",
                            room.getHotel().getName(),
                            room.getRoomNumber(),
                            res.getId(),
                            user != null ? user.getFullName() : "Misafir",
                            res.getCheckInDate(),
                            res.getCheckOutDate()
                    );
                    smsNotificationService.sendSms(mgr.getId(), mgr.getPhoneNumber(), mgrTitle, mgrMsg, "HOTEL_MANAGER", false);
                }
            } catch (Exception e) {
                System.err.println("Yönetici iptal bildirimi gönderme uyarısı: " + e.getMessage());
            }
        }

        // 2. Müşteriye de iptal bildirimi oluştur
        if (user != null && room != null && room.getHotel() != null) {
            try {
                String custTitle = "Rezervasyon İptal Onayı";
                String custMsg = String.format(
                        "Sayın %s, %s (Oda %s) için #%d numaralı rezervasyonunuz başarıyla iptal edilmiştir.",
                        user.getFullName(),
                        room.getHotel().getName(),
                        room.getRoomNumber(),
                        res.getId()
                );
                smsNotificationService.sendSms(user.getId(), user.getPhoneNumber(), custTitle, custMsg, "CUSTOMER", false);
            } catch (Exception ignored) {}
        }

        reservationRepository.deleteById(id);
    }

    private ReservationResponseDto mapToDto(Reservation res) {
        Room room = res.getRoom();
        User user = res.getUser();

        return ReservationResponseDto.builder()
                .id(res.getId())
                .userId(user != null ? user.getId() : null)
                .guestName(user != null ? user.getFullName() : "Misafir")
                .guestEmail(user != null ? user.getEmail() : null)
                .guestPhone(user != null ? user.getPhoneNumber() : "+90 555 123 45 67")
                .roomId(room != null ? room.getId() : null)
                .roomNumber(room != null ? room.getRoomNumber() : null)
                .roomType(room != null && room.getRoomType() != null ? room.getRoomType().name() : null)
                .hotelName(room != null && room.getHotel() != null ? room.getHotel().getName() : "Otel")
                .hotelCity(room != null && room.getHotel() != null ? room.getHotel().getCity() : null)
                .checkInDate(res.getCheckInDate())
                .checkOutDate(res.getCheckOutDate())
                .totalPrice(res.getTotalPrice())
                .status(res.getStatus())
                .build();
    }
}