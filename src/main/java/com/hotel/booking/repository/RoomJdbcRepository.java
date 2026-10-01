package com.hotel.booking.repository;

import com.hotel.booking.entity.Hotel;
import com.hotel.booking.entity.Room;
import com.hotel.booking.entity.RoomType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.stereotype.Repository;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

@Repository
public class RoomJdbcRepository {

    private final JdbcTemplate jdbcTemplate;

    public RoomJdbcRepository(JdbcTemplate jdbcTemplate) {
        this.jdbcTemplate = jdbcTemplate;
    }

    private final RowMapper<Room> roomRowMapper = (rs, rowNum) -> {
        Hotel hotel = Hotel.builder()
                .id(rs.getLong("hotel_id"))
                .name(rs.getString("hotel_name"))
                .city(rs.getString("city"))
                .address(rs.getString("address"))
                .description(rs.getString("description"))
                .features(rs.getString("hotel_features"))
                .rating(rs.getObject("rating") != null ? rs.getDouble("rating") : null)
                .wishlistCount(rs.getObject("wishlist_count") != null ? rs.getInt("wishlist_count") : 0)
                .build();

        String roomTypeStr = rs.getString("room_type");
        RoomType roomType = null;
        if (roomTypeStr != null) {
            try {
                roomType = RoomType.valueOf(roomTypeStr.toUpperCase().trim());
            } catch (Exception ignored) {}
        }

        boolean isAvail = rs.getObject("is_available") == null || rs.getInt("is_available") == 1;
        String badge = isAvail ? "Müsait" : "Dolu (Rezerve)";

        return Room.builder()
                .id(rs.getLong("room_id"))
                .roomNumber(rs.getString("room_number"))
                .roomType(roomType)
                .pricePerNight(rs.getBigDecimal("price_per_night"))
                .capacity(rs.getInt("capacity"))
                .features(rs.getString("room_features"))
                .imageUrl(rs.getString("image_url"))
                .wishlistCount(rs.getObject("room_wishlist_count") != null ? rs.getInt("room_wishlist_count") : 0)
                .hotel(hotel)
                .isAvailable(isAvail)
                .availabilityBadge(badge)
                .build();
    };

    /**
     * JDBC Formatında Dinamik SQL Arama ve Filtreleme Motoru
     * Büyük veri kümelerinde doğrudan veritabanı seviyesinde optimize çalışır.
     */
    public List<Room> findRoomsJdbc(
            String city,
            RoomType roomType,
            int capacity,
            BigDecimal maxPrice,
            String hotelName,
            String feature,
            LocalDate checkInDate,
            LocalDate checkOutDate,
            String sortBy,
            int page,
            int size
    ) {
        StringBuilder sql = new StringBuilder();
        List<Object> params = new ArrayList<>();

        LocalDate inDate = (checkInDate != null) ? checkInDate : LocalDate.of(2026, 9, 10);
        LocalDate outDate = (checkOutDate != null) ? checkOutDate : LocalDate.of(2026, 9, 12);

        // 1. SELECT ve JOIN Ana Sorgusu + Müsaitlik (Doluluk / Boşluk) Kontrolü
        sql.append("SELECT ")
           .append("r.id AS room_id, r.room_number, r.room_type, r.price_per_night, r.capacity, ")
           .append("r.features AS room_features, r.image_url, COALESCE(r.wishlist_count, 0) AS room_wishlist_count, ")
           .append("h.id AS hotel_id, h.name AS hotel_name, h.city, h.address, h.description, ")
           .append("h.features AS hotel_features, h.rating, COALESCE(h.wishlist_count, 0) AS wishlist_count, ")
           .append("(CASE WHEN EXISTS (")
           .append("  SELECT 1 FROM reservations res ")
           .append("  WHERE res.room_id = r.id AND res.status = 'CONFIRMED' ")
           .append("  AND (res.check_in_date < ? AND res.check_out_date > ?)")
           .append(") THEN 0 ELSE 1 END) AS is_available ")
           .append("FROM rooms r ")
           .append("JOIN hotels h ON r.hotel_id = h.id ")
           .append("WHERE 1=1 ");

        params.add(outDate);
        params.add(inDate);

        // 2. Şehir / Bölge Filtresi
        if (city != null && !city.isBlank()) {
            String normCity = "%" + normalize(city) + "%";
            sql.append("AND (LOWER(h.city) LIKE ? OR LOWER(h.address) LIKE ?) ");
            params.add(normCity);
            params.add(normCity);
        }

        // 3. Konaklama Türü Filtresi
        if (roomType != null) {
            sql.append("AND r.room_type = ? ");
            params.add(roomType.name());
        }

        // 4. Minimum Kapasite Filtresi
        if (capacity > 0) {
            sql.append("AND r.capacity >= ? ");
            params.add(capacity);
        }

        // 5. Maksimum Bütçe / Fiyat Filtresi
        if (maxPrice != null && maxPrice.compareTo(BigDecimal.ZERO) > 0) {
            sql.append("AND r.price_per_night <= ? ");
            params.add(maxPrice);
        }

        // 6. Otel Adı Filtresi
        if (hotelName != null && !hotelName.isBlank()) {
            sql.append("AND LOWER(h.name) LIKE ? ");
            params.add("%" + normalize(hotelName) + "%");
        }

        // 7. Olanaklar / Özellikler Filtresi (Wi-Fi, Havuz, Jakuzi, Şömine, Bar vb.)
        if (feature != null && !feature.isBlank()) {
            String[] tokens = feature.split("[,\\s+]+");
            for (String tok : tokens) {
                String cleanTok = normalize(tok);
                if (cleanTok.length() >= 2 && !cleanTok.equals("ve") && !cleanTok.equals("ile")) {
                    if (cleanTok.equals("bar")) {
                        // "bar" kelimesi "barbekü" ile eşleşmemeli
                        sql.append("AND ((LOWER(COALESCE(r.features, '')) LIKE '%bar%' AND LOWER(COALESCE(r.features, '')) NOT LIKE '%barbek%') ")
                           .append("OR (LOWER(COALESCE(h.features, '')) LIKE '%bar%' AND LOWER(COALESCE(h.features, '')) NOT LIKE '%barbek%') ")
                           .append("OR (LOWER(COALESCE(h.description, '')) LIKE '%bar%' AND LOWER(COALESCE(h.description, '')) NOT LIKE '%barbek%')) ");
                    } else {
                        sql.append("AND (LOWER(COALESCE(r.features, '')) LIKE ? OR LOWER(COALESCE(h.features, '')) LIKE ? OR LOWER(COALESCE(h.description, '')) LIKE ?) ");
                        params.add("%" + cleanTok + "%");
                        params.add("%" + cleanTok + "%");
                        params.add("%" + cleanTok + "%");
                    }
                }
            }
        }

        // 8. Tarih Çakışması ve Müsaitlik Kontrolü (SQL Subquery)
        if (checkInDate != null && checkOutDate != null) {
            sql.append("AND r.id NOT IN (")
               .append("  SELECT res.room_id FROM reservations res ")
               .append("  WHERE res.status = 'CONFIRMED' ")
               .append("  AND (res.check_in_date < ? AND res.check_out_date > ?)")
               .append(") ");
            params.add(checkOutDate);
            params.add(checkInDate);
        }

        // 9. Sıralama (ORDER BY)
        if ("price_desc".equalsIgnoreCase(sortBy)) {
            sql.append("ORDER BY r.price_per_night DESC ");
        } else if ("capacity_desc".equalsIgnoreCase(sortBy)) {
            sql.append("ORDER BY r.capacity DESC ");
        } else if ("rating_desc".equalsIgnoreCase(sortBy)) {
            sql.append("ORDER BY h.rating DESC ");
        } else {
            sql.append("ORDER BY r.price_per_night ASC ");
        }

        // 10. Sayfalama ve Limit (Büyük verilerde milisaniyelik yanıt için LIMIT & OFFSET)
        int limit = Math.max(1, Math.min(size, 100));
        int offset = Math.max(0, page) * limit;
        sql.append("LIMIT ? OFFSET ?");
        params.add(limit);
        params.add(offset);

        // Doğrudan JDBC ile veritabanında çalıştır ve Room listesi döndür:
        return jdbcTemplate.query(sql.toString(), roomRowMapper, params.toArray());
    }

    /**
     * Belirli bir otele ait tüm odaları ve anlık müsaitlik (boş/dolu) durumlarını getirir.
     */
    public List<Room> findRoomsByHotelIdJdbc(Long hotelId, LocalDate checkInDate, LocalDate checkOutDate) {
        LocalDate inDate = (checkInDate != null) ? checkInDate : LocalDate.of(2026, 9, 10);
        LocalDate outDate = (checkOutDate != null) ? checkOutDate : LocalDate.of(2026, 9, 12);

        String sql = "SELECT " +
                "r.id AS room_id, r.room_number, r.room_type, r.price_per_night, r.capacity, " +
                "r.features AS room_features, r.image_url, COALESCE(r.wishlist_count, 0) AS room_wishlist_count, " +
                "h.id AS hotel_id, h.name AS hotel_name, h.city, h.address, h.description, " +
                "h.features AS hotel_features, h.rating, COALESCE(h.wishlist_count, 0) AS wishlist_count, " +
                "(CASE WHEN EXISTS (" +
                "  SELECT 1 FROM reservations res " +
                "  WHERE res.room_id = r.id AND res.status = 'CONFIRMED' " +
                "  AND (res.check_in_date < ? AND res.check_out_date > ?)" +
                ") THEN 0 ELSE 1 END) AS is_available " +
                "FROM rooms r " +
                "JOIN hotels h ON r.hotel_id = h.id " +
                "WHERE r.hotel_id = ? " +
                "ORDER BY r.price_per_night ASC";

        return jdbcTemplate.query(sql, roomRowMapper, outDate, inDate, hotelId);
    }

    private String normalize(String str) {
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
