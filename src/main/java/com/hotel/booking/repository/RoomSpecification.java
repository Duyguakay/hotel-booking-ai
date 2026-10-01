package com.hotel.booking.repository;

import com.hotel.booking.entity.Hotel;
import com.hotel.booking.entity.Reservation;
import com.hotel.booking.entity.ReservationStatus;
import com.hotel.booking.entity.Room;
import com.hotel.booking.entity.RoomType;
import jakarta.persistence.criteria.Join;
import jakarta.persistence.criteria.JoinType;
import jakarta.persistence.criteria.Predicate;
import jakarta.persistence.criteria.Root;
import jakarta.persistence.criteria.Subquery;
import org.springframework.data.jpa.domain.Specification;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

public class RoomSpecification {

    public static Specification<Room> filterBy(
            String city,
            RoomType roomType,
            int capacity,
            BigDecimal maxPrice,
            String hotelName,
            String feature
    ) {
        return filterBy(city, roomType, capacity, maxPrice, hotelName, feature, null, null);
    }

    public static Specification<Room> filterBy(
            String city,
            RoomType roomType,
            int capacity,
            BigDecimal maxPrice,
            String hotelName,
            String feature,
            LocalDate checkInDate,
            LocalDate checkOutDate
    ) {
        return (root, query, cb) -> {
            // Eager fetch hotel to prevent N+1 query problem
            if (query != null && Long.class != query.getResultType() && long.class != query.getResultType()) {
                root.fetch("hotel", JoinType.INNER);
            }

            Join<Room, Hotel> hotelJoin = root.join("hotel", JoinType.INNER);
            List<Predicate> predicates = new ArrayList<>();

            // 1. Şehir Filtresi (SQL LIKE)
            if (city != null && !city.isBlank() && !city.equalsIgnoreCase("Boş")) {
                String c = normalize(city);
                if (c.contains("sapanc")) {
                    predicates.add(cb.or(
                            cb.like(cb.lower(hotelJoin.get("city")), "%sakarya%"),
                            cb.like(cb.lower(hotelJoin.get("address")), "%sapanc%"),
                            cb.like(cb.lower(hotelJoin.get("city")), "%sapanc%")
                    ));
                } else {
                    predicates.add(cb.or(
                            cb.like(cb.lower(hotelJoin.get("city")), "%" + c + "%"),
                            cb.like(cb.lower(hotelJoin.get("address")), "%" + c + "%")
                    ));
                }
            }

            // 2. Oda Türü Filtresi
            if (roomType != null) {
                predicates.add(cb.equal(root.get("roomType"), roomType));
            }

            // 3. Kapasite Filtresi (Kişi sayısı >= istenen)
            if (capacity > 0) {
                predicates.add(cb.greaterThanOrEqualTo(root.get("capacity"), capacity));
            }

            // 4. Maksimum Fiyat Filtresi
            if (maxPrice != null && maxPrice.compareTo(BigDecimal.ZERO) > 0) {
                predicates.add(cb.lessThanOrEqualTo(root.get("pricePerNight"), maxPrice));
            }

            // 5. Otel Adı Filtresi
            if (hotelName != null && !hotelName.isBlank() && !hotelName.equalsIgnoreCase("Boş")) {
                predicates.add(cb.like(cb.lower(hotelJoin.get("name")), "%" + normalize(hotelName) + "%"));
            }

            // 6. Olanak / Özellik Filtresi (Çoklu özellik desteği ile)
            if (feature != null && !feature.isBlank() && !feature.equalsIgnoreCase("Boş")) {
                String cleanFeat = normalize(feature);
                String[] feats = cleanFeat.split("[,\\s+ve]+");
                for (String f : feats) {
                    String fn = f.trim();
                    if (!fn.isEmpty() && fn.length() > 2) {
                        if (fn.equals("wifi") || fn.equals("wi-fi")) {
                            predicates.add(cb.or(
                                    cb.like(cb.lower(root.get("features")), "%wifi%"),
                                    cb.like(cb.lower(root.get("features")), "%wi-fi%"),
                                    cb.like(cb.lower(hotelJoin.get("features")), "%wifi%"),
                                    cb.like(cb.lower(hotelJoin.get("features")), "%wi-fi%")
                            ));
                        } else {
                            predicates.add(cb.or(
                                    cb.like(cb.lower(root.get("features")), "%" + fn + "%"),
                                    cb.like(cb.lower(hotelJoin.get("features")), "%" + fn + "%"),
                                    cb.like(cb.lower(hotelJoin.get("description")), "%" + fn + "%")
                            ));
                        }
                    }
                }
            }

            // 7. Tarih ve Müsaitlik Filtresi (Daha önce rezerve edilmiş odaları ele)
            if (checkInDate != null && checkOutDate != null && query != null) {
                Subquery<Long> subquery = query.subquery(Long.class);
                Root<Reservation> resRoot = subquery.from(Reservation.class);
                subquery.select(resRoot.get("room").get("id"));

                Predicate statusPredicate = cb.equal(resRoot.get("status"), ReservationStatus.CONFIRMED);
                Predicate dateOverlap = cb.and(
                        cb.lessThan(resRoot.get("checkInDate"), checkOutDate),
                        cb.greaterThan(resRoot.get("checkOutDate"), checkInDate)
                );
                subquery.where(cb.and(statusPredicate, dateOverlap));

                predicates.add(cb.not(root.get("id").in(subquery)));
            }

            return predicates.isEmpty() ? cb.conjunction() : cb.and(predicates.toArray(new Predicate[0]));
        };
    }

    private static String normalize(String str) {
        if (str == null) return "";
        return str.toLowerCase(new Locale("tr", "TR"))
                .replace("ı", "i")
                .replace("ğ", "g")
                .replace("ü", "u")
                .replace("ş", "s")
                .replace("ö", "o")
                .replace("ç", "c")
                .trim();
    }
}
