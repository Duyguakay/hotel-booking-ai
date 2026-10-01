package com.hotel.booking.service;

import com.hotel.booking.dto.AutoReservationOrderRequestDto;
import com.hotel.booking.dto.AutoReservationOrderResponseDto;
import com.hotel.booking.dto.ReservationRequestDto;
import com.hotel.booking.dto.ReservationResponseDto;
import com.hotel.booking.entity.AutoReservationOrder;
import com.hotel.booking.entity.AutoReservationStatus;
import com.hotel.booking.entity.Room;
import com.hotel.booking.entity.User;
import com.hotel.booking.repository.AutoReservationOrderRepository;
import com.hotel.booking.repository.ReservationRepository;
import com.hotel.booking.repository.RoomRepository;
import com.hotel.booking.repository.UserRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.stream.Collectors;

@Service
@RequiredArgsConstructor
public class AutoReservationService {

    private final AutoReservationOrderRepository autoReservationOrderRepository;
    private final RoomRepository roomRepository;
    private final UserRepository userRepository;
    private final ReservationRepository reservationRepository;
    private final ReservationService reservationService;
    private final SmsNotificationService smsNotificationService;

    @Transactional
    public AutoReservationOrderResponseDto createOrder(AutoReservationOrderRequestDto dto) {
        Long userId = dto.getUserId() != null ? dto.getUserId() : 1L;
        User user = userRepository.findById(userId)
                .orElseThrow(() -> new RuntimeException("Kullanıcı bulunamadı: " + userId));

        Room room = roomRepository.findById(dto.getRoomId())
                .orElseThrow(() -> new RuntimeException("Oda bulunamadı: " + dto.getRoomId()));

        String phone = (dto.getPhoneNumber() != null && !dto.getPhoneNumber().isBlank())
                ? dto.getPhoneNumber()
                : (user.getPhoneNumber() != null ? user.getPhoneNumber() : "+90 555 123 45 67");

        LocalDate inDate = dto.getCheckInDate() != null ? dto.getCheckInDate() : LocalDate.of(2026, 9, 10);
        LocalDate outDate = dto.getCheckOutDate() != null ? dto.getCheckOutDate() : LocalDate.of(2026, 9, 12);

        boolean isAutoBook = Boolean.TRUE.equals(dto.getAutoBook());

        AutoReservationOrder order = AutoReservationOrder.builder()
                .user(user)
                .room(room)
                .targetPrice(dto.getTargetPrice())
                .checkInDate(inDate)
                .checkOutDate(outDate)
                .phoneNumber(phone)
                .autoBook(isAutoBook)
                .status(AutoReservationStatus.ACTIVE)
                .createdAt(LocalDateTime.now())
                .build();

        AutoReservationOrder saved = autoReservationOrderRepository.save(order);

        // Bildirim Mesajı
        String smsTitle = isAutoBook ? "Otomatik Rezervasyon Talimatı Alındı" : "Fiyat Alarmı Kuruldu";
        String smsMsg = isAutoBook
                ? String.format("Sayın %s, %s (%s) için %s ₺ otomatik rezervasyon talimatı verildi. Otel fiyatı düşürdüğünde rezervasyonunuz otomatik yapılacak ve haber verilecektir.",
                        user.getFullName(), room.getHotel().getName(), room.getRoomNumber(), dto.getTargetPrice())
                : String.format("Sayın %s, %s (%s) için %s ₺ fiyat takip alarmı kuruldu. Otel fiyatı düşürdüğünde telefonunuza anında bildirim gönderilecek ve haber verilecektir.",
                        user.getFullName(), room.getHotel().getName(), room.getRoomNumber(), dto.getTargetPrice());
        
        smsNotificationService.sendSms(user.getId(), phone, smsTitle, smsMsg, "PRICE_WATCH", false);

        return mapToDto(saved);
    }

    @Transactional(readOnly = true)
    public List<AutoReservationOrderResponseDto> getUserOrders(Long userId) {
        return autoReservationOrderRepository.findByUserIdOrderByCreatedAtDesc(userId)
                .stream()
                .map(this::mapToDto)
                .collect(Collectors.toList());
    }

    @Transactional
    public void cancelOrder(Long orderId) {
        AutoReservationOrder order = autoReservationOrderRepository.findById(orderId)
                .orElseThrow(() -> new RuntimeException("Emir bulunamadı: " + orderId));
        order.setStatus(AutoReservationStatus.CANCELLED);
        autoReservationOrderRepository.save(order);

        smsNotificationService.sendSms(
                order.getUser().getId(),
                order.getPhoneNumber(),
                "Fiyat Alarmı İptal Edildi",
                String.format("%s (%s) için kurulan %s ₺ fiyat takip emriniz iptal edilmiştir.",
                        order.getRoom().getHotel().getName(), order.getRoom().getRoomNumber(), order.getTargetPrice()),
                "CANCEL",
                false
        );
    }

    @Transactional
    public List<AutoReservationOrderResponseDto> checkAndTriggerAutoReservations(Long roomId, BigDecimal newPrice) {
        List<AutoReservationOrder> activeOrders = autoReservationOrderRepository.findByRoomIdAndStatus(roomId, AutoReservationStatus.ACTIVE);
        List<AutoReservationOrderResponseDto> triggeredList = new ArrayList<>();

        for (AutoReservationOrder order : activeOrders) {
            // Eğer yeni fiyat hedef fiyatın altında veya eşitse tetikle!
            if (newPrice.compareTo(order.getTargetPrice()) <= 0) {
                try {
                    boolean isAutoBook = Boolean.TRUE.equals(order.getAutoBook());

                    if (isAutoBook) {
                        // 1. OTOMATİK REZERVASYON MODU: Rezervasyonu oluştur ve kullanıcıya bildir
                        boolean isAlreadyBooked = reservationRepository.existsByRoomIdAndDatesOverlap(
                                order.getRoom().getId(),
                                order.getCheckInDate(),
                                order.getCheckOutDate()
                        );

                        if (isAlreadyBooked) {
                            continue;
                        }

                        ReservationRequestDto resReq = new ReservationRequestDto();
                        resReq.setUserId(order.getUser().getId());
                        resReq.setRoomId(order.getRoom().getId());
                        resReq.setCheckInDate(order.getCheckInDate());
                        resReq.setCheckOutDate(order.getCheckOutDate());

                        ReservationResponseDto resRes = reservationService.createReservation(resReq);

                        order.setStatus(AutoReservationStatus.TRIGGERED);
                        order.setReservationId(resRes.getId());
                        order.setTriggeredAt(LocalDateTime.now());
                        autoReservationOrderRepository.save(order);

                        String smsTitle = "Rezervasyonunuz Otomatik Oluşturuldu!";
                        String smsMsg = String.format(
                                "MÜJDE! Sayın %s, takip ettiğiniz %s (%s) fiyatı %s ₺ seviyesine düştü! #%d numaralı rezervasyonunuz otomatik oluşturuldu. Tarih: %s -> %s. Toplam: %s ₺. İyi tatiller dileriz!",
                                order.getUser().getFullName(),
                                order.getRoom().getHotel().getName(),
                                order.getRoom().getRoomNumber(),
                                newPrice,
                                resRes.getId(),
                                order.getCheckInDate(),
                                order.getCheckOutDate(),
                                resRes.getTotalPrice()
                        );
                        smsNotificationService.sendSms(order.getUser().getId(), order.getPhoneNumber(), smsTitle, smsMsg, "RESERVATION", true);

                    } else {
                        // 2. SADECE HABER VER / FİYAT ALARMI MODU: Rezervasyon yapma, sadece bildirim gönder
                        order.setStatus(AutoReservationStatus.TRIGGERED);
                        order.setTriggeredAt(LocalDateTime.now());
                        autoReservationOrderRepository.save(order);

                        String smsTitle = "Fiyat Düştü Bildirimi!";
                        String smsMsg = String.format(
                                "MÜJDE! Sayın %s, takip ettiğiniz %s (%s) oda fiyatı beklediğiniz %s ₺ seviyesine (Güncel: %s ₺) düşmüştür! Odanızı ayırtmak için sitemizden hemen rezervasyon yapabilirsiniz.",
                                order.getUser().getFullName(),
                                order.getRoom().getHotel().getName(),
                                order.getRoom().getRoomNumber(),
                                order.getTargetPrice(),
                                newPrice
                        );
                        smsNotificationService.sendSms(order.getUser().getId(), order.getPhoneNumber(), smsTitle, smsMsg, "PRICE_WATCH", true);
                    }

                    triggeredList.add(mapToDto(order));
                } catch (Exception e) {
                    System.err.println("Fiyat alarmı / rezervasyon tetiklenirken hata oluştu: " + e.getMessage());
                }
            }
        }
        return triggeredList;
    }

    @Transactional
    public List<AutoReservationOrderResponseDto> simulatePriceDrop(Long roomId, BigDecimal newPrice) {
        Room room = roomRepository.findById(roomId)
                .orElseThrow(() -> new RuntimeException("Oda bulunamadı: " + roomId));
        
        room.setPricePerNight(newPrice);
        roomRepository.save(room);

        return checkAndTriggerAutoReservations(roomId, newPrice);
    }

    public AutoReservationOrderResponseDto mapToDto(AutoReservationOrder order) {
        return AutoReservationOrderResponseDto.builder()
                .id(order.getId())
                .userId(order.getUser() != null ? order.getUser().getId() : null)
                .userName(order.getUser() != null ? order.getUser().getFullName() : null)
                .roomId(order.getRoom() != null ? order.getRoom().getId() : null)
                .roomNumber(order.getRoom() != null ? order.getRoom().getRoomNumber() : null)
                .hotelName(order.getRoom() != null && order.getRoom().getHotel() != null ? order.getRoom().getHotel().getName() : null)
                .roomType(order.getRoom() != null && order.getRoom().getRoomType() != null ? order.getRoom().getRoomType().name() : null)
                .currentPrice(order.getRoom() != null ? order.getRoom().getPricePerNight() : null)
                .targetPrice(order.getTargetPrice())
                .checkInDate(order.getCheckInDate())
                .checkOutDate(order.getCheckOutDate())
                .phoneNumber(order.getPhoneNumber())
                .autoBook(order.getAutoBook())
                .status(order.getStatus())
                .reservationId(order.getReservationId())
                .createdAt(order.getCreatedAt())
                .triggeredAt(order.getTriggeredAt())
                .build();
    }
}
