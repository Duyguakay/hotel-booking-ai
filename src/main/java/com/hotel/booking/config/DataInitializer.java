package com.hotel.booking.config;

import com.hotel.booking.entity.Hotel;
import com.hotel.booking.entity.Role;
import com.hotel.booking.entity.Room;
import com.hotel.booking.entity.RoomType;
import com.hotel.booking.entity.User;
import com.hotel.booking.repository.HotelRepository;
import com.hotel.booking.repository.RoomRepository;
import com.hotel.booking.repository.UserRepository;
import org.springframework.boot.CommandLineRunner;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Component;

import java.math.BigDecimal;

@Component
public class DataInitializer implements CommandLineRunner {

    private final HotelRepository hotelRepository;
    private final RoomRepository roomRepository;
    private final UserRepository userRepository;
    private final JdbcTemplate jdbcTemplate;
    private final PasswordEncoder passwordEncoder;

    public DataInitializer(HotelRepository hotelRepository,
            RoomRepository roomRepository,
            UserRepository userRepository,
            JdbcTemplate jdbcTemplate,
            PasswordEncoder passwordEncoder) {
        this.hotelRepository = hotelRepository;
        this.roomRepository = roomRepository;
        this.userRepository = userRepository;
        this.jdbcTemplate = jdbcTemplate;
        this.passwordEncoder = passwordEncoder;
    }

    @Override
    public void run(String... args) {
        // Tablo kolon tiplerini güncelle (ENUM -> VARCHAR uyumluluğu için)
        try {
            jdbcTemplate.execute("ALTER TABLE users MODIFY COLUMN role VARCHAR(50)");
        } catch (Exception ignored) {}
        try {
            jdbcTemplate.execute("ALTER TABLE users ADD COLUMN hotel_id BIGINT");
        } catch (Exception ignored) {}
        try {
            jdbcTemplate.execute("ALTER TABLE auto_reservation_orders ADD COLUMN auto_book BOOLEAN DEFAULT FALSE");
        } catch (Exception ignored) {}

        // 0. Varsayılan Kullanıcıyı Oluştur / Güncelle (zeynep / 123456)
        User zeynep = userRepository.findByUsername("zeynep")
                .or(() -> userRepository.findByEmail("zeynep@example.com"))
                .or(() -> userRepository.findById(1L))
                .orElse(null);

        if (zeynep == null) {
            zeynep = User.builder()
                    .fullName("Zeynep Duygu Akay")
                    .email("zeynep@example.com")
                    .username("zeynep")
                    .password(passwordEncoder.encode("123456"))
                    .phoneNumber("+90 555 123 45 67")
                    .role(Role.USER)
                    .build();
            userRepository.save(zeynep);
        } else {
            zeynep.setUsername("zeynep");
            zeynep.setEmail("zeynep@example.com");
            zeynep.setPassword(passwordEncoder.encode("123456"));
            if (zeynep.getFullName() == null) zeynep.setFullName("Zeynep Duygu Akay");
            if (zeynep.getPhoneNumber() == null) zeynep.setPhoneNumber("+90 555 123 45 67");
            if (zeynep.getRole() == null || zeynep.getRole() != Role.USER) zeynep.setRole(Role.USER);
            userRepository.save(zeynep);
        }

        // Antalya Kaleiçi Butik Konak (Havuzsuz & Ekonomik Butik Otel) ekle
        boolean hasKaleici = hotelRepository.findAll().stream()
                .anyMatch(h -> h.getName() != null && h.getName().contains("Kaleiçi"));
        if (!hasKaleici) {
            Hotel kaleici = saveHotel("Antalya Kaleiçi Butik Konak", "Antalya", "Kaleiçi Barbaros Sok. No:14",
                    "Tarihi Konak, Bahçe, Restoran, Doğa, Otopark, Wi-Fi",
                    "Antalya tarihi Kaleiçi sokaklarında otantik butik taş konak.", 9.2, 28);
            saveRoom(kaleici, "K-101", RoomType.DOUBLE, 2, new BigDecimal("1400"),
                    "Otantik Ahşap Tavan, Bahçe Manzarası, Wi-Fi, Klima",
                    "https://images.unsplash.com/photo-1582719478250-c89cae4dc85b?auto=format&fit=crop&w=800&q=80", 19);
            saveRoom(kaleici, "K-102", RoomType.SINGLE, 1, new BigDecimal("1100"),
                    "Tarihi Taş Duvar, Çalışma Masası, Wi-Fi",
                    "https://images.unsplash.com/photo-1590490360182-c33d57733427?auto=format&fit=crop&w=800&q=80", 14);
        }

        if (hotelRepository.count() > 1) {
            ensureManagersExist();
            return;
        }

        // 1. Antalya - Grand Azure Resort
        Hotel h1 = saveHotel("Grand Azure Resort", "Antalya", "Lara Caddesi No:10",
                "Havuz, Bahçe, Denize Sıfır, Restoran, Spa, Otopark, Wi-Fi", "Lüks Akdeniz manzaralı resort otel.", 9.3, 24);
        saveRoom(h1, "101", RoomType.DOUBLE, 2, new BigDecimal("2500"), "Havuz Manzarası, Balkon, Wi-Fi, Klima",
                getDefaultImageByRoomType(RoomType.DOUBLE), 18);
        saveRoom(h1, "102", RoomType.SUITE, 4, new BigDecimal("5000"),
                "Özel Jakuzi, Deniz Manzarası, Balkon, Geniş Salon, Wi-Fi", getDefaultImageByRoomType(RoomType.SUITE), 34);
        saveRoom(h1, "103", RoomType.FAMILY, 5, new BigDecimal("4200"), "2 Yatak Odası, Havuz Erişimi, Geniş Balkon",
                getDefaultImageByRoomType(RoomType.FAMILY), 12);

        // 2. Sakarya / Sapanca - Doğa & Göl Bungalovları
        Hotel h2 = saveHotel("Sapanca Doğa Bungalov", "Sakarya", "Sapanca Kırkpınar Mah. No:44",
                "Bungalov, Şömine, Jakuzi, Doğa İçinde, Bahçe, Barbekü, Wi-Fi",
                "Sapanca gölü ve orman manzaralı ahşap kütük evler.", 9.6, 42);
        saveRoom(h2, "B-01", RoomType.BUNGALOW, 2, new BigDecimal("3200"),
                "Şömine, Özel Isıtmalı Havuz, Jakuzi, Doğa Manzaralı, Wi-Fi",
                getDefaultImageByRoomType(RoomType.BUNGALOW), 27);
        saveRoom(h2, "B-02", RoomType.BUNGALOW, 4, new BigDecimal("4800"),
                "Asma Kat, Geniş Bahçe, Şömine, Barbekü Alanı",
                "https://images.unsplash.com/photo-1510798831971-661eb04b3739?auto=format&fit=crop&w=800&q=80", 21);
        saveRoom(h2, "B-03", RoomType.BUNGALOW, 2, new BigDecimal("2800"), "Kuzine Şömine, Veranda, Dağ Manzarası",
                "https://images.unsplash.com/photo-1542314831-068cd1dbfeeb?auto=format&fit=crop&w=800&q=80", 15);

        // 3. Muğla / Fethiye - Sunset Luxury Villas
        Hotel h3 = saveHotel("Fethiye Sunset Luxury Villa", "Muğla", "Ölüdeniz Ovacık Cad. No:88",
                "Villa, Müstakil Havuz, Geniş Bahçe, Deniz Manzaralı, Barbekü, Wi-Fi",
                "Ölüdeniz manzaralı, tam korunaklı lüks müstakil villalar.", 9.8, 35);
        saveRoom(h3, "V-101", RoomType.VILLA, 6, new BigDecimal("6500"),
                "Özel Yüzme Havuzu, 3 Yatak Odası, Geniş Çim Bahçe, Jakuzi, Wi-Fi",
                getDefaultImageByRoomType(RoomType.VILLA), 45);
        saveRoom(h3, "V-102", RoomType.VILLA, 4, new BigDecimal("4900"),
                "Müstakil Sonsuzluk Havuzu, Teras Barbekü, 2 Banyo",
                "https://images.unsplash.com/photo-1600585154340-be6161a56a0c?auto=format&fit=crop&w=800&q=80", 23);

        // 4. Muğla / Bodrum - Bodrum Blue Aegean
        Hotel h4 = saveHotel("Bodrum Blue Aegean Hotel", "Muğla", "Yalıkavak Marina Yolu No:12",
                "Havuz, Denize Sıfır, Restoran, Bar, Otopark, Wi-Fi",
                "Bodrum Yalıkavak merkezinde konforlu butik otel.", 8.7, 19);
        saveRoom(h4, "201", RoomType.SINGLE, 1, new BigDecimal("1800"),
                "Balkon, Deniz Manzarası, Wi-Fi, Çalışma Masası", getDefaultImageByRoomType(RoomType.SINGLE), 14);
        saveRoom(h4, "202", RoomType.DOUBLE, 2, new BigDecimal("2900"), "Havuz Kenarı, Fransız Balkon, Klima",
                getDefaultImageByRoomType(RoomType.DOUBLE), 19);

        // 5. İstanbul - Bosphorus Palace Hotel
        Hotel h5 = saveHotel("Bosphorus Palace Hotel", "İstanbul", "Beşiktaş Sahil Cad. No:45",
                "Boğaz Manzarası, Spa, Kapalı Havuz, Restoran, Tarihi, Wi-Fi",
                "Tarihi yarımada ve Boğaz manzaralı lüks saray konaklaması.", 9.5, 58);
        saveRoom(h5, "301", RoomType.SINGLE, 1, new BigDecimal("2200"),
                "Boğaz Manzarası, Lüks Banyo, Çalışma Alanı, Wi-Fi",
                "https://images.unsplash.com/photo-1590490360182-c33d57733427?auto=format&fit=crop&w=800&q=80", 29);
        saveRoom(h5, "302", RoomType.SUITE, 2, new BigDecimal("7500"),
                "Panoramik Boğaz Manzarası, Mermer Jakuzi, İkram Meyve Sepeti",
                "https://images.unsplash.com/photo-1578683010236-d716f9a3f461?auto=format&fit=crop&w=800&q=80", 52);

        // 6. Kapadokya - Cave Stone House
        Hotel h6 = saveHotel("Kapadokya Cave Stone House", "Nevşehir", "Göreme Aydınlı Mah. No:3",
                "Kaya Oda, Teras, Balon Manzarası, Şömine, Restoran, Wi-Fi",
                "Göreme vadisinde peri bacaları ve balon manzaralı otantik mağara odalar.", 9.7, 50);
        saveRoom(h6, "K-101", RoomType.STONE_HOUSE, 2, new BigDecimal("3500"),
                "Otantik Taş Kemer, Jakuzi, Şömine, Vadi Manzarası", getDefaultImageByRoomType(RoomType.STONE_HOUSE), 31);
        saveRoom(h6, "K-102", RoomType.STONE_HOUSE, 3, new BigDecimal("4100"),
                "Geniş Teras, Peri Bacası Manzarası, Şömine, Wi-Fi",
                "https://images.unsplash.com/photo-1596394516093-501ba68a0ba6?auto=format&fit=crop&w=800&q=80", 28);

        ensureManagersExist();
    }

    private void ensureManagersExist() {
        var hotels = hotelRepository.findAll();
        Long sapancaId = hotels.stream().filter(h -> h.getName().toLowerCase().contains("sapanca")).map(Hotel::getId).findFirst().orElse(null);
        Long antalyaId = hotels.stream().filter(h -> h.getName().toLowerCase().contains("azure") || h.getCity().equalsIgnoreCase("Antalya")).map(Hotel::getId).findFirst().orElse(null);
        Long fethiyeId = hotels.stream().filter(h -> h.getName().toLowerCase().contains("fethiye")).map(Hotel::getId).findFirst().orElse(null);
        Long bodrumId = hotels.stream().filter(h -> h.getName().toLowerCase().contains("bodrum")).map(Hotel::getId).findFirst().orElse(null);
        Long istanbulId = hotels.stream().filter(h -> h.getName().toLowerCase().contains("bosphorus") || h.getCity().equalsIgnoreCase("İstanbul")).map(Hotel::getId).findFirst().orElse(null);

        createManagerIfNotExists("sapanca_manager", "sapanca@hotel.com", "Sapanca Otel Müdürü", Role.HOTEL_MANAGER, sapancaId);
        createManagerIfNotExists("antalya_manager", "antalya@hotel.com", "Antalya Resort Müdürü", Role.HOTEL_MANAGER, antalyaId);
        createManagerIfNotExists("fethiye_manager", "fethiye@hotel.com", "Fethiye Villa Müdürü", Role.HOTEL_MANAGER, fethiyeId);
        createManagerIfNotExists("bodrum_manager", "bodrum@hotel.com", "Bodrum Butik Müdürü", Role.HOTEL_MANAGER, bodrumId);
        createManagerIfNotExists("istanbul_manager", "istanbul@hotel.com", "İstanbul Palace Müdürü", Role.HOTEL_MANAGER, istanbulId);
        createManagerIfNotExists("admin", "admin@bookingai.com", "Genel Sistem Yöneticisi", Role.ADMIN, null);
    }

    private void createManagerIfNotExists(String username, String email, String fullName, Role role, Long hotelId) {
        var existing = userRepository.findByUsername(username);
        if (existing.isEmpty()) {
            User user = User.builder()
                    .username(username)
                    .fullName(fullName)
                    .email(email)
                    .password(passwordEncoder.encode("123456"))
                    .role(role)
                    .hotelId(hotelId)
                    .phoneNumber("+90 555 123 45 67")
                    .build();
            userRepository.save(user);
        } else {
            User user = existing.get();
            user.setPassword(passwordEncoder.encode("123456"));
            user.setRole(role);
            user.setHotelId(hotelId);
            user.setFullName(fullName);
            user.setEmail(email);
            userRepository.save(user);
        }
    }

    private Hotel saveHotel(String name, String city, String address, String features, String description, Double rating, Integer wishlistCount) {
        Hotel h = new Hotel();
        h.setName(name);
        h.setCity(city);
        h.setAddress(address);
        h.setFeatures(features);
        h.setDescription(description);
        h.setRating(rating);
        h.setWishlistCount(wishlistCount != null ? wishlistCount : 0);
        return hotelRepository.save(h);
    }

    private void saveRoom(Hotel hotel, String roomNumber, RoomType roomType, int capacity, BigDecimal price,
            String features, String imageUrl, Integer wishlistCount) {
        Room r = new Room();
        r.setHotel(hotel);
        r.setRoomNumber(roomNumber);
        r.setRoomType(roomType);
        r.setCapacity(capacity);
        r.setPricePerNight(price);
        r.setFeatures(features);
        r.setImageUrl(imageUrl);
        r.setWishlistCount(wishlistCount != null ? wishlistCount : 0);
        roomRepository.save(r);
    }

    private String getDefaultImageByRoomType(RoomType roomType) {
        if (roomType == null) {
            return "https://images.unsplash.com/photo-1566073771259-6a8506099945?auto=format&fit=crop&w=800&q=80";
        }
        switch (roomType) {
            case BUNGALOW:
                return "https://images.unsplash.com/photo-1587061949409-02df41d5e562?auto=format&fit=crop&w=800&q=80";
            case VILLA:
                return "https://images.unsplash.com/photo-1613977257363-707ba9348227?auto=format&fit=crop&w=800&q=80";
            case STONE_HOUSE:
                return "https://images.unsplash.com/photo-1571896349842-33c89424de2d?auto=format&fit=crop&w=800&q=80";
            case SUITE:
                return "https://images.unsplash.com/photo-1582719508461-905c673771fd?auto=format&fit=crop&w=800&q=80";
            case FAMILY:
                return "https://images.unsplash.com/photo-1590490360182-c33d57733427?auto=format&fit=crop&w=800&q=80";
            case SINGLE:
                return "https://images.unsplash.com/photo-1618773928121-c32242e63f39?auto=format&fit=crop&w=800&q=80";
            case DOUBLE:
            default:
                return "https://images.unsplash.com/photo-1566073771259-6a8506099945?auto=format&fit=crop&w=800&q=80";
        }
    }
}