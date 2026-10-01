package com.hotel.booking.service;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.hotel.booking.dto.AutoReservationOrderRequestDto;
import com.hotel.booking.dto.AutoReservationOrderResponseDto;
import com.hotel.booking.dto.ChatRequestDto;
import com.hotel.booking.dto.ChatResponseDto;
import com.hotel.booking.dto.ReservationRequestDto;
import com.hotel.booking.dto.ReservationResponseDto;
import com.hotel.booking.entity.Hotel;
import com.hotel.booking.entity.Reservation;
import com.hotel.booking.entity.Room;
import com.hotel.booking.entity.RoomType;
import com.hotel.booking.repository.HotelRepository;
import com.hotel.booking.repository.ReservationRepository;
import com.hotel.booking.repository.RoomJdbcRepository;
import com.hotel.booking.repository.RoomRepository;
import com.hotel.booking.repository.RoomSpecification;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.stereotype.Service;
import org.springframework.web.client.RestTemplate;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Collectors;

@Service
public class AiAssistantService {

    private final HotelRepository hotelRepository;
    private final RoomRepository roomRepository;
    private final ReservationRepository reservationRepository;
    private final ReservationService reservationService;
    private final AutoReservationService autoReservationService;
    private final RoomJdbcRepository roomJdbcRepository;
    private final JdbcTemplate jdbcTemplate;
    private final RestTemplate restTemplate;
    private final ObjectMapper objectMapper;

    @Value("${groq.api.key}")
    private String apiKey;

    @Value("${groq.api.url}")
    private String apiUrl;

    private final List<String> availableModels = List.of(
            "qwen/qwen3.6-27b",
            "openai/gpt-oss-120b",
            "openai/gpt-oss-20b");

    public AiAssistantService(HotelRepository hotelRepository,
            RoomRepository roomRepository,
            ReservationRepository reservationRepository,
            ReservationService reservationService,
            AutoReservationService autoReservationService,
            RoomJdbcRepository roomJdbcRepository,
            JdbcTemplate jdbcTemplate) {
        this.hotelRepository = hotelRepository;
        this.roomRepository = roomRepository;
        this.reservationRepository = reservationRepository;
        this.reservationService = reservationService;
        this.autoReservationService = autoReservationService;
        this.roomJdbcRepository = roomJdbcRepository;
        this.jdbcTemplate = jdbcTemplate;
        this.restTemplate = new RestTemplate();
        this.objectMapper = new ObjectMapper();
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
            } catch (Exception ignored) {
            }
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

    public ChatResponseDto askAssistant(ChatRequestDto requestDto) {
        String userMessage = requestDto.getMessage();
        List<Map<String, String>> history = requestDto.getHistory() != null ? requestDto.getHistory()
                : new ArrayList<>();

        // 1. SOHBETİ SIFIRLAMA VE ÖNE ÇIKAN OTELLERİ LİSTELEME
        if (isResetOrShowAllIntent(userMessage)) {
            List<Room> allRooms = queryRoomsFromDatabase(null, 0, null, null, null, null, null);

            String reply = "<strong>Sohbet Sıfırlandı</strong><br>" +
                    "Popüler otel ve konaklama seçenekleri listelendi. Her otelin kartı üzerinde ilgili odanın <strong>Müsait</strong> veya <strong>Dolu</strong> durumunu görebilirsiniz.";
            List<String> suggestions = List.of("Müsait Olanlar", "Antalya Otelleri", "Sapanca Bungalov",
                    "Kaş Villaları", "3000 TL Altı");
            return new ChatResponseDto(reply, allRooms, suggestions);
        }

        // 1.5 OTOMATİK REZERVASYON / FİYAT ALARMI TALEBİ
        if (isAutoReserveIntent(userMessage)) {
            if (requestDto.getUserId() == null) {
                String reply = "<strong>Giriş Yapmanız Gerekiyor</strong><br>" +
                        "Fiyat takip alarmı kurabilmek ve adınıza otomatik rezervasyon oluşturabilmek için lütfen hesabınıza giriş yapın.";
                List<String> suggestions = List.of("Giriş Yap", "Antalya Otelleri", "Sapanca Bungalov");
                return new ChatResponseDto(reply, List.of(), suggestions);
            }
            ChatResponseDto directAuto = tryDirectAutoReserve(userMessage, history, requestDto.getUserId());
            if (directAuto != null)
                return directAuto;
        }

        // 2. REZERVASYON YAPMA TALEBİ
        if (isReservationIntent(userMessage)) {
            if (requestDto.getUserId() == null) {
                String reply = "<strong>Giriş Yapmanız Gerekiyor</strong><br>" +
                        "Otel rezervasyonu yapabilmek için lütfen hesabınıza giriş yapın.";
                List<String> suggestions = List.of("Giriş Yap", "Antalya Otelleri", "Sapanca Bungalov");
                return new ChatResponseDto(reply, List.of(), suggestions);
            }
            ChatResponseDto directRes = tryDirectReservation(userMessage, history, requestDto.getUserId());
            if (directRes != null)
                return directRes;
        }

        // 3. İPTAL ETME TALEBİ
        if (isCancelIntent(userMessage)) {
            if (requestDto.getUserId() == null) {
                String reply = "<strong>Giriş Yapmanız Gerekiyor</strong><br>" +
                        "Rezervasyonlarınızı görüntülemek ve iptal edebilmek için lütfen hesabınıza giriş yapın.";
                List<String> suggestions = List.of("Giriş Yap", "Antalya Otelleri");
                return new ChatResponseDto(reply, List.of(), suggestions);
            }
            ChatResponseDto directCancel = tryDirectCancel(userMessage, requestDto.getUserId());
            if (directCancel != null)
                return directCancel;
        }

        // 3. KULLANICININ QUERY'SİNE GÖRE DİNAMİK SQL VE LLM ANALİZİ
        String systemPrompt = buildSystemPrompt();

        for (String modelName : availableModels) {
            try {
                String aiRawResponse = callGroqApi(modelName, systemPrompt, history, userMessage);
                if (aiRawResponse != null && !aiRawResponse.isBlank()) {
                    ChatResponseDto dto = parseAiResponse(aiRawResponse, userMessage, history);
                    if (dto != null) {
                        return dto;
                    }
                }
            } catch (Exception e) {
                System.err.println("Model " + modelName + " hatasi: " + e.getMessage());
            }
        }

        // 4. QUERY BAZLI DOĞRUDAN SQL SORGUSU (Fallback / Kural Motoru)
        return executeRobustFallback(history, userMessage);
    }

    private boolean isResetOrShowAllIntent(String msg) {
        String clean = normalizeInput(msg);
        return clean.contains("sifirla") || clean.contains("temizle") ||
                clean.contains("bastan basla") || clean.contains("tum otel") ||
                clean.contains("butun otel") || clean.contains("hepsini listele") ||
                clean.contains("tum odalar") || clean.contains("bosluk") ||
                clean.contains("doluluk") || clean.contains("musaitlik");
    }

    private boolean isCancelIntent(String msg) {
        String clean = normalizeInput(msg);

        // "iptal etme", "iptal istemiyorum", "vazgecme" gibi olumsuzluklar iptal
        // DEĞİLDİR
        if (clean.contains("iptal etme") || clean.contains("iptal istemiyorum") || clean.contains("vazgecme")
                || clean.contains("silme")) {
            return false;
        }

        // Açıkça iptal etme ifadeleri
        if (clean.contains("rezervasyon iptal") || clean.contains("rezervasyonu iptal") ||
                clean.contains("rezervasyonumu iptal") || clean.contains("rezervasyonlarimi iptal") ||
                clean.contains("iptal et") || clean.contains("iptal edilsin") || clean.contains("iptal etmek") ||
                clean.contains("vazgectim") || clean.contains("vazgeciyorum")) {
            return true;
        }

        // Rezervasyon yapma/ayırma ifadeleri varsa iptal değildir
        if (clean.contains("rezerve") || clean.contains("rezervasyon") || clean.contains("ayirt") ||
                clean.contains("tut") || clean.contains("oda ayir") || clean.contains("yer ayir") ||
                clean.contains("ayir") || clean.contains("yapabilirsin") || clean.contains("alabilirsin") ||
                clean.contains("bana ayir")) {
            return false;
        }

        return clean.matches(".*\\b(iptal|vazgec|silinsin|silmek)\\b.*");
    }

    private boolean isAutoReserveIntent(String msg) {
        if (msg == null)
            return false;
        String clean = normalizeInput(msg);

        // İptal cümleleri otomatik rezervasyon veya alarm değildir
        if (clean.contains("iptal et") || clean.contains("iptal edilsin") || clean.contains("vazgec")) {
            return false;
        }

        // 1. Doğrudan Fiyat Alarmı & Takip Kelimeleri
        if (clean.contains("fiyat alarmi") || clean.contains("fiyat takip") || clean.contains("fiyati takip")
                || clean.contains("fiyat bildirimi") || clean.contains("alarm kur") || clean.contains("takibe al")
                || clean.contains("fiyat takibine al") || clean.contains("takip et") || clean.contains("takipte kal")) {
            return true;
        }

        // 2. Fiyat Düşme / İndirim Şartı ile Bildirim / Otomatik Rezervasyon (Örn: "2000'e düşerse haber ver", "düşerse rezerve et")
        boolean hasTriggerCondition = clean.contains("duser") || clean.contains("dusse") || clean.contains("duserse")
                || clean.contains("dusuruldugunde") || clean.contains("dusunce") || clean.contains("inince")
                || clean.contains("inerse") || clean.contains("indiginde") || clean.contains("olunca")
                || clean.contains("olursa") || clean.contains("ucuzlarsa") || clean.contains("ucuzlayinca")
                || clean.contains("haber ver") || clean.contains("haber et") || clean.contains("bildir")
                || clean.contains("bilgilendir") || clean.contains("haberdar") || clean.contains("alarm")
                || clean.contains("takip") || clean.contains("otomatik");

        boolean hasAction = clean.contains("otomatik") || clean.contains("rezerve") || clean.contains("rezervasyon") ||
                clean.contains("tut") || clean.contains("ayir") || clean.contains("ayirt") || clean.contains("al") ||
                clean.contains("haber ver") || clean.contains("haber et") || clean.contains("bildir")
                || clean.contains("bilgilendir") ||
                clean.contains("bilgi ver") || clean.contains("haberdar") || clean.contains("soyle") ||
                clean.contains("sms") || clean.contains("mesaj at") || clean.contains("mesaj gonder")
                || clean.contains("uyar") ||
                clean.contains("alarm") || clean.contains("takip") || clean.contains("alirim")
                || clean.contains("satin al");

        return hasTriggerCondition && (hasAction || clean.contains("alarm") || clean.contains("takip"));
    }

    private boolean isItineraryIntent(String msg) {
        if (msg == null)
            return false;
        String clean = normalizeInput(msg);
        if (clean.contains("iptal") || clean.contains("fiyat alarmi") || clean.contains("otomatik rezerve")) {
            return false;
        }
        return clean.contains("tatil plan") || clean.contains("gezi plan") || clean.contains("gezi rota") ||
                clean.contains("tatil rota") || clean.contains("seyahat plan") || clean.contains("gunluk plan") ||
                clean.contains("gun gun") || clean.contains("itinerary") || clean.contains("gezi rehber") ||
                clean.contains("tatil rehber") || clean.contains("nereleri gezmeli")
                || clean.contains("nereleri gezebilirim") ||
                clean.contains("ne yapmali") || clean.contains("neler yapabiliriz") || clean.contains("gezi onerisi") ||
                clean.contains("tatil onerisi") || clean.contains("tatil program") || clean.contains("gezi program") ||
                clean.contains("rota hazirla") || clean.contains("rota cikar") || clean.contains("rota oner") ||
                clean.contains("gezi rotasi") || clean.contains("tatil rotasi") ||
                (clean.contains("plan") && (clean.contains("gun") || clean.contains("haftasonu")
                        || clean.contains("tatil") || clean.contains("gezi") || clean.contains("rota")
                        || clean.contains("esimle") || clean.contains("arkadas")))
                ||
                (clean.contains("rota") && (clean.contains("hazirla") || clean.contains("cikar")
                        || clean.contains("oner") || clean.contains("yap") || clean.contains("iste")));
    }

    private boolean isReservationIntent(String msg) {
        if (isAutoReserveIntent(msg) || isItineraryIntent(msg)) {
            return false;
        }
        String clean = normalizeInput(msg);
        if (clean.contains("rezervasyon iptal") || clean.contains("rezervasyonu iptal")
                || clean.contains("rezervasyonumu iptal") || clean.contains("iptal et")) {
            if (!clean.contains("iptal etme")) {
                return false;
            }
        }
        return clean.contains("rezerve") || clean.contains("rezervasyon") ||
                clean.contains("ayirt") || clean.contains("tut") ||
                clean.contains("oda ayir") || clean.contains("yer ayir") ||
                clean.contains("ayir") || clean.contains("yapabilirsin") ||
                clean.contains("alabilirsin") || clean.contains("bana ayir");
    }

    private boolean isCheaperIntent(String msg) {
        if (msg == null)
            return false;
        String clean = normalizeInput(msg);
        return clean.contains("ucuz") || clean.contains("pahali") || clean.contains("cok pahali")
                || clean.contains("daha uygun") || clean.contains("butce dostu") || clean.contains("butceye uygun")
                || clean.contains("daha ekonomik") || clean.contains("fiyati dusur") || clean.contains("fiyat dusur")
                || clean.contains("hesapli") || clean.contains("daha hesapli")
                || clean.contains("fiyati yuksek") || clean.contains("fiyat yuksek") || clean.contains("baska ucuz");
    }

    private String normalizeInput(String str) {
        if (str == null)
            return "";
        String s = str.toLowerCase(new Locale("tr", "TR"))
                .replace("ı", "i")
                .replace("ğ", "g")
                .replace("ü", "u")
                .replace("ş", "s")
                .replace("ö", "o")
                .replace("ç", "c")
                .replace("wi-fi", "wifi");

        // Fuzzy typo matching for common keywords
        s = s.replaceAll("\\bantlya\\b|\\bantlay\\b|\\bantala\\b", "antalya")
                .replaceAll("\\bsapanc\\b|\\bsapanca\\b|\\bsakary\\b", "sakarya")
                .replaceAll("\\bbodrm\\b|\\bbodrumm\\b|\\bbodruma\\b", "bodrum")
                .replaceAll("\\bistnbul\\b|\\bistanbl\\b|\\bistabul\\b", "istanbul")
                .replaceAll("\\bkapadoky\\b|\\bkapdokya\\b|\\bgoreme\\b", "kapadokya")
                .replaceAll("\\babnt\\b|\\babanta\\b", "abant")
                .replaceAll("\\bfethye\\b|\\bfethiy\\b|\\boludeniz\\b", "fethiye")
                .replaceAll("\\bkass\\b|\\bkasa\\b", "kas")
                .replaceAll("\\bbngalov\\b|\\bbungalv\\b|\\bbungalo\\b|\\bbanglov\\b", "bungalov")
                .replaceAll("\\bvilal\\b|\\bvill\\b|\\bvila\\b", "villa")
                .replaceAll("\\bjakzi\\b|\\bjakuzi\\b|\\bjakuzili\\b|\\bjakzili\\b", "jakuzi")
                .replaceAll("\\bsomine\\b|\\bsomineli\\b", "somine")
                .replaceAll("\\bhavz\\b|\\bhavuzl\\b|\\bhavuzlu\\b", "havuz");

        return s;
    }

    private Room findRoomDirectlyByNumber(String roomNumber, String hotelName) {
        if (roomNumber == null || roomNumber.isBlank())
            return null;
        String sql = "SELECT r.id AS room_id, r.room_number, r.room_type, r.price_per_night, r.capacity, " +
                "r.features AS room_features, r.image_url, COALESCE(r.wishlist_count, 0) AS room_wishlist_count, " +
                "h.id AS hotel_id, h.name AS hotel_name, h.city, h.address, h.description, " +
                "h.features AS hotel_features, h.rating, COALESCE(h.wishlist_count, 0) AS wishlist_count, 1 AS is_available " +
                "FROM rooms r JOIN hotels h ON r.hotel_id = h.id " +
                "WHERE (REPLACE(LOWER(r.room_number), '-', '') = ? OR LOWER(r.room_number) = ?) ";
        List<Object> params = new ArrayList<>(
                List.of(roomNumber.toLowerCase().replace("-", ""), roomNumber.toLowerCase()));
        if (hotelName != null && !hotelName.isBlank()) {
            sql += "AND LOWER(h.name) LIKE ? ";
            params.add("%" + normalizeInput(hotelName) + "%");
        }
        sql += "LIMIT 1";
        List<Room> res = jdbcTemplate.query(sql, roomRowMapper, params.toArray());
        return res.isEmpty() ? null : res.get(0);
    }

    private String extractRoomNumber(String text) {
        if (text == null)
            return null;
        String clean = text.toLowerCase();
        Pattern p = Pattern.compile("\\b([a-z]{1,2}-?\\d{1,3}|[1-9]\\d{2,3})\\s*(?:nolu|numarali|no|oda)?\\b");
        Matcher m = p.matcher(clean);
        while (m.find()) {
            String val = m.group(1).trim();
            if (!val.matches("202[4-7]")
                    && (!val.matches("[1-9]000") || clean.contains("oda") || clean.contains("nolu"))) {
                return val;
            }
        }
        return null;
    }

    private ChatResponseDto tryDirectAutoReserve(String userMessage, List<Map<String, String>> history, Long userId) {
        String currentMsgNorm = normalizeInput(userMessage);

        String city = extractCity(currentMsgNorm, history);
        String hotelName = extractHotelName(currentMsgNorm);
        String roomType = extractRoomType(currentMsgNorm, history);
        int capacity = extractCapacity(currentMsgNorm, history);
        String phone = extractPhoneNumber(userMessage);

        // 1. Doğrudan JDBC ile Oda Numarası Arama (O(1) Hızlı SQL Sorgusu)
        String rNum = extractRoomNumber(userMessage);
        Room targetRoom = null;
        if (rNum != null) {
            targetRoom = findRoomDirectlyByNumber(rNum, hotelName);
        }

        // 2. Şehir, Otel Adı veya Oda Türüne Göre Eşleştirme (JDBC)
        if (targetRoom == null) {
            List<Room> matched = queryRoomsFromDatabase(city, capacity, null, hotelName, null, null, roomType);
            if (matched.isEmpty()) {
                matched = queryRoomsFromDatabase(city, 0, null, hotelName, null, null, roomType);
            }
            if (!matched.isEmpty()) {
                targetRoom = matched.get(0);
            }
        }

        // 3. Kullanıcı "bu otel odası", "bu oda" dediğinde geçmiş sohbetten oda bulma
        if (targetRoom == null && history != null) {
            for (int i = history.size() - 1; i >= 0; i--) {
                String prevContent = history.get(i).get("content");
                if (prevContent != null) {
                    String prevNorm = normalizeInput(prevContent);
                    String prevCity = extractCity(prevNorm);
                    String prevHotel = extractHotelName(prevNorm);
                    String prevType = extractRoomType(prevNorm, null);
                    List<Room> prevMatched = queryRoomsFromDatabase(prevCity, 0, null, prevHotel, null, null,
                            prevType);
                    if (!prevMatched.isEmpty()) {
                        targetRoom = prevMatched.get(0);
                        break;
                    }
                }
            }
        }

        if (targetRoom == null) {
            List<Room> fallback = queryRoomsFromDatabase(null, 0, null, null, null, null, null);
            if (!fallback.isEmpty()) {
                targetRoom = fallback.get(0);
            }
        }

        if (targetRoom != null) {
            Double targetPrice = extractTargetPrice(userMessage, targetRoom, history);

            LocalDate checkIn = extractDate(currentMsgNorm, true);
            LocalDate checkOut = extractDate(currentMsgNorm, false);
            boolean isAutoBook = isAutoBookRequested(userMessage);

            try {
                AutoReservationOrderRequestDto req = AutoReservationOrderRequestDto.builder()
                        .userId(userId)
                        .roomId(targetRoom.getId())
                        .targetPrice(BigDecimal.valueOf(targetPrice))
                        .phoneNumber(phone != null ? phone : "+90 555 123 45 67")
                        .checkInDate(checkIn)
                        .checkOutDate(checkOut)
                        .autoBook(isAutoBook)
                        .build();

                AutoReservationOrderResponseDto order = autoReservationService.createOrder(req);
                return buildAutoReserveSuccessResponse(order);
            } catch (Exception e) {
                return new ChatResponseDto("Fiyat alarmı oluşturulurken hata oluştu: " + e.getMessage());
            }
        }
        return null;
    }

    private Double extractTargetPrice(String userMessage, Room room, List<Map<String, String>> history) {
        if (userMessage == null) {
            return (room != null && room.getPricePerNight() != null)
                    ? Math.max(500.0, room.getPricePerNight().doubleValue() - 400.0)
                    : 2500.0;
        }

        String clean = normalizeInput(userMessage);

        // 1. Yüzdelik İndirim Kontrolü (örn: "%20 indirim", "yüzde 15 ucuzlarsa", "%10
        // düşerse")
        Pattern pPercent = Pattern.compile("(?:yuzde|%)\\s*(\\d{1,2})|(\\d{1,2})\\s*%");
        Matcher mPercent = pPercent.matcher(clean);
        if (mPercent.find()) {
            try {
                String pStr = mPercent.group(1) != null ? mPercent.group(1) : mPercent.group(2);
                int percent = Integer.parseInt(pStr);
                if (percent > 0 && percent < 90 && room != null && room.getPricePerNight() != null) {
                    double discounted = room.getPricePerNight().doubleValue() * (1.0 - (percent / 100.0));
                    return Math.round(discounted / 50.0) * 50.0;
                }
            } catch (Exception ignored) {
            }
        }

        // 2. Belirli Miktarda Ucuzlama (örn: "500 tl ucuzlarsa", "1000 lira düşerse",
        // "300 tl inerse")
        Pattern pRelativeDrop = Pattern
                .compile("(\\d{2,4})\\s*(?:tl|lira)?\\s*(?:ucuzlarsa|duserse|inerse|dusse|inse|kirilirsa)");
        Matcher mDrop = pRelativeDrop.matcher(clean);
        if (mDrop.find()) {
            try {
                double dropAmount = Double.parseDouble(mDrop.group(1));
                if (dropAmount >= 100 && room != null && room.getPricePerNight() != null) {
                    double result = room.getPricePerNight().doubleValue() - dropAmount;
                    if (result >= 500)
                        return result;
                }
            } catch (Exception ignored) {
            }
        }

        // 3. X Gece / X Gecelik Fiyat Belirtilmiş mi? (örn: "3 gecelik fiyatı 12000 in
        // altına düşerse", "3 gece 12000 olursa", "4 günlük 8000 tl")
        Pattern pNightsPrice = Pattern.compile(
                "(\\d{1,2})\\s*(?:gecelik|gece|gunluk|gun)\\s*(?:fiyati|fiyat|ucreti|icin|butcem|toplam)?\\s*(?:'nin|'nın|nin|nın|in|ın|e|a|ye|ya)?\\s*:?\\s*([1-9]\\d{2,5})");
        Matcher mNightsPrice = pNightsPrice.matcher(clean);
        if (mNightsPrice.find()) {
            try {
                int n = Integer.parseInt(mNightsPrice.group(1));
                double total = Double.parseDouble(mNightsPrice.group(2));
                if (n > 0 && total >= 500) {
                    double perNight = total / n;
                    return Math.round(perNight / 50.0) * 50.0;
                }
            } catch (Exception ignored) {
            }
        }

        // 4. Toplam Bütçe / Toplam Tutar (örn: "toplam 6000 tl altına düşerse", "5 gece
        // için bütçem 10000")
        Pattern pTotalBudget = Pattern.compile("(?:toplam|toplamda|butcem|butce)\\s*:?\\s*([1-9]\\d{3,5})");
        Matcher mBudget = pTotalBudget.matcher(clean);
        if (mBudget.find()) {
            try {
                double totalBudget = Double.parseDouble(mBudget.group(1));
                LocalDate[] dates = extractDateRange(userMessage);
                long nights = java.time.temporal.ChronoUnit.DAYS.between(dates[0], dates[1]);
                if (nights <= 0)
                    nights = 2;
                double perNight = totalBudget / nights;
                if (perNight >= 500) {
                    return Math.round(perNight / 50.0) * 50.0;
                }
            } catch (Exception ignored) {
            }
        }

        // 5. "2 bin", "3 bin", "2.5k", "3k" gibi kısaltmalar
        Pattern pKilo = Pattern.compile("(\\d+(?:[.,]\\d+)?)\\s*(?:bin|k)\\b");
        Matcher mKilo = pKilo.matcher(clean);
        if (mKilo.find()) {
            try {
                String numStr = mKilo.group(1).replace(",", ".");
                double num = Double.parseDouble(numStr);
                double val = num * 1000.0;
                if (val >= 500)
                    return val;
            } catch (Exception ignored) {
            }
        }

        // 6. Doğrudan Fiyat Belirtilmiş mi? (findPriceInText)
        Double directPrice = extractPriceDouble("", userMessage, history);
        if (directPrice != null && directPrice >= 500) {
            LocalDate[] dates = extractDateRange(userMessage);
            long nights = java.time.temporal.ChronoUnit.DAYS.between(dates[0], dates[1]);
            // Eğer doğrudan verilen fiyat oda gecelik fiyatının 1.6 katından yüksekse ve
            // gece sayısı > 1 ise bu toplam konaklama fiyatıdır
            if (nights > 1 && room != null && room.getPricePerNight() != null
                    && directPrice > room.getPricePerNight().doubleValue() * 1.5) {
                double perNight = directPrice / nights;
                return Math.round(perNight / 50.0) * 50.0;
            }
            return directPrice;
        }

        // 7. Sayı belirtilmemiş ancak indirim/ucuzlama/alarm istenmiş ("ucuzlarsa haber
        // ver", "fiyatı düşerse", "indirimde bildir", "fiyat alarmı kur", "takibe al")
        if (room != null && room.getPricePerNight() != null) {
            double current = room.getPricePerNight().doubleValue();
            // Varsayılan akıllı hedef: Mevcut fiyattan %15 indirim
            double target = Math.round((current * 0.85) / 50.0) * 50.0;
            return Math.max(500.0, target);
        }

        return 2500.0;
    }

    private boolean isAutoBookRequested(String text) {
        if (text == null)
            return false;
        String clean = normalizeInput(text);

        // "bana rezervasyon yap", "rezervasyon yap", "otomatik rezerve et", "rezerve
        // et" kontrolü
        boolean hasBookKeyword = clean.contains("rezervasyon yap") || clean.contains("rezerve et") ||
                clean.contains("rezervasyon olustur") || clean.contains("rezervasyonumu yap") ||
                clean.contains("otomatik rezerve") || clean.contains("otomatik rezervasyon") ||
                clean.contains("otomatik ayirt") || clean.contains("otomatik tut") ||
                clean.contains("direkt rezerve") || clean.contains("hemen rezerve") ||
                clean.contains("benim yerime") || clean.contains("benim adima") ||
                clean.contains("bana ayir") || clean.contains("yerimi ayir");

        // Yalnızca "haber ver / bildir / alarm kur" varsa ve yukarıdaki rezerve
        // kelimeleri yoksa false
        if (!hasBookKeyword) {
            return false;
        }

        if (clean.contains("sadece haber ver") || clean.contains("sadece bildir")
                || clean.contains("rezervasyon yapma")) {
            return false;
        }

        return true;
    }

    private ChatResponseDto buildAutoReserveSuccessResponse(AutoReservationOrderResponseDto order) {
        List<String> suggestions = List.of(
                "Rezervasyonlarım",
                "Bildirimleri Gör",
                "Sapanca Bungalov",
                "Kaş Villaları");

        boolean isAuto = Boolean.TRUE.equals(order.getAutoBook());

        long nights = 1;
        if (order.getCheckInDate() != null && order.getCheckOutDate() != null) {
            nights = java.time.temporal.ChronoUnit.DAYS.between(order.getCheckInDate(), order.getCheckOutDate());
            if (nights <= 0)
                nights = 1;
        }

        String targetPriceStr;
        if (nights > 1 && order.getTargetPrice() != null) {
            BigDecimal totalTarget = order.getTargetPrice().multiply(BigDecimal.valueOf(nights));
            BigDecimal totalCurrent = order.getCurrentPrice() != null
                    ? order.getCurrentPrice().multiply(BigDecimal.valueOf(nights))
                    : null;
            targetPriceStr = String.format("%s ₺ / Gece ve altı (%d Gece Toplam: %s ₺ | Mevcut Gecelik: %s ₺)",
                    order.getTargetPrice(), nights, totalTarget,
                    order.getCurrentPrice() != null ? order.getCurrentPrice() : order.getTargetPrice());
        } else {
            targetPriceStr = String.format("%s ₺ ve altı (Mevcut Fiyat: %s ₺)",
                    order.getTargetPrice(),
                    order.getCurrentPrice() != null ? order.getCurrentPrice() : order.getTargetPrice());
        }

        String reply;
        if (isAuto) {
            reply = String.format(
                    "<strong>Otomatik Rezervasyon Talimatı Oluşturuldu</strong><br><br>" +
                            "<strong>Otel / Oda:</strong> %s (Oda %s - %s)<br>" +
                            "<strong>Hedef Fiyat:</strong> %s<br>" +
                            "<strong>Tarih:</strong> %s &rarr; %s (%d Gece)<br>" +
                            "<strong>Bildirim Numarası:</strong> %s<br><br>" +
                            "<em>Otel yöneticisi oda fiyatını %s ₺ veya altına düşürdüğü anda sistem adınıza rezervasyonu otomatik olarak yapacak ve telefonunuza anında bildirim gönderecektir.</em>",
                    order.getHotelName(), order.getRoomNumber(), formatRoomTypeTurkish(order.getRoomType()),
                    targetPriceStr,
                    order.getCheckInDate(), order.getCheckOutDate(), nights,
                    order.getPhoneNumber(),
                    order.getTargetPrice());
        } else {
            reply = String.format(
                    "<strong>Fiyat Takip Alarmı Başarıyla Kuruldu</strong><br><br>" +
                            "<strong>Otel / Oda:</strong> %s (Oda %s - %s)<br>" +
                            "<strong>Hedef Fiyat:</strong> %s<br>" +
                            "<strong>Tarih:</strong> %s &rarr; %s (%d Gece)<br>" +
                            "<strong>Bildirim Numarası:</strong> %s<br><br>" +
                            "<em>Otel yöneticisi oda fiyatını %s ₺ veya altına düşürdüğü anda telefonunuza ve bildirim merkezinize anında bildirim iletilecektir. (Adınıza otomatik rezervasyon yapılmayacaktır)</em>",
                    order.getHotelName(), order.getRoomNumber(), formatRoomTypeTurkish(order.getRoomType()),
                    targetPriceStr,
                    order.getCheckInDate(), order.getCheckOutDate(), nights,
                    order.getPhoneNumber(),
                    order.getTargetPrice());
        }

        return new ChatResponseDto(reply, List.of(), suggestions);
    }

    private String extractPhoneNumber(String text) {
        if (text == null)
            return null;
        Pattern p = Pattern.compile("(?:\\+?90|0)?\\s*(5\\d{2})[\\s.-]*(\\d{3})[\\s.-]*(\\d{2})[\\s.-]*(\\d{2})");
        Matcher m = p.matcher(text);
        if (m.find()) {
            return "+90 " + m.group(1) + " " + m.group(2) + " " + m.group(3) + " " + m.group(4);
        }
        return null;
    }

    private ChatResponseDto tryDirectReservation(String userMessage, List<Map<String, String>> history, Long userId) {
        String currentMsgNorm = normalizeInput(userMessage);

        String city = extractCity(currentMsgNorm, history);
        Double maxPrice = extractPriceDouble("", userMessage, history);
        String hotelName = extractHotelName(currentMsgNorm);
        String roomType = extractRoomType(currentMsgNorm, history);
        int capacity = extractCapacity(currentMsgNorm, history);

        // 1 kişi 2 kişilik veya daha geniş odada da rezervasyon yapabilir
        List<Room> matched = queryRoomsFromDatabase(city, capacity, maxPrice, hotelName, null, null, roomType);
        if (matched.isEmpty()) {
            matched = queryRoomsFromDatabase(city, 0, maxPrice, hotelName, null, null, roomType);
        }

        if (!matched.isEmpty()) {
            Room targetRoom = matched.get(0);
            LocalDate checkIn = extractDate(currentMsgNorm, true);
            LocalDate checkOut = extractDate(currentMsgNorm, false);

            try {
                ReservationRequestDto req = new ReservationRequestDto();
                req.setUserId(userId);
                req.setRoomId(targetRoom.getId());
                req.setCheckInDate(checkIn);
                req.setCheckOutDate(checkOut);

                ReservationResponseDto res = reservationService.createReservation(req);
                List<String> suggestions = List.of("Rezervasyonlarım", "Başka Otel Ara", "Kaş Villaları");
                return new ChatResponseDto(String.format("<strong>Rezervasyonunuz Başarıyla Oluşturuldu!</strong><br>" +
                        "<strong>Otel:</strong> %s (Oda %s)<br>" +
                        "<strong>Rezervasyon No:</strong> #%d<br>" +
                        "<strong>Tarih:</strong> %s &rarr; %s<br>" +
                        "<strong>Toplam Tutar:</strong> %s ₺",
                        targetRoom.getHotel().getName(), targetRoom.getRoomNumber(), res.getId(), res.getCheckInDate(),
                        res.getCheckOutDate(), res.getTotalPrice()),
                        List.of(), suggestions);
            } catch (Exception e) {
                return new ChatResponseDto("Rezervasyon sırasında hata oluştu: " + e.getMessage());
            }
        }
        return null;
    }

    private ChatResponseDto tryDirectCancel(String userMessage, Long userId) {
        Pattern p = Pattern.compile("(?:#|no:?|numara:?|id:?|rezervasyon\\s*)(\\d{1,6})", Pattern.CASE_INSENSITIVE);
        Matcher m = p.matcher(userMessage);
        Long resId = null;
        if (m.find()) {
            try {
                resId = Long.parseLong(m.group(1));
            } catch (Exception ignored) {
            }
        }

        if (resId == null && !userMessage.toLowerCase().contains("kisi")
                && !userMessage.toLowerCase().contains("kişi")) {
            Pattern p2 = Pattern.compile("#?(\\d{1,6})");
            Matcher m2 = p2.matcher(userMessage);
            if (m2.find()) {
                try {
                    resId = Long.parseLong(m2.group(1));
                } catch (Exception ignored) {
                }
            }
        }

        if (resId == null) {
            List<Reservation> userReservations = reservationRepository.findByUserIdOrderByIdDesc(userId);
            if (!userReservations.isEmpty()) {
                resId = userReservations.get(0).getId();
            }
        }

        if (resId == null) {
            return new ChatResponseDto("İptal edilecek aktif bir rezervasyonunuz bulunamadı.");
        }

        try {
            reservationService.cancelReservation(resId);
            List<String> suggestions = List.of("Aktif Rezervasyonlarım", "Yeni Otel Ara", "Sapanca Bungalov");
            return new ChatResponseDto(
                    String.format("<strong>#%d</strong> numaralı rezervasyonunuz başarıyla iptal edildi.", resId), null,
                    suggestions);
        } catch (Exception e) {
            return new ChatResponseDto("Rezervasyon iptal edilirken bir hata oluştu: " + e.getMessage());
        }
    }

    private int getMonthNumber(String monthName) {
        if (monthName == null)
            return 9;
        String m = normalizeInput(monthName);
        if (m.contains("ocak"))
            return 1;
        if (m.contains("subat") || m.contains("şubat"))
            return 2;
        if (m.contains("mart"))
            return 3;
        if (m.contains("nisan"))
            return 4;
        if (m.contains("mayis") || m.contains("mayıs"))
            return 5;
        if (m.contains("haziran"))
            return 6;
        if (m.contains("temmuz"))
            return 7;
        if (m.contains("agustos") || m.contains("ağustos"))
            return 8;
        if (m.contains("eylul") || m.contains("eylül"))
            return 9;
        if (m.contains("ekim"))
            return 10;
        if (m.contains("kasim") || m.contains("kasım"))
            return 11;
        if (m.contains("aralik") || m.contains("aralık"))
            return 12;
        return 9;
    }

    private LocalDate[] extractDateRange(String text) {
        if (text == null) {
            return new LocalDate[] { LocalDate.of(2026, 9, 10), LocalDate.of(2026, 9, 12) };
        }
        String clean = normalizeInput(text);

        // 1. "10-15 eylül" veya "10 ile 15 eylül" veya "10 eylül - 15 eylül"
        Pattern pRange = Pattern.compile(
                "(\\d{1,2})\\s*(?:-|ile|ve|ila)?\\s*(\\d{1,2})\\s*(ocak|subat|mart|nisan|mayis|haziran|temmuz|agustos|eylul|ekim|kasim|aralik)");
        Matcher mRange = pRange.matcher(clean);
        if (mRange.find()) {
            try {
                int d1 = Integer.parseInt(mRange.group(1));
                int d2 = Integer.parseInt(mRange.group(2));
                int month = getMonthNumber(mRange.group(3));
                if (d1 > 0 && d1 <= 31 && d2 > 0 && d2 <= 31) {
                    if (d2 <= d1)
                        d2 = d1 + 2;
                    return new LocalDate[] { LocalDate.of(2026, month, d1), LocalDate.of(2026, month, d2) };
                }
            } catch (Exception ignored) {
            }
        }

        // 2. "15 eylül 3 gece" / "10 eylül 4 gün"
        Pattern pStartNight = Pattern.compile(
                "(\\d{1,2})\\s*(ocak|subat|mart|nisan|mayis|haziran|temmuz|agustos|eylul|ekim|kasim|aralik)\\s*(?:tarihinde|tarihinden|itibaren)?\\s*(\\d{1,2})\\s*(?:gece|gun|gün)");
        Matcher mStartNight = pStartNight.matcher(clean);
        if (mStartNight.find()) {
            try {
                int d1 = Integer.parseInt(mStartNight.group(1));
                int month = getMonthNumber(mStartNight.group(2));
                int nights = Integer.parseInt(mStartNight.group(3));
                if (nights <= 0)
                    nights = 2;
                LocalDate in = LocalDate.of(2026, month, d1);
                return new LocalDate[] { in, in.plusDays(nights) };
            } catch (Exception ignored) {
            }
        }

        // 3. "X gece" (örn: "3 gece", "5 gece", "1 gece")
        Pattern pNightsOnly = Pattern.compile("(\\d{1,2})\\s*(?:gece|gun|gün)");
        Matcher mNightsOnly = pNightsOnly.matcher(clean);
        if (mNightsOnly.find()) {
            try {
                int nights = Integer.parseInt(mNightsOnly.group(1));
                if (nights > 0 && nights <= 30) {
                    LocalDate in = LocalDate.of(2026, 9, 10);
                    return new LocalDate[] { in, in.plusDays(nights) };
                }
            } catch (Exception ignored) {
            }
        }

        // 4. "10.09.2026 - 15.09.2026"
        Pattern pFullDate = Pattern.compile("(\\d{4}-\\d{2}-\\d{2})|(\\d{2}\\.\\d{2}\\.\\d{4})");
        Matcher mFull = pFullDate.matcher(clean);
        List<LocalDate> foundDates = new ArrayList<>();
        while (mFull.find()) {
            try {
                String match = mFull.group();
                if (match.contains(".")) {
                    String[] parts = match.split("\\.");
                    foundDates.add(LocalDate.of(Integer.parseInt(parts[2]), Integer.parseInt(parts[1]),
                            Integer.parseInt(parts[0])));
                } else if (match.contains("-")) {
                    foundDates.add(LocalDate.parse(match));
                }
            } catch (Exception ignored) {
            }
        }
        if (foundDates.size() >= 2) {
            LocalDate d1 = foundDates.get(0);
            LocalDate d2 = foundDates.get(1);
            if (d2.isAfter(d1)) {
                return new LocalDate[] { d1, d2 };
            }
        }

        return new LocalDate[] { LocalDate.of(2026, 9, 10), LocalDate.of(2026, 9, 12) };
    }

    private LocalDate extractDate(String text, boolean isCheckIn) {
        LocalDate[] range = extractDateRange(text);
        return isCheckIn ? range[0] : range[1];
    }

    private String extractCity(String text) {
        if (text == null)
            return null;
        String clean = normalizeInput(text);
        if (clean.contains("antalya") || clean.contains("kas") || clean.contains("kaş"))
            return "Antalya";
        if (clean.contains("sakarya") || clean.contains("sapanc") || clean.contains("sapbca")
                || clean.contains("sapanca"))
            return "Sakarya";
        if (clean.contains("mugla") || clean.contains("muğla") || clean.contains("fethiye") || clean.contains("bodrum"))
            return "Muğla";
        if (clean.contains("istanb") || clean.contains("isnabul"))
            return "İstanbul";
        if (clean.contains("nevsehir") || clean.contains("kapadokya") || clean.contains("goreme")
                || clean.contains("göreme"))
            return "Nevşehir";
        if (clean.contains("bolu") || clean.contains("abant"))
            return "Bolu";
        return null;
    }

    private String extractCity(String text, List<Map<String, String>> history) {
        String found = extractCity(text);
        if (found != null)
            return found;
        if (history != null) {
            for (int i = history.size() - 1; i >= 0; i--) {
                if ("user".equals(history.get(i).get("role"))) {
                    String prevFound = extractCity(history.get(i).get("content"));
                    if (prevFound != null)
                        return prevFound;
                }
            }
        }
        return null;
    }

    private String extractHotelName(String text) {
        if (text == null)
            return null;
        String clean = normalizeInput(text);
        if (clean.contains("azure") || clean.contains("grand"))
            return "Grand Azure";
        if (clean.contains("bosphorus"))
            return "Bosphorus";
        if (clean.contains("sapanca") || clean.contains("sapbca") || clean.contains("doga") || clean.contains("doğa"))
            return "Sapanca";
        if (clean.contains("sunset") || clean.contains("fethiye"))
            return "Fethiye";
        if (clean.contains("blue") || clean.contains("bodrum"))
            return "Bodrum";
        if (clean.contains("peri") || clean.contains("kapadokya"))
            return "Kapadokya";
        if (clean.contains("abant") || clean.contains("kosk") || clean.contains("köşk"))
            return "Abant";
        if (clean.contains("panorama") || clean.contains("kas") || clean.contains("kaş"))
            return "Kaş";
        return null;
    }

    private String extractRoomType(String text) {
        if (text == null)
            return null;
        String clean = normalizeInput(text);
        if (clean.contains("bungalov") || clean.contains("bungalow") || clean.contains("bunaglov")
                || clean.contains("bunaglove") ||
                clean.contains("kutuk ev") || clean.contains("kütük ev") || clean.contains("dag evi")
                || clean.contains("dağ evi")) {
            return "BUNGALOW";
        }
        if (clean.contains("villa") || clean.contains("villalar") || clean.contains("mustakil villa")) {
            return "VILLA";
        }
        if (clean.contains("tas ev") || clean.contains("taş ev") || clean.contains("magara") || clean.contains("mağara")
                || clean.contains("cave")) {
            return "STONE_HOUSE";
        }
        if (clean.contains("suit") || clean.contains("suite") || clean.contains("süit")) {
            return "SUITE";
        }
        if (clean.contains("family room") || clean.contains("aile odasi")) {
            return "FAMILY";
        }
        if (clean.contains("single room") || clean.contains("single oda")) {
            return "SINGLE";
        }
        if (clean.contains("double room") || clean.contains("double oda")) {
            return "DOUBLE";
        }
        return null;
    }

    private String extractRoomType(String text, List<Map<String, String>> history) {
        String found = extractRoomType(text);
        if (found != null)
            return found;
        if (history != null) {
            for (int i = history.size() - 1; i >= 0; i--) {
                if ("user".equals(history.get(i).get("role"))) {
                    String prevFound = extractRoomType(history.get(i).get("content"));
                    if (prevFound != null)
                        return prevFound;
                }
            }
        }
        return null;
    }

    private String extractExcludeFeature(String text) {
        if (text == null)
            return null;
        String clean = normalizeInput(text);

        // 1. Bar olmasın / Bar olmayanlar kontrolü
        if ((clean.contains("barsiz") || clean.contains("barsız") || clean.contains("bar olmayan")
                || clean.contains("bari olmayan") || clean.contains("barı olmayan") || clean.contains("bar bulunmayan")
                || clean.contains("bar olmasin") || clean.contains("bar olmasın") || clean.contains("bar istemiyorum"))
                && !clean.contains("barbeku") && !clean.contains("barbekü")) {
            return "Bar";
        }

        if (clean.contains("havuzsuz") || clean.contains("havuz olmasin") || clean.contains("havuz olmayan")
                || clean.contains("havuzu olmayan") || clean.contains("havuz bulunmayan")
                || clean.contains("havuz istemiyorum") || clean.contains("havuzsuz olsun")) {
            return "Havuz";
        }
        if (clean.contains("jakuzisiz") || clean.contains("jakuzi olmasin") || clean.contains("jakuzi olmayan")
                || clean.contains("jakuzisi olmayan") || clean.contains("jakuzi bulunmayan")
                || clean.contains("jakuzisiz olsun")) {
            return "Jakuzi";
        }
        if (clean.contains("sominesiz") || clean.contains("şöminesiz") || clean.contains("somine olmasin")
                || clean.contains("somine olmayan") || clean.contains("sominesi olmayan")
                || clean.contains("somine bulunmayan")) {
            return "Şömine";
        }
        if (clean.contains("spasiz") || clean.contains("spa olmayan") || clean.contains("spa bulunmayan")
                || clean.contains("spa olmasin")) {
            return "Spa";
        }
        if (clean.contains("balkonsuz") || clean.contains("balkon olmayan") || clean.contains("balkonu olmayan")
                || clean.contains("balkon bulunmayan")) {
            return "Balkon";
        }
        if (clean.contains("restoransiz") || clean.contains("restoransız") || clean.contains("restoran olmayan")
                || clean.contains("restorani olmayan")) {
            return "Restoran";
        }
        if (clean.contains("barbekusuz") || clean.contains("barbeküsüz") || clean.contains("barbeku olmayan")
                || clean.contains("barbekusu olmayan") || clean.contains("mangalsiz")) {
            return "Barbekü";
        }
        if (clean.contains("denizsiz") || clean.contains("deniz olmayan") || clean.contains("denize sifir olmayan")) {
            return "Deniz";
        }
        return null;
    }

    private String extractExcludeFeature(String text, List<Map<String, String>> history) {
        String found = extractExcludeFeature(text);
        if (found != null)
            return found;
        if (history != null) {
            for (int i = history.size() - 1; i >= 0; i--) {
                if ("user".equals(history.get(i).get("role"))) {
                    String prevFound = extractExcludeFeature(history.get(i).get("content"));
                    if (prevFound != null)
                        return prevFound;
                }
            }
        }
        return null;
    }

    private String extractFeature(String text) {
        if (text == null)
            return null;
        String clean = normalizeInput(text);
        String exclude = extractExcludeFeature(text);

        // 1. Barbekü kontrolü (Bar kelimesinden önce kontrol edilmeli)
        if ((clean.contains("barbeku") || clean.contains("barbekü") || clean.contains("mangal"))
                && (exclude == null || !exclude.equalsIgnoreCase("Barbekü")))
            return "Barbekü";

        // 2. Bar / Barlı kontrolü
        if ((clean.matches(".*\\bbar\\b.*") || clean.contains("barli") || clean.contains("barlı")
                || clean.contains("barda") || clean.contains("bar olan"))
                && !clean.contains("barbeku") && !clean.contains("barbekü")
                && (exclude == null || !exclude.equalsIgnoreCase("Bar"))) {
            return "Bar";
        }

        if (clean.contains("havuz") && (exclude == null || !exclude.equalsIgnoreCase("Havuz")))
            return "Havuz";
        if (clean.contains("jakuzi") && (exclude == null || !exclude.equalsIgnoreCase("Jakuzi")))
            return "Jakuzi";
        if ((clean.contains("somine") || clean.contains("şömine") || clean.contains("kuzine"))
                && (exclude == null || !exclude.equalsIgnoreCase("Şömine")))
            return "Şömine";
        if (clean.contains("wifi") || clean.contains("wi-fi") || clean.contains("internet"))
            return "Wi-Fi";
        if (clean.contains("bahce") || clean.contains("bahçe"))
            return "Bahçe";
        if (clean.contains("spa") && (exclude == null || !exclude.equalsIgnoreCase("Spa")))
            return "Spa";
        if (clean.contains("restoran") || clean.contains("restaurant") || clean.contains("yemek"))
            return "Restoran";
        if (clean.contains("otopark") || clean.contains("park yeri") || clean.contains("garaj"))
            return "Otopark";
        if (clean.contains("klima") || clean.contains("klimali"))
            return "Klima";
        if (clean.contains("balkon") && (exclude == null || !exclude.equalsIgnoreCase("Balkon")))
            return "Balkon";
        if (clean.contains("deniz") || clean.contains("sahil") || clean.contains("plaj"))
            return "Deniz";
        return null;
    }

    private String extractFeature(String text, List<Map<String, String>> history) {
        String found = extractFeature(text);
        if (found != null)
            return found;
        if (history != null) {
            for (int i = history.size() - 1; i >= 0; i--) {
                if ("user".equals(history.get(i).get("role"))) {
                    String prevFound = extractFeature(history.get(i).get("content"));
                    if (prevFound != null)
                        return prevFound;
                }
            }
        }
        return null;
    }

    private int extractCapacity(String text) {
        if (text == null)
            return 0;
        String clean = normalizeInput(text);
        if (clean.contains("tek kisi") || clean.contains("tek kisilik") || clean.contains("1 kisi")
                || clean.contains("1 kisilik") || clean.contains("bir kisi") || clean.contains("bir kisilik")) {
            return 1;
        }
        if (clean.contains("cift kisi") || clean.contains("cift kisilik") || clean.contains("2 kisi")
                || clean.contains("2 kisilik") || clean.contains("iki kisi") || clean.contains("iki kisilik")
                || clean.contains("esimle") || clean.contains("sevgilimle")) {
            return 2;
        }
        if (clean.contains("uc kisi") || clean.contains("uc kisilik") || clean.contains("3 kisi")
                || clean.contains("3 kisilik")) {
            return 3;
        }
        if (clean.contains("dort kisi") || clean.contains("dort kisilik") || clean.contains("4 kisi")
                || clean.contains("4 kisilik")) {
            return 4;
        }
        if (clean.contains("bes kisi") || clean.contains("bes kisilik") || clean.contains("5 kisi")
                || clean.contains("5 kisilik")) {
            return 5;
        }
        if (clean.contains("alti kisi") || clean.contains("alti kisilik") || clean.contains("6 kisi")
                || clean.contains("6 kisilik")) {
            return 6;
        }
        Pattern p = Pattern.compile("(\\d+)\\s*(?:kisi|kişi|kisilik|kişilik)");
        Matcher m = p.matcher(clean);
        if (m.find()) {
            try {
                return Integer.parseInt(m.group(1));
            } catch (Exception ignored) {
            }
        }
        return 0;
    }

    private int extractCapacity(String text, List<Map<String, String>> history) {
        int found = extractCapacity(text);
        if (found > 0)
            return found;
        if (history != null) {
            for (int i = history.size() - 1; i >= 0; i--) {
                if ("user".equals(history.get(i).get("role"))) {
                    int prevFound = extractCapacity(history.get(i).get("content"));
                    if (prevFound > 0)
                        return prevFound;
                }
            }
        }
        return 0;
    }

    private int extractDays(String userMessage, String city) {
        if (userMessage == null) {
            return getDefaultDaysForCity(city);
        }
        String clean = normalizeInput(userMessage);

        // Sayı ve gün/günlük/gece eşleşmesi
        Pattern p = Pattern.compile("(\\d{1,2})\\s*(?:gun|gün|gunluk|günlük|gece|gecelik)");
        Matcher m = p.matcher(clean);
        if (m.find()) {
            try {
                int val = Integer.parseInt(m.group(1));
                if (val >= 1 && val <= 14) {
                    return Math.min(val, 7);
                }
            } catch (Exception ignored) {
            }
        }

        // Türkçe kelime ile gün sayıları
        if (clean.contains("1 gun") || clean.contains("bir gun") || clean.contains("1 gunluk")
                || clean.contains("bir gunluk") || clean.contains("tek gun")) {
            return 1;
        }
        if (clean.contains("haftasonu") || clean.contains("hafta sonu") || clean.contains("2 gun")
                || clean.contains("iki gun") || clean.contains("2 gunluk") || clean.contains("iki gunluk")
                || clean.contains("2 gece") || clean.contains("iki gece")) {
            return 2;
        }
        if (clean.contains("3 gun") || clean.contains("uc gun") || clean.contains("3 gunluk")
                || clean.contains("uc gunluk") || clean.contains("3 gece") || clean.contains("uc gece")) {
            return 3;
        }
        if (clean.contains("4 gun") || clean.contains("dort gun") || clean.contains("4 gunluk")
                || clean.contains("dort gunluk") || clean.contains("4 gece") || clean.contains("dort gece")) {
            return 4;
        }
        if (clean.contains("5 gun") || clean.contains("bes gun") || clean.contains("5 gunluk")
                || clean.contains("bes gunluk") || clean.contains("5 gece") || clean.contains("bes gece")) {
            return 5;
        }
        if (clean.contains("6 gun") || clean.contains("alti gun") || clean.contains("6 gunluk")
                || clean.contains("alti gunluk") || clean.contains("6 gece") || clean.contains("alti gece")) {
            return 6;
        }
        if (clean.contains("1 hafta") || clean.contains("bir hafta") || clean.contains("haftalik")
                || clean.contains("7 gun") || clean.contains("yedi gun") || clean.contains("7 gunluk")
                || clean.contains("yedi gunluk")) {
            return 7;
        }

        // İpucu kelimeleri
        if (clean.contains("kisa") || clean.contains("kacamak") || clean.contains("hizli")) {
            return 2;
        }
        if (clean.contains("uzun") || clean.contains("genis") || clean.contains("dolu dolu")) {
            return 5;
        }

        return getDefaultDaysForCity(city);
    }

    private int getDefaultDaysForCity(String city) {
        if (city == null)
            return 3;
        String c = city.toLowerCase();
        if (c.contains("sakarya") || c.contains("sapanc") || c.contains("abant")) {
            return 2;
        }
        if (c.contains("antalya") || c.contains("bodrum") || c.contains("mugla") || c.contains("muğla")
                || c.contains("fethiye") || c.contains("kas")) {
            return 3;
        }
        if (c.contains("istanbul")) {
            return 3;
        }
        if (c.contains("kapadokya") || c.contains("nevsehir") || c.contains("nevşehir")) {
            return 3;
        }
        return 3;
    }

    private String buildSystemPrompt() {
        StringBuilder sb = new StringBuilder();
        sb.append("Sen BookingAI akıllı otel ve konaklama rezervasyon asistanısın.\n");
        sb.append("ÖNEMLİ BAĞLAM VE ARAMA KURALLARI:\n");
        sb.append(
                "1. OLUMSUZ ÖZELLİK VE UCUZ ARAMALARINDA (örn: 'havuzsuz otelleri listele', 'havuzsuz daha ucuz olanlar', 'jakuzisiz'):\n");
        sb.append(
                "   - 'excludeFeature' alanına 'Havuz' veya istenmeyen özelliği yaz, 'feature' alanını boş bırak. 'maxPrice' alanını bütçeye göre (örn: 2500 veya 3000) ayarla. Önceki konuşmadaki otel adı veya şehir kısıtını gerekiyorsa genişlet.\n");
        sb.append(
                "2. FİYAT DÜŞÜRME / DAHA UCUZ İSTEKLERİNDE (örn: 'bunlar pahalı daha ucuz olanları göster', 'daha uygun var mı', 'daha ucuz', 'bundan daha ucuz'):\n");
        sb.append(
                "   - 'maxPrice' alanını kesinlikle önceki konuşmada gösterilen otelin gecelik fiyatından DAHA DÜŞÜK bir sayı olarak belirle. Asla önceki fiyattan daha yüksek bir fiyat yazma!\n");
        sb.append(
                "3. KULLANICI YENİ BİR ŞEHİR VEYA GENEL OTEL ARAMASI YAPTIĞINDA (örn: 'şimdi antalyadaki otelleri listele', 'Antalya otelleri'):\n");
        sb.append(
                "   - Önceki konuşmadaki oda tipi (bungalov vb.) veya fiyat kısıtlamalarını SIFIRLA, yeni şehre odaklan.\n");
        sb.append(
                "4. KULLANICI MEVCUT SEÇENEKLERİ ADIM ADIM DARALTTIĞINDA (örn: '3000 TL altı olsun', 'havuzlu olanlar', '2 kişilik'):\n");
        sb.append("   - Önceki şehir ve oda türü kriterlerini KORU ve yeni filtreyle birleştir.\n");
        sb.append("5. KULLANICI YENİ BİR TÜR İSTEDİĞİNDE (örn: 'villaları göster'):\n");
        sb.append("   - Oda türünü güncelle, varsa mevcut şehri koru.\n\n");

        sb.append("\nKURALLAR (SADECE FORMATI DÖN):\n");
        sb.append("1. Tüm Otelleri Göster / Sıfırla İsteklerinde:\n");
        sb.append(
                "FILTER_ACTION:{\"city\":\"\",\"roomType\":\"\",\"capacity\":0,\"maxPrice\":0,\"hotelName\":\"\",\"feature\":\"\",\"excludeFeature\":\"\",\"reply\":\"Tüm otel ve konaklama seçenekleri listelendi:\"}\n");
        sb.append("2. İptal Talebinde:\n");
        sb.append("CANCEL_ACTION:{\"reservationId\":RezervasyonIDVeya0}\n");
        sb.append("3. Rezervasyon Talebinde:\n");
        sb.append(
                "RESERVATION_ACTION:{\"userId\":1,\"roomId\":OdaID,\"checkInDate\":\"YYYY-MM-DD\",\"checkOutDate\":\"YYYY-MM-DD\"}\n");
        sb.append("4. Arama / Filtreleme Talebinde:\n");
        sb.append(
                "FILTER_ACTION:{\"city\":\"Şehir veya Boş\",\"roomType\":\"BUNGALOW veya VILLA veya STONE_HOUSE veya SUITE veya Boş\",\"capacity\":KapasiteVeya0,\"maxPrice\":MaksFiyatSayisiVeya0,\"hotelName\":\"Otel Adı veya Boş\",\"feature\":\"İstenen Özellik veya Boş\",\"excludeFeature\":\"İstenmeyen Özellik (örn: Havuz) veya Boş\",\"reply\":\"Kısa samimi Türkçe mesaj\"}\n");
        sb.append(
                "5. Fiyat Alarmı / Takip / Otomatik Rezervasyon Talebinde (örn: 'fiyat düşünce haber ver', 'fiyatı 3000 TL altına inince haber ver', 'fiyat alarmı kur' -> autoBook: false; 'fiyatı düşünce otomatik rezerve et' -> autoBook: true):\n");
        sb.append(
                "AUTO_RESERVE_ACTION:{\"userId\":1,\"roomId\":OdaID,\"targetPrice\":MaksHedefFiyatSayisi,\"phoneNumber\":\"TelefonNumarasiVeyaBos\",\"checkInDate\":\"YYYY-MM-DD\",\"checkOutDate\":\"YYYY-MM-DD\",\"autoBook\":trueVeyaFalse}\n");
        sb.append(
                "6. Genel Soru-Cevap / Sohbet / Bilgi İsteklerinde (örn: 'merhaba', 'nasılsın', 'giriş çıkış saatleri nedir', 'kahvaltı dahil mi', 'ne yapabilirsin', genel seyahat ve otel soruları):\n");
        sb.append(
                "GENERAL_REPLY:{\"reply\":\"Kullanıcının sorusuna samimi, net, profesyonel, emojisi olmayan açıklayıcı Türkçe yanıt.\"}\n");
        sb.append(
                "7. Tatil Planı / Gezi Rotası / Itinerary İsteklerinde (örn: '3 kişilik Antalya gezi planı', '4 günlük Kaş tatil planı', 'Sapanca hafta sonu rotası', 'Kapadokya gezi planı'):\n");
        sb.append(
                "   - 'city': Belirtilen şehir (örn: Antalya, Sakarya, Muğla, Nevşehir, İstanbul veya Boş)\n");
        sb.append(
                "   - 'days': Kullanıcının talep ettiği gün sayısı (örn: '4 günlük' -> 4, 'hafta sonu' -> 2, '5 gün' -> 5, '1 hafta' -> 7). Gün belirtilmediyse şehre göre dinamik en uygun gün sayısı (örn: Sapanca için 2, Antalya için 3 veya 4).\n");
        sb.append(
                "   - 'capacity': İstenen kişi sayısı (örn: '3 kişilik' -> 3, '2 kişi' / 'eşimle' -> 2, 'tek kişi' -> 1, belirtilmediyse 0).\n");
        sb.append(
                "   - 'theme': Gezi teması (örn: Doğa & Huzur, Deniz & Güneş, Tarih & Kültür, Macera & Keşif).\n");
        sb.append(
                "   - 'roomType': Tercih edilen konaklama türü (BUNGALOW, VILLA, STONE_HOUSE, SUITE, DELUXE veya Boş).\n");
        sb.append(
                "   - 'reply': HTML formatında gün gün detaylı gezi planı (tam olarak belirlenen gün sayısı kadar 1. Gün, 2. Gün... içermelidir).\n");
        sb.append(
                "ITINERARY_ACTION:{\"city\":\"Şehir veya Boş\",\"days\":GunSayisi,\"capacity\":KapasiteVeya0,\"theme\":\"Gezi Teması\",\"roomType\":\"BUNGALOW veya VILLA veya STONE_HOUSE veya SUITE veya Boş\",\"reply\":\"<div class='itinerary-box'><div class='itinerary-header'><i class='fa-solid fa-map-location-dot text-primary'></i> 🗓️ X Günlük Tatil ve Gezi Planı</div><div class='itinerary-day'><div class='itinerary-day-title'><i class='fa-solid fa-calendar-day text-primary'></i> 1. Gün: ...</div><div class='itinerary-slot'>☀️ <strong>Sabah (09:00 - 12:00):</strong> ...</div><div class='itinerary-slot'>🌤️ <strong>Öğle (13:00 - 17:00):</strong> ...</div><div class='itinerary-slot'>🌙 <strong>Akşam (19:00 - 22:00):</strong> ...</div></div><div class='itinerary-tip'><i class='fa-solid fa-lightbulb text-warning'></i> <strong>Yerel İpuçları & Gastronomi:</strong> ...</div></div>\"}\n");
        return sb.toString();
    }

    private String callGroqApi(String model, String systemPrompt, List<Map<String, String>> history, String userMessage)
            throws Exception {
        HttpHeaders headers = new HttpHeaders();
        headers.setContentType(MediaType.APPLICATION_JSON);
        headers.setBearerAuth(apiKey.trim());

        List<Map<String, String>> messagesList = new ArrayList<>();

        Map<String, String> sys = new HashMap<>();
        sys.put("role", "system");
        sys.put("content", systemPrompt);
        messagesList.add(sys);

        int startIdx = Math.max(0, history.size() - 8);
        for (int i = startIdx; i < history.size(); i++) {
            Map<String, String> h = history.get(i);
            Map<String, String> msg = new HashMap<>();
            msg.put("role", h.get("role"));
            msg.put("content", h.get("content"));
            messagesList.add(msg);
        }

        Map<String, String> usr = new HashMap<>();
        usr.put("role", "user");
        usr.put("content", userMessage);
        messagesList.add(usr);

        Map<String, Object> body = new HashMap<>();
        body.put("model", model);
        body.put("messages", messagesList);
        body.put("temperature", 0.0);

        HttpEntity<Map<String, Object>> entity = new HttpEntity<>(body, headers);
        ResponseEntity<String> response = restTemplate.postForEntity(apiUrl.trim(), entity, String.class);

        JsonNode root = objectMapper.readTree(response.getBody());
        return root.path("choices").get(0).path("message").path("content").asText().trim();
    }

    private static class FilterCriteria {
        String city;
        String roomType;
        String feature;
        String excludeFeature;
        int capacity;
        Double maxPrice;
        String hotelName;
        boolean cheaperRequested;
    }

    private FilterCriteria resolveFilters(String userMessage, List<Map<String, String>> history,
            String rawCity, String rawRoomType, String rawFeature, String rawExcludeFeature, int rawCapacity,
            Double rawPrice,
            String rawHotelName) {
        FilterCriteria fc = new FilterCriteria();
        String currentMsgNorm = normalizeInput(userMessage);

        String currentCity = extractCity(currentMsgNorm);
        String currentRoomType = extractRoomType(currentMsgNorm);
        String currentFeature = extractFeature(currentMsgNorm);
        String currentExclude = extractExcludeFeature(currentMsgNorm);
        int currentCapacity = extractCapacity(currentMsgNorm);
        Double currentPrice = findPriceInText(userMessage);
        String currentHotel = extractHotelName(currentMsgNorm);
        boolean isCheaper = isCheaperIntent(userMessage);
        fc.cheaperRequested = isCheaper;

        boolean isNewCitySearch = (currentCity != null);
        boolean isNewTypeSearch = (currentRoomType != null);
        boolean isExplicitReset = currentMsgNorm.contains("tum otel") || currentMsgNorm.contains("sifirla") ||
                currentMsgNorm.contains("temizle") || currentMsgNorm.contains("yeni arama") ||
                currentMsgNorm.contains("baska") || currentMsgNorm.contains("farkli");

        if (isExplicitReset) {
            fc.city = currentCity;
            fc.roomType = currentRoomType;
            fc.feature = currentFeature;
            fc.excludeFeature = currentExclude;
            fc.capacity = currentCapacity;
            fc.maxPrice = currentPrice;
            fc.hotelName = currentHotel;
            return fc;
        }

        if (isNewCitySearch) {
            fc.city = currentCity;
            fc.roomType = (currentRoomType != null) ? currentRoomType
                    : (rawRoomType != null && !rawRoomType.isBlank() && !rawRoomType.equalsIgnoreCase("Boş")
                            ? rawRoomType
                            : null);
            fc.feature = (currentFeature != null) ? currentFeature
                    : (rawFeature != null && !rawFeature.isBlank() && !rawFeature.equalsIgnoreCase("Boş") ? rawFeature
                            : null);
            fc.excludeFeature = (currentExclude != null) ? currentExclude
                    : (rawExcludeFeature != null && !rawExcludeFeature.isBlank()
                            && !rawExcludeFeature.equalsIgnoreCase("Boş") ? rawExcludeFeature
                                    : null);
            fc.capacity = currentCapacity > 0 ? currentCapacity : rawCapacity;
            fc.maxPrice = currentPrice != null ? currentPrice : null;
            fc.hotelName = currentHotel;
            return fc;
        }

        if (isNewTypeSearch) {
            fc.city = (rawCity != null && !rawCity.isBlank() && !rawCity.equalsIgnoreCase("Boş")) ? rawCity
                    : extractCity(currentMsgNorm, history);
            fc.roomType = currentRoomType;
            fc.feature = currentFeature;
            fc.excludeFeature = (currentExclude != null) ? currentExclude
                    : (rawExcludeFeature != null && !rawExcludeFeature.isBlank()
                            && !rawExcludeFeature.equalsIgnoreCase("Boş") ? rawExcludeFeature
                                    : null);
            fc.capacity = currentCapacity;
            fc.maxPrice = currentPrice;
            fc.hotelName = currentHotel;
            return fc;
        }

        // Kademeli daraltma (Incremental refinement):
        fc.city = (rawCity != null && !rawCity.isBlank() && !rawCity.equalsIgnoreCase("Boş")) ? rawCity
                : extractCity(currentMsgNorm, history);
        fc.roomType = (rawRoomType != null && !rawRoomType.isBlank() && !rawRoomType.equalsIgnoreCase("Boş"))
                ? rawRoomType
                : extractRoomType(currentMsgNorm, history);
        fc.feature = (rawFeature != null && !rawFeature.isBlank() && !rawFeature.equalsIgnoreCase("Boş")) ? rawFeature
                : extractFeature(currentMsgNorm, history);
        fc.excludeFeature = (currentExclude != null) ? currentExclude
                : (rawExcludeFeature != null && !rawExcludeFeature.isBlank()
                        && !rawExcludeFeature.equalsIgnoreCase("Boş") ? rawExcludeFeature
                                : extractExcludeFeature(currentMsgNorm, history));
        fc.capacity = rawCapacity > 0 ? rawCapacity : extractCapacity(currentMsgNorm, history);
        fc.hotelName = (currentHotel != null) ? currentHotel : ((currentExclude != null) ? null : extractHotelName(currentMsgNorm));

        if (isCheaper) {
            Double prevPrice = null;
            if (history != null) {
                for (int i = history.size() - 1; i >= 0; i--) {
                    String content = history.get(i).get("content");
                    Double found = findPriceInText(content);
                    if (found != null && found >= 500) {
                        prevPrice = found;
                        break;
                    }
                }
            }

            if (currentPrice != null) {
                fc.maxPrice = currentPrice;
            } else if (prevPrice != null && prevPrice > 500) {
                fc.maxPrice = prevPrice;
            } else if (rawPrice != null && rawPrice > 0) {
                fc.maxPrice = Math.min(rawPrice, 2800.0);
            } else {
                fc.maxPrice = 2500.0;
            }
        } else {
            fc.maxPrice = (rawPrice != null && rawPrice > 0) ? rawPrice : extractPriceDouble("", userMessage, history);
        }

        // Çelişen özellikleri dinamik olarak güncelle:
        if (currentExclude != null) {
            // Kullanıcı bu mesajda negatif bir özellik istedi (örn: "havuz olmasın", "havuzsuz")
            fc.excludeFeature = currentExclude;
            if (fc.feature != null && normalizeInput(fc.feature).contains(normalizeInput(currentExclude))) {
                fc.feature = null;
            }
        } else if (currentFeature != null) {
            // Kullanıcı bu mesajda pozitif bir özellik istedi (örn: "havuzlu olsun", "jakuzili")
            fc.feature = currentFeature;
            if (fc.excludeFeature != null && normalizeInput(fc.excludeFeature).contains(normalizeInput(currentFeature))) {
                fc.excludeFeature = null;
            }
        } else if (fc.feature != null && fc.excludeFeature != null) {
            String fNorm = normalizeInput(fc.feature);
            String exNorm = normalizeInput(fc.excludeFeature);
            if (fNorm.contains(exNorm) || exNorm.contains(fNorm)) {
                fc.excludeFeature = null;
            }
        }

        fc.hotelName = (rawHotelName != null && !rawHotelName.isBlank() && !rawHotelName.equalsIgnoreCase("Boş"))
                ? rawHotelName
                : extractHotelName(currentMsgNorm);

        return fc;
    }

    private ChatResponseDto parseAiResponse(String rawResponse, String userMessage, List<Map<String, String>> history) {
        String cleaned = cleanAiOutput(rawResponse);

        try {
            // 0. AUTO_RESERVE_ACTION
            Matcher autoMatcher = Pattern.compile("AUTO_RESERVE_ACTION:\\s*(\\{.*?\\})", Pattern.DOTALL)
                    .matcher(cleaned);
            if (autoMatcher.find()) {
                JsonNode node = objectMapper.readTree(autoMatcher.group(1));
                long roomId = node.path("roomId").asLong(0L);
                long reqUserId = node.path("userId").asLong(1L);

                Room targetRoom = (roomId > 0L) ? roomRepository.findById(roomId).orElse(null) : null;
                if (targetRoom == null && roomId > 0L) {
                    targetRoom = findRoomDirectlyByNumber(String.valueOf(roomId), null);
                }

                if (targetRoom == null) {
                    return tryDirectAutoReserve(userMessage, history, reqUserId);
                }

                double targetPrice = extractTargetPrice(userMessage, targetRoom, history);
                String phone = node.path("phoneNumber").asText("");
                String inStr = normalizeDate(node.path("checkInDate").asText("2026-09-10"));
                String outStr = normalizeDate(node.path("checkOutDate").asText("2026-09-12"));

                boolean isAutoBook = node.has("autoBook") ? node.path("autoBook").asBoolean(false)
                        : isAutoBookRequested(userMessage);

                AutoReservationOrderRequestDto req = AutoReservationOrderRequestDto.builder()
                        .userId(reqUserId)
                        .roomId(targetRoom.getId())
                        .targetPrice(BigDecimal.valueOf(targetPrice))
                        .phoneNumber(phone != null && !phone.isBlank() ? phone : "+90 555 123 45 67")
                        .checkInDate(LocalDate.parse(inStr))
                        .checkOutDate(LocalDate.parse(outStr))
                        .autoBook(isAutoBook)
                        .build();

                AutoReservationOrderResponseDto order = autoReservationService.createOrder(req);
                return buildAutoReserveSuccessResponse(order);
            }

            // 1. CANCEL_ACTION
            Matcher cancelMatcher = Pattern.compile("CANCEL_ACTION:\\s*(\\{.*?\\})", Pattern.DOTALL).matcher(cleaned);
            if (cancelMatcher.find()) {
                JsonNode node = objectMapper.readTree(cancelMatcher.group(1));
                long resId = node.path("reservationId").asLong(0L);
                long reqUserId = node.path("userId").asLong(1L);

                if (resId == 0L) {
                    List<Reservation> userReservations = reservationRepository.findByUserIdOrderByIdDesc(reqUserId);
                    if (!userReservations.isEmpty()) {
                        resId = userReservations.get(0).getId();
                    }
                }

                if (resId != 0L) {
                    reservationService.cancelReservation(resId);
                    return new ChatResponseDto("#" + resId + " numaralı rezervasyonunuz başarıyla iptal edildi.");
                } else {
                    return new ChatResponseDto("İptal edilecek aktif bir rezervasyonunuz bulunamadı.");
                }
            }

            // 2. RESERVATION_ACTION
            Matcher resMatcher = Pattern.compile("RESERVATION_ACTION:\\s*(\\{.*?\\})", Pattern.DOTALL).matcher(cleaned);
            if (resMatcher.find()) {
                JsonNode node = objectMapper.readTree(resMatcher.group(1));
                long roomId = node.path("roomId").asLong(0L);
                long reqUserId = node.path("userId").asLong(1L);

                Room targetRoom = (roomId > 0L) ? roomRepository.findById(roomId).orElse(null) : null;
                if (targetRoom == null && roomId > 0L) {
                    targetRoom = findRoomDirectlyByNumber(String.valueOf(roomId), null);
                }

                if (targetRoom == null) {
                    return tryDirectReservation(userMessage, history, reqUserId);
                }

                String inStr = normalizeDate(node.path("checkInDate").asText("2026-09-10"));
                String outStr = normalizeDate(node.path("checkOutDate").asText("2026-09-12"));

                ReservationRequestDto req = new ReservationRequestDto();
                req.setUserId(reqUserId);
                req.setRoomId(targetRoom.getId());
                req.setCheckInDate(LocalDate.parse(inStr));
                req.setCheckOutDate(LocalDate.parse(outStr));

                ReservationResponseDto res = reservationService.createReservation(req);
                return new ChatResponseDto(String.format(
                        "<strong>Rezervasyonunuz Başarıyla Oluşturuldu!</strong><br><strong>Rezervasyon No:</strong> #%d<br><strong>Tarih:</strong> %s &rarr; %s<br><strong>Toplam Tutar:</strong> %s ₺",
                        res.getId(), res.getCheckInDate(), res.getCheckOutDate(), res.getTotalPrice()));
            }

            // 3. FILTER_ACTION
            Matcher filterMatcher = Pattern.compile("FILTER_ACTION:\\s*(\\{.*?\\})", Pattern.DOTALL).matcher(cleaned);
            if (filterMatcher.find()) {
                JsonNode node = objectMapper.readTree(filterMatcher.group(1));

                String rawCity = node.path("city").asText("").trim();
                String rawRoomType = node.path("roomType").asText("").trim();
                int rawCapacity = node.path("capacity").asInt(0);
                Double rawPrice = extractPriceDouble(node.path("maxPrice").asText(""), userMessage, null);
                String rawHotelName = node.path("hotelName").asText("").trim();
                String rawFeature = node.path("feature").asText("").trim();
                String rawExcludeFeature = node.path("excludeFeature").asText("").trim();
                String reply = node.path("reply").asText("Kriterlerinize uygun seçenekler listelendi:");

                FilterCriteria fc = resolveFilters(userMessage, history, rawCity, rawRoomType, rawFeature,
                        rawExcludeFeature, rawCapacity,
                        rawPrice, rawHotelName);

                Double dbMaxPrice = (fc.cheaperRequested && fc.maxPrice != null && fc.maxPrice > 0)
                        ? (fc.maxPrice - 1.0)
                        : fc.maxPrice;
                List<Room> filtered = queryRoomsFromDatabase(fc.city, fc.capacity, dbMaxPrice, fc.hotelName,
                        fc.feature, fc.excludeFeature, fc.roomType);

                if (filtered.isEmpty()) {
                    return resolveSmartAlternatives(fc);
                }

                if (fc.excludeFeature != null && !fc.excludeFeature.isBlank() && fc.cheaperRequested) {
                    String priceText = (fc.maxPrice != null && fc.maxPrice > 0) ? String.format("%.0f ₺ altı ", fc.maxPrice) : "";
                    reply = "<strong>" + fc.excludeFeature + " Bulunmayan " + priceText + "Seçenekler:</strong><br>Önceki seçeneğe göre daha bütçe dostu ve havuzsuz konaklama seçenekleri listelendi:";
                } else if (fc.excludeFeature != null && fc.excludeFeature.equalsIgnoreCase("Havuz")
                        && (reply.contains("Kriterlerinize") || reply.isBlank())) {
                    reply = "<strong>Havuzsuz (Doğa & Kültür Odaklı) Seçenekler:</strong><br>Havuz bulunmayan, doğa ve otantik konseptli konaklama seçeneklerimiz listelendi:";
                } else if (fc.cheaperRequested && (reply.contains("Kriterlerinize") || reply.isBlank())) {
                    String priceText = (fc.maxPrice != null && fc.maxPrice > 0) ? String.format("%.0f ₺ Altı ", fc.maxPrice) : "";
                    reply = "<strong>" + priceText + "Bütçenize Uygun Seçenekler:</strong><br>Önceki seçeneğe göre daha ekonomik konaklama seçenekleri en uygun fiyattan başlayarak listelendi:";
                }

                String enrichedReply = appendStayCalculation(reply, userMessage, filtered);
                List<String> suggestions = generateSmartSuggestions(fc, filtered);

                return new ChatResponseDto(enrichedReply, filtered, suggestions);
            }

            // 3.5 ITINERARY_ACTION
            Matcher itinMatcher = Pattern.compile("ITINERARY_ACTION:\\s*(\\{.*?\\})", Pattern.DOTALL).matcher(cleaned);
            if (itinMatcher.find()) {
                JsonNode node = objectMapper.readTree(itinMatcher.group(1));
                String rawCity = node.path("city").asText("").trim();
                if (rawCity.equalsIgnoreCase("Boş"))
                    rawCity = "";
                String rawRoomType = node.path("roomType").asText("").trim();
                if (rawRoomType.equalsIgnoreCase("Boş"))
                    rawRoomType = "";
                int rawCapacity = node.path("capacity").asInt(0);
                int rawDays = node.path("days").asInt(0);
                String reply = node.path("reply").asText("").trim();

                String city = !rawCity.isBlank() ? rawCity : extractCity(normalizeInput(userMessage), history);
                String roomType = !rawRoomType.isBlank() ? rawRoomType
                        : extractRoomType(normalizeInput(userMessage), history);
                int capacity = rawCapacity > 0 ? rawCapacity : extractCapacity(normalizeInput(userMessage), history);
                int days = rawDays > 0 ? rawDays : extractDays(userMessage, city);

                List<Room> matchedRooms = queryRoomsFromDatabase(city, capacity, null, null, null, null, roomType);
                if (matchedRooms.isEmpty() && roomType != null && !roomType.isBlank()) {
                    matchedRooms = queryRoomsFromDatabase(city, capacity, null, null, null, null, null);
                }
                if (matchedRooms.isEmpty() && capacity > 0) {
                    matchedRooms = queryRoomsFromDatabase(city, 0, null, null, null, null, null);
                }
                if (matchedRooms.isEmpty()) {
                    matchedRooms = queryRoomsFromDatabase(null, capacity, null, null, null, null, null);
                }
                if (matchedRooms.isEmpty()) {
                    matchedRooms = queryRoomsFromDatabase(null, 0, null, null, null, null, null);
                }

                if (reply.isBlank() || reply.length() < 60) {
                    return generateRichItineraryFallback(userMessage, history);
                }

                if (!reply.contains("itinerary-hotel-cta") && !matchedRooms.isEmpty()) {
                    Room top = matchedRooms.get(0);
                    String hName = top.getHotel() != null ? top.getHotel().getName() : "Otelimiz";
                    String capInfo = capacity > 0 ? String.format("%d Kişilik ", capacity) : "";
                    reply += String.format(
                            "<div class='itinerary-hotel-cta mt-2 p-2 rounded' style='background:#f0fdf4;border:1px solid #bbf7d0;color:#166534;font-size:0.83rem;'><i class='fa-solid fa-hotel text-success'></i> <strong>%sBu Plana En Uygun Konaklama:</strong> %s &mdash; <strong>%s ₺/gece</strong> (Kapasite: %d Kişi &mdash; Aşağıdaki karttan hemen ayırtabilirsiniz)</div>",
                            capInfo, hName, top.getPricePerNight(), top.getCapacity());
                }

                List<String> suggestions = new ArrayList<>();
                if (days == 2 || days == 3) {
                    suggestions.add(String.format("%d Günlük Yap", days + 1));
                } else if (days >= 4) {
                    suggestions.add(String.format("%d Günlük Yap", days - 1));
                }
                suggestions.add("Hemen Rezerve Et");
                suggestions.add("Fiyat Alarmı Kur");
                if (capacity > 0) {
                    suggestions.add(String.format("%d Kişilik Odalar", capacity));
                } else {
                    suggestions.add("Daha Bütçe Dostu");
                }
                return new ChatResponseDto(reply, matchedRooms,
                        suggestions.stream().distinct().limit(4).collect(Collectors.toList()));
            }

            // 4. GENERAL_REPLY
            Matcher genMatcher = Pattern.compile("GENERAL_REPLY:\\s*(\\{.*?\\})", Pattern.DOTALL).matcher(cleaned);
            if (genMatcher.find()) {
                JsonNode node = objectMapper.readTree(genMatcher.group(1));
                String reply = node.path("reply").asText("").trim();
                if (!reply.isBlank()) {
                    List<String> suggestions = List.of("Antalya Otelleri", "Fiyat Takibi", "Sapanca Bungalov",
                            "Kaş Villaları");
                    return new ChatResponseDto(reply, List.of(), suggestions);
                }
            }

            // 5. DOĞAL METİN YANITI (Herhangi bir eylem etiketi olmadan dönen serbest
            // metin)
            if (!cleaned.isBlank() && !cleaned.contains("FILTER_ACTION") && !cleaned.contains("RESERVATION_ACTION")
                    && !cleaned.contains("CANCEL_ACTION") && !cleaned.contains("AUTO_RESERVE_ACTION")
                    && !cleaned.contains("ITINERARY_ACTION")) {
                if (isItineraryIntent(userMessage)) {
                    return generateRichItineraryFallback(userMessage, history);
                }
                List<String> suggestions = List.of("Antalya Otelleri", "Fiyat Takibi", "Sapanca Bungalov",
                        "Kaş Villaları");
                return new ChatResponseDto(cleaned, List.of(), suggestions);
            }

        } catch (Exception e) {
            System.err.println("JSON parse hatası: " + e.getMessage());
        }

        return executeRobustFallback(history, userMessage);
    }

    // =========================================================================
    // JDBC VE DİNAMİK SQL VERİTABANI SORGUSU (SELECT, WHERE, JOIN, LIMIT)
    // =========================================================================
    private List<Room> queryRoomsFromDatabase(String city, int capacity, Double maxPrice, String hotelName,
            String feature, String excludeFeature, String roomTypeStr) {

        StringBuilder sql = new StringBuilder();
        List<Object> params = new ArrayList<>();

        LocalDate inDate = LocalDate.of(2026, 9, 10);
        LocalDate outDate = LocalDate.of(2026, 9, 12);

        // 1. SELECT VE JOIN SORGUSU + Müsaitlik (Doluluk / Boşluk) Kontrolü
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

        // 2. Şehir / Bölge Filtresi (WHERE LOWER(city) LIKE ?)
        if (city != null && !city.isBlank() && !city.equalsIgnoreCase("Boş")) {
            String normCity = "%" + normalizeInput(city) + "%";
            sql.append("AND (LOWER(h.city) LIKE ? OR LOWER(h.address) LIKE ?) ");
            params.add(normCity);
            params.add(normCity);
        }

        // 3. Konaklama / Oda Türü Filtresi (WHERE r.room_type = ?)
        if (roomTypeStr != null && !roomTypeStr.isBlank() && !roomTypeStr.equalsIgnoreCase("Boş")) {
            RoomType rt = parseRoomTypeEnum(roomTypeStr);
            if (rt != null) {
                sql.append("AND r.room_type = ? ");
                params.add(rt.name());
            }
        }

        // 4. Kapasite Filtresi (WHERE r.capacity >= ?)
        if (capacity > 0) {
            sql.append("AND r.capacity >= ? ");
            params.add(capacity);
        }

        // 5. Maksimum Bütçe Filtresi (WHERE r.price_per_night <= ?)
        if (maxPrice != null && maxPrice > 0) {
            sql.append("AND r.price_per_night <= ? ");
            params.add(BigDecimal.valueOf(maxPrice));
        }

        // 6. Otel Adı Filtresi (WHERE LOWER(h.name) LIKE ?)
        if (hotelName != null && !hotelName.isBlank() && !hotelName.equalsIgnoreCase("Boş")) {
            sql.append("AND LOWER(h.name) LIKE ? ");
            params.add("%" + normalizeInput(hotelName) + "%");
        }

        // 7. İstenen Olanaklar / Özellikler Filtresi (Pozitif Filtre: Wi-Fi, Havuz,
        // Jakuzi, Bar vb.)
        if (feature != null && !feature.isBlank() && !feature.equalsIgnoreCase("Boş")) {
            String[] tokens = feature.split("[,\\s+]+");
            for (String tok : tokens) {
                String cleanTok = normalizeInput(tok);
                if (cleanTok.length() >= 2 && !cleanTok.equals("ve") && !cleanTok.equals("ile")) {
                    if (cleanTok.equals("bar")) {
                        // "bar" kelimesi "barbekü" ile eşleşmemeli
                        sql.append(
                                "AND ((LOWER(COALESCE(r.features, '')) LIKE '%bar%' AND LOWER(COALESCE(r.features, '')) NOT LIKE '%barbek%') ")
                                .append("OR (LOWER(COALESCE(h.features, '')) LIKE '%bar%' AND LOWER(COALESCE(h.features, '')) NOT LIKE '%barbek%') ")
                                .append("OR (LOWER(COALESCE(h.description, '')) LIKE '%bar%' AND LOWER(COALESCE(h.description, '')) NOT LIKE '%barbek%')) ");
                    } else {
                        sql.append(
                                "AND (LOWER(COALESCE(r.features, '')) LIKE ? OR LOWER(COALESCE(h.features, '')) LIKE ? OR LOWER(COALESCE(h.description, '')) LIKE ?) ");
                        String term = "%" + cleanTok + "%";
                        params.add(term);
                        params.add(term);
                        params.add(term);
                    }
                }
            }
        }

        // 8. İstenmeyen Olanaklar / Özellikler Filtresi (Negatif Filtre: Havuzsuz,
        // Jakuzisiz, Barsız vb.)
        if (excludeFeature != null && !excludeFeature.isBlank() && !excludeFeature.equalsIgnoreCase("Boş")) {
            String[] tokens = excludeFeature.split("[,\\s+]+");
            for (String tok : tokens) {
                String cleanTok = normalizeInput(tok);
                if (cleanTok.length() >= 2 && !cleanTok.equals("ve") && !cleanTok.equals("ile")) {
                    if (cleanTok.equals("bar")) {
                        // Bar olmayanlar (Barı olmayan ama Barbeküsü olabilenler dahil edilir)
                        sql.append(
                                "AND (LOWER(COALESCE(r.features, '')) NOT LIKE '%bar%' OR LOWER(COALESCE(r.features, '')) LIKE '%barbek%') ")
                                .append("AND (LOWER(COALESCE(h.features, '')) NOT LIKE '%bar%' OR LOWER(COALESCE(h.features, '')) LIKE '%barbek%') ")
                                .append("AND (LOWER(COALESCE(h.description, '')) NOT LIKE '%bar%' OR LOWER(COALESCE(h.description, '')) LIKE '%barbek%') ");
                    } else {
                        sql.append("AND (LOWER(COALESCE(r.features, '')) NOT LIKE ? ")
                                .append("AND LOWER(COALESCE(h.features, '')) NOT LIKE ? ")
                                .append("AND LOWER(COALESCE(h.description, '')) NOT LIKE ?) ");
                        String term = "%" + cleanTok + "%";
                        params.add(term);
                        params.add(term);
                        params.add(term);
                    }
                }
            }
        }

        // 9. Sıralama ve Sayfalama (ORDER BY Fiyat ASC, LIMIT 50 - Büyük Veri
        // Optimizasyonu)
        sql.append("ORDER BY r.price_per_night ASC ");
        sql.append("LIMIT 50");

        // 10. JDBC Template ile SQL'i Doğrudan Veritabanında Çalıştırma ve Eşleme
        // (RowMapper)
        return jdbcTemplate.query(sql.toString(), (rs, rowNum) -> {
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

            String rTypeStr = rs.getString("room_type");
            RoomType rType = null;
            if (rTypeStr != null) {
                try {
                    rType = RoomType.valueOf(rTypeStr.toUpperCase().trim());
                } catch (Exception ignored) {
                }
            }

            boolean isAvail = rs.getObject("is_available") == null || rs.getInt("is_available") == 1;
            String badge = isAvail ? "Müsait" : "Dolu (Rezerve)";

            return Room.builder()
                    .id(rs.getLong("room_id"))
                    .roomNumber(rs.getString("room_number"))
                    .roomType(rType)
                    .pricePerNight(rs.getBigDecimal("price_per_night"))
                    .capacity(rs.getInt("capacity"))
                    .features(rs.getString("room_features"))
                    .imageUrl(rs.getString("image_url"))
                    .wishlistCount(rs.getObject("room_wishlist_count") != null ? rs.getInt("room_wishlist_count") : 0)
                    .hotel(hotel)
                    .isAvailable(isAvail)
                    .availabilityBadge(badge)
                    .build();
        }, params.toArray());
    }

    private RoomType parseRoomTypeEnum(String roomTypeStr) {
        if (roomTypeStr == null || roomTypeStr.isBlank() || roomTypeStr.equalsIgnoreCase("Boş")) {
            return null;
        }
        try {
            return RoomType.valueOf(roomTypeStr.toUpperCase().trim());
        } catch (Exception ignored) {
            String norm = normalizeInput(roomTypeStr);
            if (norm.contains("bungalov") || norm.contains("bungalow"))
                return RoomType.BUNGALOW;
            if (norm.contains("villa"))
                return RoomType.VILLA;
            if (norm.contains("tas") || norm.contains("stone"))
                return RoomType.STONE_HOUSE;
            if (norm.contains("suit"))
                return RoomType.SUITE;
            if (norm.contains("aile") || norm.contains("family"))
                return RoomType.FAMILY;
            if (norm.contains("single") || norm.contains("tek"))
                return RoomType.SINGLE;
            if (norm.contains("double") || norm.contains("cift"))
                return RoomType.DOUBLE;
        }
        return null;
    }

    private Double extractPriceDouble(String jsonPrice, String userMessage, List<Map<String, String>> history) {
        try {
            if (jsonPrice != null && !jsonPrice.isBlank() && !jsonPrice.equals("0")) {
                String cleanNum = jsonPrice.replaceAll("[^0-9.]", "");
                if (!cleanNum.isEmpty()) {
                    double val = Double.parseDouble(cleanNum);
                    if (val > 100)
                        return val;
                }
            }
        } catch (Exception ignored) {
        }

        // En son kullanıcı mesajında fiyat kontrolü
        Double priceFromMsg = findPriceInText(userMessage);
        if (priceFromMsg != null)
            return priceFromMsg;

        // Geçmişteki kullanıcı mesajlarında fiyat kontrolü
        if (history != null) {
            for (int i = history.size() - 1; i >= 0; i--) {
                if ("user".equals(history.get(i).get("role"))) {
                    Double prevVal = findPriceInText(history.get(i).get("content"));
                    if (prevVal != null)
                        return prevVal;
                }
            }
        }

        return null;
    }

    private Double findPriceInText(String text) {
        if (text == null)
            return null;
        String clean = normalizeInput(text)
                .replaceAll("([0-9]+)[.,]00\\b", "$1")
                .replace(".", "")
                .replace(",", ".");

        // ÖNCELİK 0: Gecelik oda fiyatı (örn: "Gecelik 1100 ₺", "1100 ₺/gece", "1100 ₺ / gece", "1100 TL / gece")
        Pattern pNightly = Pattern.compile(
                "(?:gecelik\\s*:?\\s*)?([1-9]\\d{2,5})(?:\\.00)?\\s*(?:tl|lira|₺)?\\s*(?:/\\s*gece|gecelik)",
                Pattern.CASE_INSENSITIVE);
        Matcher mNightly = pNightly.matcher(clean);
        if (mNightly.find()) {
            try {
                double val = Double.parseDouble(mNightly.group(1));
                if (val >= 500 && val != 2024 && val != 2025 && val != 2026 && val != 2027) {
                    return val;
                }
            } catch (Exception ignored) {
            }
        }

        Pattern pGecelik = Pattern.compile("gecelik\\s*:?\\s*([1-9]\\d{2,5})", Pattern.CASE_INSENSITIVE);
        Matcher mGecelik = pGecelik.matcher(clean);
        if (mGecelik.find()) {
            try {
                double val = Double.parseDouble(mGecelik.group(1));
                if (val >= 500 && val != 2024 && val != 2025 && val != 2026 && val != 2027) {
                    return val;
                }
            } catch (Exception ignored) {
            }
        }

        // ÖNCELİK 1: Açıkça TL / Lira veya Fiyat şartı (altına, veya altı, eşit veya
        // altı vb.) ile doğrudan bağlantılı sayı
        // Örn: "2000 tlnin altina", "2000 tl veya alti", "fiyati 2000", "2000 liranin
        // altinda", "2000 tl", "2000'e duserse"
        Pattern p1 = Pattern.compile(
                "([1-9]\\d{1,5})\\s*(?:tl|lira|bin|k|tl'den|tl'ye|tlden|tlye|tl'nin|tlnin|liranin|lira'nin)(?:\\s*(?:'nin|'nın|nin|nın|den|dan|ye|ya|e|a|veya|ve|ya da)?\\s*(?:esit|esiti|alti|altina|altinda|kadar|duserse|dusse|dusunce|inince|inerse|olursa|olunca|dusuruldugunde|seviyesine|seviyesi|dustugunde))?",
                Pattern.CASE_INSENSITIVE);
        Matcher m1 = p1.matcher(clean);
        if (m1.find()) {
            try {
                double val = Double.parseDouble(m1.group(1));
                if (val != 2024 && val != 2025 && val != 2026 && val != 2027) {
                    return val;
                }
            } catch (Exception ignored) {
            }
        }

        // ÖNCELİK 2: "fiyatı 2000", "fiyat 2000", "hedef 2000"
        Pattern pFiyat = Pattern.compile("(?:fiyat|fiyati|hedef)\\s*:?\\s*([1-9]\\d{1,5})", Pattern.CASE_INSENSITIVE);
        Matcher mFiyat = pFiyat.matcher(clean);
        if (mFiyat.find()) {
            try {
                double val = Double.parseDouble(mFiyat.group(1));
                if (val != 2024 && val != 2025 && val != 2026 && val != 2027) {
                    return val;
                }
            } catch (Exception ignored) {
            }
        }

        // ÖNCELİK 3: Şart kelimeleri ile biten veya başlayan sayılar ("2000 veya altı",
        // "2000 altına düştüğünde", "2000 ve altı", "2000 altı")
        Pattern pCond = Pattern.compile(
                "([1-9]\\d{1,5})\\s*(?:'nin|'nın|nin|nın|den|dan|ye|ya|e|a|veya|ve|ya da)?\\s*(?:esit\\s*(?:veya|ve|ya da)?\\s*)?(?:alti|altina|altinda|kadar|duserse|dusse|dusunce|inince|inerse|olursa|olunca|dusuruldugunde|dustugunde)",
                Pattern.CASE_INSENSITIVE);
        Matcher mCond = pCond.matcher(clean);
        if (mCond.find()) {
            try {
                double val = Double.parseDouble(mCond.group(1));
                if (val != 2024 && val != 2025 && val != 2026 && val != 2027) {
                    return val;
                }
            } catch (Exception ignored) {
            }
        }

        // ÖNCELİK 4: Oda numarası, kişi, gün, gece, ay adı (eylül, ekim vb.) OLMAYAN ve
        // en az 500 TL olan genel fiyat sayısı
        Pattern pGeneric = Pattern.compile(
                "(?<!(?:oda|no|nolu|numarali)\\s*)(?<!\\d)([1-9]\\d{2,5})(?!\\s*(?:nolu|numarali|no|kisi|gece|gun|ocak|subat|mart|nisan|mayis|haziran|temmuz|agustos|eylul|ekim|kasim|aralik))(?!\\d)");
        Matcher mGeneric = pGeneric.matcher(clean);
        while (mGeneric.find()) {
            try {
                double val = Double.parseDouble(mGeneric.group(1));
                if (val >= 500 && val != 2024 && val != 2025 && val != 2026 && val != 2027) {
                    return val;
                }
            } catch (Exception ignored) {
            }
        }

        return null;
    }

    private String cleanAiOutput(String text) {
        if (text == null)
            return "";
        String cleaned = text.replaceAll("(?s)<think>.*?(</think>|$)", "").trim();
        cleaned = cleaned.replaceAll(
                "(?si)(Here's a thinking process|The user is asking|Analyze User Input|Let's check).*?(?=(FILTER_ACTION|RESERVATION_ACTION|CANCEL_ACTION|AUTO_RESERVE_ACTION|ITINERARY_ACTION|GENERAL_REPLY|$))",
                "");
        cleaned = cleaned.replace("```json", "").replace("```", "").trim();
        return cleaned;
    }

    private String normalizeDate(String rawDate) {
        if (rawDate == null || rawDate.isBlank())
            return "2026-09-10";
        if (rawDate.matches("^\\d{2}-\\d{2}$")) {
            String[] parts = rawDate.split("-");
            return "2026-" + parts[1] + "-" + parts[0];
        }
        return rawDate;
    }

    private boolean isGeneralGreetingOrHelp(String text) {
        if (text == null)
            return false;
        String clean = normalizeInput(text);
        return clean.matches(
                ".*\\b(merhaba|selam|gunaydin|iyi gunler|iyi aksamlar|hey|nasilsin|naber|ne haber|nasil gidiyor|yardim|ne yapabilirsin|neler yapabilirsin|nasil calisir|giris saati|cikis saati|saat kacta|kahvalti|evcil hayvan|iletisim|telefon)\\b.*");
    }

    private ChatResponseDto buildGeneralChatFallback(String text) {
        String clean = normalizeInput(text);
        if (clean.contains("merhaba") || clean.contains("selam") || clean.contains("gunaydin")
                || clean.contains("iyi gunler") || clean.contains("hey")) {
            return new ChatResponseDto(
                    "Merhaba! BookingAI akıllı otel ve rezervasyon asistanınıza hoş geldiniz. Şehir, bütçe veya konsept (bungalov, villa, havuzlu vb.) belirterek otel arayabilir, fiyat takip alarmı kurabilir veya rezervasyonlarınızı yönetebilirsiniz. Size nasıl yardımcı olabilirim?",
                    List.of(), List.of("Antalya Otelleri", "Sapanca Bungalov", "3000 TL Altı", "Rezervasyonlarım"));
        }
        if (clean.contains("nasilsin") || clean.contains("naber") || clean.contains("ne haber")
                || clean.contains("nasil gidiyor")) {
            return new ChatResponseDto(
                    "Harikayım, teşekkür ederim! Size en uygun tatil ve konaklama fırsatlarını sunmak için buradayım. Bugün nereye seyahat etmek istersiniz?",
                    List.of(), List.of("Antalya Otelleri", "Sapanca Bungalov", "Kaş Villaları", "Bodrum Butik"));
        }
        if (clean.contains("giris saati") || clean.contains("cikis saati") || clean.contains("saat kacta")) {
            return new ChatResponseDto(
                    "Otellerimizde standart giriş (Check-in) saati <strong>14:00</strong>, çıkış (Check-out) saati ise <strong>12:00</strong>'dir. Erken giriş veya geç çıkış taleplerinizi rezervasyon sırasında belirtebilirsiniz.",
                    List.of(), List.of("Otel Ara", "Fiyat Takibi", "Rezervasyonlarım"));
        }
        if (clean.contains("ne yapabilirsin") || clean.contains("neler yapabilirsin") || clean.contains("yardim")
                || clean.contains("nasil calisir")) {
            return new ChatResponseDto(
                    "BookingAI olarak size şu konularda yardımcı olabilirim:<br>• <strong>🗓️ Kişiselleştirilmiş Tatil Planlayıcı:</strong> '3 günlük Sapanca romantik plan' veya 'Kapadokya gezi rotası' diyerek gün gün aktivite, restoran ve rota planı çıkarabilirsiniz.<br>• <strong>Akıllı Otel Arama & Filtreleme:</strong> Şehir, fiyat aralığı, oda tipi (bungalov, villa, taş ev) ve özelliklere göre (Wi-Fi, havuz, şömine vb.) anında arama yapabilirsiniz.<br>• <strong>Otomatik Fiyat Takibi:</strong> İstediğiniz bir oda için hedef fiyat belirleyerek fiyat düştüğünde anında otomatik rezervasyon emri verebilirsiniz.<br>• <strong>Rezervasyon ve İptal:</strong> Dilediğiniz odayı saniyeler içinde rezerve edebilir veya mevcut rezervasyonlarınızı yönetebilirsiniz.",
                    List.of(),
                    List.of("3 Günlük Sapanca Planı", "Kapadokya Gezi Rotası", "Antalya Otelleri", "Fiyat Takibi"));
        }
        return new ChatResponseDto(
                "Size otel arama, kişiselleştirilmiş tatil planı çıkarma, fiyat takibi ve rezervasyon işlemlerinizde yardımcı olabilirim. Hangi şehirde veya nasıl bir konaklama/tatil planı arıyorsunuz?",
                List.of(),
                List.of("3 Günlük Sapanca Planı", "Kaş Gezi Rotası", "Antalya Otelleri", "Sapanca Bungalov"));
    }

    private ChatResponseDto executeRobustFallback(List<Map<String, String>> history, String userMessage) {
        if (isGeneralGreetingOrHelp(userMessage)) {
            return buildGeneralChatFallback(userMessage);
        }

        if (isItineraryIntent(userMessage)) {
            return generateRichItineraryFallback(userMessage, history);
        }

        FilterCriteria fc = resolveFilters(userMessage, history, null, null, null, null, 0, null, null);

        Double dbMaxPrice = (fc.cheaperRequested && fc.maxPrice != null && fc.maxPrice > 0)
                ? (fc.maxPrice - 1.0)
                : fc.maxPrice;
        List<Room> filtered = queryRoomsFromDatabase(fc.city, fc.capacity, dbMaxPrice, fc.hotelName, fc.feature,
                fc.excludeFeature, fc.roomType);

        if (!filtered.isEmpty()) {
            String defaultReply = "Kriterlerinize uygun konaklama seçeneklerimiz listelendi:";
            if (fc.excludeFeature != null && !fc.excludeFeature.isBlank()) {
                String exLabel = fc.excludeFeature.equalsIgnoreCase("Havuz") ? "Havuzsuz (Doğa & Kültür Odaklı)"
                        : (fc.excludeFeature + " Bulunmayan");
                defaultReply = "<strong>" + exLabel + " Seçenekler:</strong><br>" + fc.excludeFeature
                        + " bulunmayan konaklama seçeneklerimiz listelendi:";
            } else if (fc.cheaperRequested) {
                defaultReply = "<strong>Bütçe Dostu ve Daha Uygun Seçenekler:</strong><br>Daha ekonomik konaklama seçenekleri en uygun fiyattan başlayarak listelendi:";
            }

            String reply = appendStayCalculation(defaultReply, userMessage, filtered);
            List<String> suggestions = generateSmartSuggestions(fc, filtered);
            return new ChatResponseDto(reply, filtered, suggestions);
        }

        return resolveSmartAlternatives(fc);
    }

    private ChatResponseDto resolveSmartAlternatives(FilterCriteria fc) {
        String trRoomType = formatRoomTypeTurkish(fc.roomType);
        String cityName = (fc.city != null && !fc.city.isBlank() && !fc.city.equalsIgnoreCase("Boş")) ? fc.city : null;

        // 1. Kullanıcı belirli bir şehirdeyken (örn: Antalya) havuzsuz veya belirli bir filtre aradıysa ve o şehirde yoksa:
        if (cityName != null) {
            StringBuilder reason = new StringBuilder();
            reason.append("<strong>").append(cityName).append("</strong> bölgesinde ");
            if (fc.excludeFeature != null && !fc.excludeFeature.isBlank() && !fc.excludeFeature.equalsIgnoreCase("Boş")) {
                reason.append("<strong>").append(fc.excludeFeature).append(" bulunmayan</strong> ");
            }
            if (fc.roomType != null && !fc.roomType.isBlank() && !fc.roomType.equalsIgnoreCase("Boş")) {
                reason.append("<strong>").append(trRoomType).append("</strong> türünde ");
            }
            if (fc.feature != null && !fc.feature.isBlank() && !fc.feature.equalsIgnoreCase("Boş")) {
                reason.append("<strong>").append(fc.feature).append(" olan</strong> ");
            }
            if (fc.maxPrice != null && fc.maxPrice > 0) {
                reason.append(String.format("<strong>%.0f ₺ altı</strong> ", fc.maxPrice));
            }
            reason.append("kriterlerinize uygun bir konaklama seçeneğimiz bulunmuyor.<br><br>");
            reason.append("Dilerseniz bu bölgedeki mevcut seçenekleri görebilir veya farklı bir bölge/filtre seçebilirsiniz.");

            List<String> suggestions = new ArrayList<>();
            suggestions.add(cityName + " Tüm Oteller");
            if (fc.excludeFeature != null && !fc.excludeFeature.isBlank()) {
                suggestions.add("Sapanca Havuzsuz");
            } else {
                suggestions.add("Daha Yüksek Bütçe");
            }
            suggestions.add("Sohbeti Sıfırla");

            return new ChatResponseDto(reason.toString(), List.of(), suggestions);
        }

        // 2. Şehir belirtilmemiş genel aramalarda hiçbir oda bulunamadıysa:
        String noMatchMsg = "Belirttiğiniz filtrelere uygun konaklama seçeneği bulunamadı. Lütfen filtrelerinizi güncelleyin veya sohbeti sıfırlayın.";
        return new ChatResponseDto(noMatchMsg, List.of(),
                List.of("Tüm Otelleri Göster", "Sapanca Bungalov", "Kaş Villaları", "Sohbeti Sıfırla"));
    }

    private String formatRoomTypeTurkish(String type) {
        if (type == null)
            return "oda";
        String norm = normalizeInput(type);
        if (norm.contains("bungalov") || norm.contains("bungalow"))
            return "bungalov";
        if (norm.contains("villa"))
            return "villa";
        if (norm.contains("tas") || norm.contains("stone"))
            return "taş ev";
        if (norm.contains("suit"))
            return "süit oda";
        if (norm.contains("aile") || norm.contains("family"))
            return "aile odası";
        if (norm.contains("single") || norm.contains("tek"))
            return "tek kişilik oda";
        if (norm.contains("double") || norm.contains("cift"))
            return "çift kişilik oda";
        return type.toLowerCase();
    }

    private String appendStayCalculation(String reply, String userMessage, List<Room> rooms) {
        if (rooms == null || rooms.isEmpty() || userMessage == null)
            return reply;

        LocalDate[] dates = extractDateRange(userMessage);
        long nights = java.time.temporal.ChronoUnit.DAYS.between(dates[0], dates[1]);
        if (nights <= 0)
            nights = 1;

        boolean hasExplicitDateOrDuration = userMessage.matches(
                ".*\\b(\\d{1,2}\\s*(?:gece|gun|gün)|eylul|ekim|kasim|aralik|ocak|subat|mart|nisan|mayis|haziran|temmuz|agustos)\\b.*");

        if (nights > 1 || hasExplicitDateOrDuration) {
            Room topRoom = rooms.get(0);
            BigDecimal total = topRoom.getPricePerNight().multiply(BigDecimal.valueOf(nights));
            String note = String.format(
                    "<br><small class='text-primary fw-semibold'>Tarihler: %s &rarr; %s (%d Gece Toplam: %s ₺ | Gecelik %s ₺)</small>",
                    dates[0], dates[1], nights, total, topRoom.getPricePerNight());
            return reply + note;
        }

        return reply;
    }

    private List<String> generateSmartSuggestions(FilterCriteria fc, List<Room> rooms) {
        List<String> suggestions = new ArrayList<>();
        if (rooms != null && !rooms.isEmpty()) {
            Room first = rooms.get(0);
            String city = (first.getHotel() != null) ? first.getHotel().getCity() : "";

            if (fc.excludeFeature == null || !fc.excludeFeature.contains("Havuz")) {
                if (fc.feature == null || !fc.feature.contains("havuz"))
                    suggestions.add("Havuzsuz Seçenekler");
            }
            if (fc.maxPrice == null || fc.maxPrice > 3000)
                suggestions.add("Daha Ucuz Olanlar");
            if (fc.feature == null || !fc.feature.contains("jakuzi"))
                suggestions.add("Jakuzili Olanlar");
            if (fc.feature == null || !fc.feature.contains("somine"))
                suggestions.add("Şömineli Olanlar");
            if (fc.maxPrice == null || fc.maxPrice > 3500)
                suggestions.add("3000 TL Altı");
            if (first.getRoomType() == RoomType.BUNGALOW)
                suggestions.add("Şömineli Olanlar");
            if (first.getRoomType() == RoomType.VILLA)
                suggestions.add("Müstakil Havuzlu");
            suggestions.add("10-12 Eylül Rezerve Et");
        } else {
            suggestions.add("Antalya Otelleri");
            suggestions.add("Sapanca Bungalov");
            suggestions.add("Havuzsuz Oteller");
            suggestions.add("Daha Ucuz Seçenekler");
        }
        return suggestions.stream().distinct().limit(4).collect(Collectors.toList());
    }

    private ChatResponseDto generateRichItineraryFallback(String userMessage, List<Map<String, String>> history) {
        String msgNorm = normalizeInput(userMessage);
        String city = extractCity(msgNorm, history);
        String roomType = extractRoomType(msgNorm, history);
        int capacity = extractCapacity(msgNorm, history);
        int days = extractDays(userMessage, city);

        String theme = "Doğa & Dinlenme";
        if (msgNorm.contains("romantik") || msgNorm.contains("esim") || msgNorm.contains("sevgili")
                || msgNorm.contains("balayi")) {
            theme = "Romantik & Doğa Kaçamağı";
        } else if (msgNorm.contains("macera") || msgNorm.contains("atv") || msgNorm.contains("rafting")
                || msgNorm.contains("dalis")) {
            theme = "Macera, Deniz & Aktivite";
        } else if (msgNorm.contains("kultur") || msgNorm.contains("tarih") || msgNorm.contains("muze")) {
            theme = "Tarih, Kültür & Keşif";
        } else if (msgNorm.contains("aile") || msgNorm.contains("cocuk")) {
            theme = "Aile Boyu Keyifli Tatil";
        } else if (msgNorm.contains("deniz") || msgNorm.contains("plaj") || msgNorm.contains("yuzme")) {
            theme = "Turkuaz Deniz & Güneş";
        }

        if (city == null || city.isBlank()) {
            if (msgNorm.contains("sapanc"))
                city = "Sakarya";
            else if (msgNorm.contains("antalya"))
                city = "Antalya";
            else if (msgNorm.contains("bodrum"))
                city = "Muğla";
            else if (msgNorm.contains("fethiye") || msgNorm.contains("kas") || msgNorm.contains("oludeniz"))
                city = "Muğla";
            else if (msgNorm.contains("kapadokya") || msgNorm.contains("goreme"))
                city = "Nevşehir";
            else if (msgNorm.contains("istanbul"))
                city = "İstanbul";
            else
                city = "Sakarya";
        }

        String displayCity = city;
        if (city.equalsIgnoreCase("Sakarya") || msgNorm.contains("sapanc"))
            displayCity = "Sapanca";
        else if (city.equalsIgnoreCase("Muğla")
                && (msgNorm.contains("fethiye") || msgNorm.contains("oludeniz") || msgNorm.contains("kas")))
            displayCity = "Fethiye / Kaş";
        else if (city.equalsIgnoreCase("Muğla") && msgNorm.contains("bodrum"))
            displayCity = "Bodrum";
        else if (city.equalsIgnoreCase("Nevşehir") || msgNorm.contains("kapadokya"))
            displayCity = "Kapadokya";

        StringBuilder html = new StringBuilder();
        html.append("<div class='itinerary-box'>");
        String capHeader = capacity > 0 ? String.format("%d Kişilik ", capacity) : "";
        html.append(String.format(
                "<div class='itinerary-header'><i class='fa-solid fa-map-location-dot text-primary'></i> 🗓️ %s%d Günlük %s %s Planı</div>",
                capHeader, days, displayCity, theme));

        if (displayCity.contains("Sapanca") || city.equalsIgnoreCase("Sakarya")) {
            html.append("<div class='itinerary-day'>")
                    .append("<div class='itinerary-day-title'><i class='fa-solid fa-calendar-day text-primary'></i> 1. Gün: Varış & Göl Kıyısında Huzur</div>")
                    .append("<div class='itinerary-slot'>☀️ <strong>Sabah (09:00 - 12:00):</strong> Kırkpınar sahilinde göl manzaralı serpme köy kahvaltısı ve sahil boyu yürüyüş.</div>")
                    .append("<div class='itinerary-slot'>🌤️ <strong>Öğle (13:00 - 17:00):</strong> Maşukiye Şelaleleri ve doğa parkı gezisi; doğa içinde ATV safari & Zipline heyecanı.</div>")
                    .append("<div class='itinerary-slot'>🌙 <strong>Akşam (19:00 - 22:00):</strong> Göl kenarında kiremitte alabalık akşam yemeği; bungalovda şömine & jakuzi keyfi.</div>")
                    .append("</div>");

            if (days >= 2) {
                html.append("<div class='itinerary-day'>")
                        .append("<div class='itinerary-day-title'><i class='fa-solid fa-calendar-day text-primary'></i> 2. Gün: Doğa Parkı & Spa Deneyimi</div>")
                        .append("<div class='itinerary-slot'>☀️ <strong>Sabah:</strong> Ormanya Doğal Yaşam Parkı ve Hobbit Evleri ziyareti; temiz orman havasında yürüyüş.</div>")
                        .append("<div class='itinerary-slot'>🌤️ <strong>Öğle:</strong> Ayrı Gezegen Cam Teras'tan panoramik vadi manzarası izleme ve kahve molası.</div>")
                        .append("<div class='itinerary-slot'>🌙 <strong>Akşam:</strong> Barbekü keyfi ve bahçede ısıtmalı özel havuz deneyimi.</div>")
                        .append("</div>");
            }

            if (days >= 3) {
                html.append("<div class='itinerary-day'>")
                        .append("<div class='itinerary-day-title'><i class='fa-solid fa-calendar-day text-primary'></i> 3. Gün: Sanat, Alışveriş & Lezzet</div>")
                        .append("<div class='itinerary-slot'>☀️ <strong>Sabah:</strong> Yerel Kırkpınar butik kafelerinde brunch ve yerel lezzet tadımı.</div>")
                        .append("<div class='itinerary-slot'>🌤️ <strong>Öğle:</strong> Sapanca Sanat Sokağı ve organik köy pazarından el yapımı hediyelikler & reçel alışverişi.</div>")
                        .append("<div class='itinerary-slot'>🌙 <strong>Akşam:</strong> Göl kıyısında gün batımı kahvesi eşliğinde dinlendirici akşam keyfi.</div>")
                        .append("</div>");
            }

            if (days >= 4) {
                html.append("<div class='itinerary-day'>")
                        .append("<div class='itinerary-day-title'><i class='fa-solid fa-calendar-day text-primary'></i> 4. Gün: Kartepe Zirvesi & Soğucak Yaylası</div>")
                        .append("<div class='itinerary-slot'>☀️ <strong>Sabah:</strong> Kartepe zirvesine teleferik/araçla çıkış; bol oksijenli dağ yürüyüşü.</div>")
                        .append("<div class='itinerary-slot'>🌤️ <strong>Öğle:</strong> Soğucak Yaylası doğa keşfi ve dağ evinde sucuk-ekmek / mantar keyfi.</div>")
                        .append("<div class='itinerary-slot'>🌙 <strong>Akşam:</strong> Dağ havası sonrası bungalovda sıcak jakuzi ve dinlenme.</div>")
                        .append("</div>");
            }

            if (days >= 5) {
                html.append("<div class='itinerary-day'>")
                        .append("<div class='itinerary-day-title'><i class='fa-solid fa-calendar-day text-primary'></i> 5. Gün: Poyrazlar Gölü & Doğançay Şelalesi</div>")
                        .append("<div class='itinerary-slot'>☀️ <strong>Sabah:</strong> Poyrazlar Gölü Tabiat Parkı etrafında göl kenarı bisiklet turu.</div>")
                        .append("<div class='itinerary-slot'>🌤️ <strong>Öğle:</strong> Doğançay Şelalesi yürüyüş parkuru ve fotoğraf molası.</div>")
                        .append("<div class='itinerary-slot'>🌙 <strong>Akşam:</strong> Sakarya Nehri kenarında gün batımı çayı ve veda ziyafeti.</div>")
                        .append("</div>");
            }

            html.append(
                    "<div class='itinerary-tip'><i class='fa-solid fa-lightbulb text-warning'></i> <strong>Yerel İpucu:</strong> Kiremitte eritme köy peyniri ve güveçte mantar denemeyi unutmayın! Akşamları şömine başı için yanınıza hafif kalın kıyafetler almanız önerilir.</div>");

        } else if (city.equalsIgnoreCase("Antalya")) {
            html.append("<div class='itinerary-day'>")
                    .append("<div class='itinerary-day-title'><i class='fa-solid fa-calendar-day text-primary'></i> 1. Gün: Tarihi Kaleiçi & Akdeniz Esintisi</div>")
                    .append("<div class='itinerary-slot'>☀️ <strong>Sabah (09:00 - 12:00):</strong> Tarihi Hadrian (Üçkapılar) Kapısı'ndan Kaleiçi sokaklarına giriş; taş konakta kahvaltı.</div>")
                    .append("<div class='itinerary-slot'>🌤️ <strong>Öğle (13:00 - 17:00):</strong> Yat Limanı'ndan kalkan tekne turu ile falezler ve deniz mağaraları keşfi.</div>")
                    .append("<div class='itinerary-slot'>🌙 <strong>Akşam (19:00 - 22:00):</strong> Kaleiçi teras restoranında Akdeniz mezeleri ve taze balık ziyafeti.</div>")
                    .append("</div>");

            if (days >= 2) {
                html.append("<div class='itinerary-day'>")
                        .append("<div class='itinerary-day-title'><i class='fa-solid fa-calendar-day text-primary'></i> 2. Gün: Şelaleler & Plaj Keyfi</div>")
                        .append("<div class='itinerary-slot'>☀️ <strong>Sabah:</strong> Düden Şelalesi'nin denize döküldüğü noktada yürüyüş ve fotoğraf çekimi.</div>")
                        .append("<div class='itinerary-slot'>🌤️ <strong>Öğle:</strong> Lara / Konyaaltı sahilinde deniz, kum ve güneş keyfi; su sporları aktiviteleri.</div>")
                        .append("<div class='itinerary-slot'>🌙 <strong>Akşam:</strong> Marina çevresinde canlı müzik ve gün batımı kokteylleri.</div>")
                        .append("</div>");
            }

            if (days >= 3) {
                html.append("<div class='itinerary-day'>")
                        .append("<div class='itinerary-day-title'><i class='fa-solid fa-calendar-day text-primary'></i> 3. Gün: Antik Kentler & Kanyon Macerası</div>")
                        .append("<div class='itinerary-slot'>☀️ <strong>Sabah:</strong> Perge Antik Kenti ve Aspendos Tiyatrosu kültür turu.</div>")
                        .append("<div class='itinerary-slot'>🌤️ <strong>Öğle:</strong> Köprülü Kanyon'da serin sularda rafting ve doğa yürüyüşü.</div>")
                        .append("<div class='itinerary-slot'>🌙 <strong>Akşam:</strong> Meşhur Antalya tahinli piyazı ve şiş köfte lezzet durağı.</div>")
                        .append("</div>");
            }

            if (days >= 4) {
                html.append("<div class='itinerary-day'>")
                        .append("<div class='itinerary-day-title'><i class='fa-solid fa-calendar-day text-primary'></i> 4. Gün: Efsanevi Olimpos & Yanartaş Doğa Keşfi</div>")
                        .append("<div class='itinerary-slot'>☀️ <strong>Sabah:</strong> Olimpos Antik Kenti kalıntıları ve Çıralı Plajı'nın berrak sularında yüzme.</div>")
                        .append("<div class='itinerary-slot'>🌤️ <strong>Öğle:</strong> Çıralı portakal bahçelerinde taze sıkılmış meyve suları ve gözleme molası.</div>")
                        .append("<div class='itinerary-slot'>🌙 <strong>Akşam:</strong> Alacakaranlıkta Yanartaş (Chimaera) efsanevi sonsuz alevlerine tırmanış.</div>")
                        .append("</div>");
            }

            if (days >= 5) {
                html.append("<div class='itinerary-day'>")
                        .append("<div class='itinerary-day-title'><i class='fa-solid fa-calendar-day text-primary'></i> 5. Gün: Side Antik Kenti & Manavgat Şelalesi</div>")
                        .append("<div class='itinerary-slot'>☀️ <strong>Sabah:</strong> Manavgat Şelalesi çevresinde serin nehir havası ve nehir tekne turu.</div>")
                        .append("<div class='itinerary-slot'>🌤️ <strong>Öğle:</strong> Apollon Tapınağı ve Side Antik Tiyatrosu tarihi yürüyüşü.</div>")
                        .append("<div class='itinerary-slot'>🌙 <strong>Akşam:</strong> Side limanında deniz kenarında gün batımı akşam yemeği.</div>")
                        .append("</div>");
            }

            if (days >= 6) {
                html.append("<div class='itinerary-day'>")
                        .append("<div class='itinerary-day-title'><i class='fa-solid fa-calendar-day text-primary'></i> 6. Gün: Tahtalı Dağı Zirvesi & Phaselis Koyu</div>")
                        .append("<div class='itinerary-slot'>☀️ <strong>Sabah:</strong> Olympos Teleferik ile 2365 metre Tahtalı zirvesine çıkış; bulutların üzerinden Akdeniz manzarası.</div>")
                        .append("<div class='itinerary-slot'>🌤️ <strong>Öğle:</strong> Çam ağaçlarıyla çevrili Phaselis Antik Koyu'nda tarihi kalıntılar arasında deniz keyfi.</div>")
                        .append("<div class='itinerary-slot'>🌙 <strong>Akşam:</strong> Kemer Ayışığı Koyu'nda canlı müzik ve akşam yemeği.</div>")
                        .append("</div>");
            }

            if (days >= 7) {
                html.append("<div class='itinerary-day'>")
                        .append("<div class='itinerary-day-title'><i class='fa-solid fa-calendar-day text-primary'></i> 7. Gün: Alanya Kalesi & Dim Çayı Dinlenmesi</div>")
                        .append("<div class='itinerary-slot'>☀️ <strong>Sabah:</strong> Tarihi Alanya Kalesi, Kızılkule ve Kleopatra Plajı ziyareti.</div>")
                        .append("<div class='itinerary-slot'>🌤️ <strong>Öğle:</strong> Dim Çayı'nda suyun üzerine kurulu çardaklarda serinleyerek alabalık öğle yemeği.</div>")
                        .append("<div class='itinerary-slot'>🌙 <strong>Akşam:</strong> Damlataş Mağarası ziyareti ve veda kahvesi.</div>")
                        .append("</div>");
            }

            html.append(
                    "<div class='itinerary-tip'><i class='fa-solid fa-lightbulb text-warning'></i> <strong>Yerel İpucu:</strong> Meşhur Antalya usulü tahinli piyaz ve turunç reçelini mutlaka tadın. Sıcak günlerde şapkanızı ve güneş kreminizi yanınızdan ayırmayın.</div>");

        } else if (displayCity.contains("Fethiye") || displayCity.contains("Kaş")
                || (city.equalsIgnoreCase("Muğla") && !displayCity.contains("Bodrum"))) {
            html.append("<div class='itinerary-day'>")
                    .append("<div class='itinerary-day-title'><i class='fa-solid fa-calendar-day text-primary'></i> 1. Gün: Ölüdeniz & Babadağ Gün Batımı</div>")
                    .append("<div class='itinerary-slot'>☀️ <strong>Sabah (09:00 - 12:00):</strong> Belcekız Plajı ve Kumburnu Tabiat Parkı lagününde turkuaz sularda yüzme.</div>")
                    .append("<div class='itinerary-slot'>🌤️ <strong>Öğle (13:00 - 17:00):</strong> Babadağ Teleferik ile 1700m zirveye çıkış; yamaç paraşütlerini izleme.</div>")
                    .append("<div class='itinerary-slot'>🌙 <strong>Akşam (19:00 - 22:00):</strong> Babadağ zirvesinde gün batımı manzaralı akşam yemeği & villada dinlenme.</div>")
                    .append("</div>");

            if (days >= 2) {
                html.append("<div class='itinerary-day'>")
                        .append("<div class='itinerary-day-title'><i class='fa-solid fa-calendar-day text-primary'></i> 2. Gün: Kelebekler Vadisi Tekne Turu</div>")
                        .append("<div class='itinerary-slot'>☀️ <strong>Sabah:</strong> Ölüdeniz'den kalkan tekneyle Kelebekler Vadisi ve Akvaryum Koyu turu.</div>")
                        .append("<div class='itinerary-slot'>🌤️ <strong>Öğle:</strong> Soğuk Su Koyu ve St. Nicholas Adası'nda şnorkel ile dalış.</div>")
                        .append("<div class='itinerary-slot'>🌙 <strong>Akşam:</strong> Özel villanızın terasında havuz başı barbekü keyfi.</div>")
                        .append("</div>");
            }

            if (days >= 3) {
                html.append("<div class='itinerary-day'>")
                        .append("<div class='itinerary-day-title'><i class='fa-solid fa-calendar-day text-primary'></i> 3. Gün: Saklıkent Kanyonu & Kayaköy</div>")
                        .append("<div class='itinerary-slot'>☀️ <strong>Sabah:</strong> Saklıkent Kanyonu'nda buz gibi suların içinde kanyon yürüyüşü.</div>")
                        .append("<div class='itinerary-slot'>🌤️ <strong>Öğle:</strong> Tarihi Kayaköy Hayalet Köyü sokaklarında yürüyüş ve gözleme molası.</div>")
                        .append("<div class='itinerary-slot'>🌙 <strong>Akşam:</strong> Fethiye Paspatur Çarşısı'nda hediyelik alışverişi ve deniz kenarı kahvesi.</div>")
                        .append("</div>");
            }

            if (days >= 4) {
                html.append("<div class='itinerary-day'>")
                        .append("<div class='itinerary-day-title'><i class='fa-solid fa-calendar-day text-primary'></i> 4. Gün: Kaş Kaputaş Plajı & Patara Kum Tepeleri</div>")
                        .append("<div class='itinerary-slot'>☀️ <strong>Sabah:</strong> Masalsı Kaputaş Plajı kanyon ağzında turkuaz dalgalarda yüzme.</div>")
                        .append("<div class='itinerary-slot'>🌤️ <strong>Öğle:</strong> Patara Antik Kenti meclis binası ve deniz feneri kültür keşfi.</div>")
                        .append("<div class='itinerary-slot'>🌙 <strong>Akşam:</strong> Patara Çöl Kum Tepelerinde unutulmaz gün batımı izleme.</div>")
                        .append("</div>");
            }

            if (days >= 5) {
                html.append("<div class='itinerary-day'>")
                        .append("<div class='itinerary-day-title'><i class='fa-solid fa-calendar-day text-primary'></i> 5. Gün: Kekova Batık Şehir & Simena Kalesi</div>")
                        .append("<div class='itinerary-slot'>☀️ <strong>Sabah:</strong> Üçağız'dan kalkan tekneyle Kekova Batık Şehir kalıntıları üzerinde kano/tekne turu.</div>")
                        .append("<div class='itinerary-slot'>🌤️ <strong>Öğle:</strong> Simena (Kaleköy) Kalesi'ne tırmanış ve ev yapımı keçi sütlü dondurma tadımı.</div>")
                        .append("<div class='itinerary-slot'>🌙 <strong>Akşam:</strong> Kaş merkezde begonvilli sokaklarda Akdeniz mezeleri akşam yemeği.</div>")
                        .append("</div>");
            }

            html.append(
                    "<div class='itinerary-tip'><i class='fa-solid fa-lightbulb text-warning'></i> <strong>Yerel İpucu:</strong> Saklıkent Kanyonu için yanınıza deniz ayakkabısı almayı ve Babadağ gün batımını kaçırmamak için teleferik biletinizi önceden almayı unutmayın.</div>");

        } else if (displayCity.contains("Bodrum")) {
            html.append("<div class='itinerary-day'>")
                    .append("<div class='itinerary-day-title'><i class='fa-solid fa-calendar-day text-primary'></i> 1. Gün: Bodrum Kalesi & Yalıkavak Marina</div>")
                    .append("<div class='itinerary-slot'>☀️ <strong>Sabah:</strong> Tarihi Bodrum Kalesi & Sualtı Arkeoloji Müzesi ziyareti; bembeyaz sokaklarda kahvaltı.</div>")
                    .append("<div class='itinerary-slot'>🌤️ <strong>Öğle:</strong> Bodrum Çarşısı ve Marina boyu yürüyüş; Bitez sahilinde deniz keyfi.</div>")
                    .append("<div class='itinerary-slot'>🌙 <strong>Akşam:</strong> Yalıkavak Marina'da lüks restoranlar ve yat manzaralı akşam yemeği.</div>")
                    .append("</div>");

            if (days >= 2) {
                html.append("<div class='itinerary-day'>")
                        .append("<div class='itinerary-day-title'><i class='fa-solid fa-calendar-day text-primary'></i> 2. Gün: Akvaryum Koyu & Gümüşlük Gün Batımı</div>")
                        .append("<div class='itinerary-slot'>☀️ <strong>Sabah:</strong> Karaada ve Akvaryum Koyu tekne turu; berrak koylarda yüzme.</div>")
                        .append("<div class='itinerary-slot'>🌤️ <strong>Öğle:</strong> Tekne üzerinde ızgara balık ve taze Ege salatası.</div>")
                        .append("<div class='itinerary-slot'>🌙 <strong>Akşam:</strong> Gümüşlük sahilinde denizin içinde masalarda gün batımı eşliğinde meze & balık.</div>")
                        .append("</div>");
            }

            if (days >= 3) {
                html.append("<div class='itinerary-day'>")
                        .append("<div class='itinerary-day-title'><i class='fa-solid fa-calendar-day text-primary'></i> 3. Gün: Yel Değirmenleri & Gurme Keşif</div>")
                        .append("<div class='itinerary-slot'>☀️ <strong>Sabah:</strong> Tarihi Yel Değirmenleri tepesinde Bodrum ve Gümbet manzarası.</div>")
                        .append("<div class='itinerary-slot'>🌤️ <strong>Öğle:</strong> Meşhur Bodrum Çökertme Kebabı lezzet deneyimi.</div>")
                        .append("<div class='itinerary-slot'>🌙 <strong>Akşam:</strong> Butik otel havuz başında kokteyl eşliğinde dinlenme.</div>")
                        .append("</div>");
            }

            if (days >= 4) {
                html.append("<div class='itinerary-day'>")
                        .append("<div class='itinerary-day-title'><i class='fa-solid fa-calendar-day text-primary'></i> 4. Gün: Türkbükü & Cennet Koyu</div>")
                        .append("<div class='itinerary-slot'>☀️ <strong>Sabah:</strong> Cennet Koyu'nun turkuaz sularında sakin deniz ve şnorkel keyfi.</div>")
                        .append("<div class='itinerary-slot'>🌤️ <strong>Öğle:</strong> Göltürkbükü sahil yürüyüşü ve Ege zeytinyağlıları molası.</div>")
                        .append("<div class='itinerary-slot'>🌙 <strong>Akşam:</strong> Marina Yacht Club'da canlı caz performansı ve akşam yemeği.</div>")
                        .append("</div>");
            }

            html.append(
                    "<div class='itinerary-tip'><i class='fa-solid fa-lightbulb text-warning'></i> <strong>Yerel İpucu:</strong> Gümüşlük'te gün batımı için rezervasyon yaptırmayı ve Bodrum mandalinasından yapılan yerel tatlıları denemeyi unutmayın.</div>");

        } else if (displayCity.contains("Kapadokya") || city.equalsIgnoreCase("Nevşehir")) {
            html.append("<div class='itinerary-day'>")
                    .append("<div class='itinerary-day-title'><i class='fa-solid fa-calendar-day text-primary'></i> 1. Gün: Göreme Vadileri & Peri Bacaları</div>")
                    .append("<div class='itinerary-slot'>☀️ <strong>Sabah:</strong> Göreme Açık Hava Müzesi ve kaya kiliseleri keşfi; taş konakta serpme kahvaltı.</div>")
                    .append("<div class='itinerary-slot'>🌤️ <strong>Öğle:</strong> Paşabağları Rahipler Vadisi ve Devrent Hayal Vadisi'nde masalsı kaya oluşumları gezisi.</div>")
                    .append("<div class='itinerary-slot'>🌙 <strong>Akşam:</strong> Otantik mağara restoranda çömlekte kırılan meşhur Testi Kebabı ziyafeti.</div>")
                    .append("</div>");

            if (days >= 2) {
                html.append("<div class='itinerary-day'>")
                        .append("<div class='itinerary-day-title'><i class='fa-solid fa-calendar-day text-primary'></i> 2. Gün: Balon Turu & Uçhisar Gün Batımı</div>")
                        .append("<div class='itinerary-slot'>☀️ <strong>Sabah (05:30 - 08:30):</strong> Gün doğumunda Sıcak Hava Balon Turu veya otel terasından balonları izleme.</div>")
                        .append("<div class='itinerary-slot'>🌤️ <strong>Öğle:</strong> Aşk Vadisi ve Güvercinlik Vadisi'nde ATV Safari & doğa yürüyüşü.</div>")
                        .append("<div class='itinerary-slot'>🌙 <strong>Akşam:</strong> Uçhisar Kalesi'nde panoramik gün batımı ve mağara odada şömine keyfi.</div>")
                        .append("</div>");
            }

            if (days >= 3) {
                html.append("<div class='itinerary-day'>")
                        .append("<div class='itinerary-day-title'><i class='fa-solid fa-calendar-day text-primary'></i> 3. Gün: Yeraltı Şehri & Çömlek Atölyesi</div>")
                        .append("<div class='itinerary-slot'>☀️ <strong>Sabah:</strong> Derinkuyu veya Kaymaklı Yeraltı Şehri'nin gizemli tünellerini keşfetme.</div>")
                        .append("<div class='itinerary-slot'>🌤️ <strong>Öğle:</strong> Avanos Kızılırmak kıyısında geleneksel çömlek yapım atölyesi deneyimi.</div>")
                        .append("<div class='itinerary-slot'>🌙 <strong>Akşam:</strong> Yerel şarap tadımı ve tarihi konakta huzurlu akşam kahvesi.</div>")
                        .append("</div>");
            }

            if (days >= 4) {
                html.append("<div class='itinerary-day'>")
                        .append("<div class='itinerary-day-title'><i class='fa-solid fa-calendar-day text-primary'></i> 4. Gün: Ihlara Vadisi Kanyonu & Selime Manastırı</div>")
                        .append("<div class='itinerary-slot'>☀️ <strong>Sabah:</strong> Ihlara Vadisi kanyonunda Melendiz Çayı boyu doğa yürüyüşü ve kaya kiliseleri.</div>")
                        .append("<div class='itinerary-slot'>🌤️ <strong>Öğle:</strong> Belisırma Köyü'nde nehir üstü çardaklarda yöresel alabalık ve saç kavurma.</div>")
                        .append("<div class='itinerary-slot'>🌙 <strong>Akşam:</strong> Selime Katedrali dev kaya manastırında büyüleyici gün batımı.</div>")
                        .append("</div>");
            }

            html.append(
                    "<div class='itinerary-tip'><i class='fa-solid fa-lightbulb text-warning'></i> <strong>Yerel İpucu:</strong> Balon izlemek için sabah erken saatte hava serin olacağından kalın bir ceket almayı ve Avanos'ta çömlek çarkının başına geçmeyi unutmayın!</div>");

        } else if (city.equalsIgnoreCase("İstanbul")) {
            html.append("<div class='itinerary-day'>")
                    .append("<div class='itinerary-day-title'><i class='fa-solid fa-calendar-day text-primary'></i> 1. Gün: Tarihi Yarımada & Boğaz Büyüsü</div>")
                    .append("<div class='itinerary-slot'>☀️ <strong>Sabah:</strong> Ayasofya, Sultanahmet Meydanı ve Yerebatan Sarnıcı büyüleyici atmosferi.</div>")
                    .append("<div class='itinerary-slot'>🌤️ <strong>Öğle:</strong> Topkapı Sarayı ziyareti ve Tarihi Kapalıçarşı'da geleneksel baharat & kahve keşfi.</div>")
                    .append("<div class='itinerary-slot'>🌙 <strong>Akşam:</strong> Boğaz kıyısında saray manzaralı restoranda akşam yemeği.</div>")
                    .append("</div>");

            if (days >= 2) {
                html.append("<div class='itinerary-day'>")
                        .append("<div class='itinerary-day-title'><i class='fa-solid fa-calendar-day text-primary'></i> 2. Gün: Boğaz Turu & Galata Kültürü</div>")
                        .append("<div class='itinerary-slot'>☀️ <strong>Sabah:</strong> Beşiktaş & Ortaköy sahilinde kahvaltı; özel Boğaz tekne turu.</div>")
                        .append("<div class='itinerary-slot'>🌤️ <strong>Öğle:</strong> Galata Kulesi, Karaköy sanat sokakları ve butik kafeler gezisi.</div>")
                        .append("<div class='itinerary-slot'>🌙 <strong>Akşam:</strong> Tarihi yarımada ışıkları altında Boğaz'da akşam kahvesi ve tatlı keyfi.</div>")
                        .append("</div>");
            }

            if (days >= 3) {
                html.append("<div class='itinerary-day'>")
                        .append("<div class='itinerary-day-title'><i class='fa-solid fa-calendar-day text-primary'></i> 3. Gün: Pera Sanatı & Kadıköy Moda</div>")
                        .append("<div class='itinerary-slot'>☀️ <strong>Sabah:</strong> İstiklal Caddesi tarihi pasajlar ve Dolmabahçe Sarayı görkemi.</div>")
                        .append("<div class='itinerary-slot'>🌤️ <strong>Öğle:</strong> Vapurla Kadıköy'e geçiş; Moda sahilinde yürüyüş ve gurme sokak lezzetleri.</div>")
                        .append("<div class='itinerary-slot'>🌙 <strong>Akşam:</strong> Kadıköy barlar sokağında canlı müzik ve gün batımı kahvesi.</div>")
                        .append("</div>");
            }

            if (days >= 4) {
                html.append("<div class='itinerary-day'>")
                        .append("<div class='itinerary-day-title'><i class='fa-solid fa-calendar-day text-primary'></i> 4. Gün: Prens Adaları (Büyükada) Kaçamağı</div>")
                        .append("<div class='itinerary-slot'>☀️ <strong>Sabah:</strong> Vapurla Büyükada'ya geçiş; begonvilli tarihi ahşap köşkler arasında ada turu.</div>")
                        .append("<div class='itinerary-slot'>🌤️ <strong>Öğle:</strong> Aya Yorgi Kilisesi tepesine tırmanış ve panoramik Marmara Denizi manzarası.</div>")
                        .append("<div class='itinerary-slot'>🌙 <strong>Akşam:</strong> Ada iskelesinde deniz kenarında taze balık ve mezeler eşliğinde veda yemeği.</div>")
                        .append("</div>");
            }

            html.append(
                    "<div class='itinerary-tip'><i class='fa-solid fa-lightbulb text-warning'></i> <strong>Yerel İpucu:</strong> Tarihi Yarımada'da rahat yürüyüş ayakkabıları tercih edin ve Karaköy'de fıstıklı sıcak baklava tatmayı unutmayın.</div>");

        } else {
            html.append("<div class='itinerary-day'>")
                    .append(String.format(
                            "<div class='itinerary-day-title'><i class='fa-solid fa-calendar-day text-primary'></i> 1. Gün: %s Şehrine Varış & Şehir Turu</div>",
                            displayCity))
                    .append("<div class='itinerary-slot'>☀️ <strong>Sabah:</strong> Şehrin tarihi merkezinde yerel lezzetlerle zengin kahvaltı.</div>")
                    .append("<div class='itinerary-slot'>🌤️ <strong>Öğle:</strong> Popüler simge yapılar, müzeler ve doğal parkların keşfi.</div>")
                    .append("<div class='itinerary-slot'>🌙 <strong>Akşam:</strong> Şehrin en beğenilen restoranında yerel mutfak deneyimi.</div>")
                    .append("</div>");

            if (days >= 2) {
                html.append("<div class='itinerary-day'>")
                        .append("<div class='itinerary-day-title'><i class='fa-solid fa-calendar-day text-primary'></i> 2. Gün: Doğa, Aktivite & Dinlenme</div>")
                        .append("<div class='itinerary-slot'>☀️ <strong>Sabah:</strong> Doğa yürüyüşü, göl/deniz kıyısında ferahlatıcı sabah turu.</div>")
                        .append("<div class='itinerary-slot'>🌤️ <strong>Öğle:</strong> Bölgesel el sanatları ve yerel çarşı alışverişi.</div>")
                        .append("<div class='itinerary-slot'>🌙 <strong>Akşam:</strong> Konforlu otelinizde huzurlu akşam dinlenmesi.</div>")
                        .append("</div>");
            }

            if (days >= 3) {
                html.append("<div class='itinerary-day'>")
                        .append("<div class='itinerary-day-title'><i class='fa-solid fa-calendar-day text-primary'></i> 3. Gün: Kültür & Yöresel Gastronomi</div>")
                        .append("<div class='itinerary-slot'>☀️ <strong>Sabah:</strong> Yöresel köy pazarından organik ürünler ve köy kahvaltısı.</div>")
                        .append("<div class='itinerary-slot'>🌤️ <strong>Öğle:</strong> Tarihi ören yerleri ve kentin simge sokaklarının fotoğraflanması.</div>")
                        .append("<div class='itinerary-slot'>🌙 <strong>Akşam:</strong> Yerel lezzetlerle hazırlanan özel akşam ziyafeti.</div>")
                        .append("</div>");
            }

            if (days >= 4) {
                html.append("<div class='itinerary-day'>")
                        .append("<div class='itinerary-day-title'><i class='fa-solid fa-calendar-day text-primary'></i> 4. Gün: Çevre Koylar & Doğa Parkları</div>")
                        .append("<div class='itinerary-slot'>☀️ <strong>Sabah:</strong> Şehre yakın tabiat parkında doğa yürüyüşü ve kuş gözlemi.</div>")
                        .append("<div class='itinerary-slot'>🌤️ <strong>Öğle:</strong> Doğa içinde yöresel kır lokantasında öğle molası.</div>")
                        .append("<div class='itinerary-slot'>🌙 <strong>Akşam:</strong> Gün batımı eşliğinde sakin akşam kahvesi.</div>")
                        .append("</div>");
            }

            html.append(
                    "<div class='itinerary-tip'><i class='fa-solid fa-lightbulb text-warning'></i> <strong>Yerel İpucu:</strong> Bölgenin coğrafi işaretli yöresel tatlarını denemeyi ve fotoğraf makinenizi yanınıza almayı unutmayın.</div>");
        }

        List<Room> matchedRooms = queryRoomsFromDatabase(city, capacity, null, null, null, null, roomType);
        if (matchedRooms.isEmpty() && roomType != null && !roomType.isBlank()) {
            matchedRooms = queryRoomsFromDatabase(city, capacity, null, null, null, null, null);
        }
        if (matchedRooms.isEmpty() && capacity > 0) {
            matchedRooms = queryRoomsFromDatabase(city, 0, null, null, null, null, null);
        }
        if (matchedRooms.isEmpty()) {
            matchedRooms = queryRoomsFromDatabase(null, 0, null, null, null, null, null);
        }

        if (!matchedRooms.isEmpty()) {
            Room top = matchedRooms.get(0);
            String hName = top.getHotel() != null ? top.getHotel().getName() : "Otelimiz";
            String capInfo = capacity > 0 ? String.format("%d Kişilik ", capacity) : "";
            html.append(String.format(
                    "<div class='itinerary-hotel-cta mt-2 p-2 rounded' style='background:#f0fdf4;border:1px solid #bbf7d0;color:#166534;font-size:0.83rem;'><i class='fa-solid fa-hotel text-success'></i> <strong>%sBu Plana En Uygun Konaklama:</strong> %s &mdash; <strong>%s ₺/gece</strong> (Kapasite: %d Kişi &mdash; Aşağıdaki karttan hemen ayırtabilirsiniz)</div>",
                    capInfo, hName, top.getPricePerNight(), top.getCapacity()));
        }

        html.append("</div>");

        List<String> suggestions = new ArrayList<>();
        if (days == 2 || days == 3) {
            suggestions.add(String.format("%d Günlük Yap", days + 1));
        } else if (days >= 4) {
            suggestions.add(String.format("%d Günlük Yap", days - 1));
        }
        suggestions.add("Hemen Rezerve Et");
        suggestions.add("Fiyat Alarmı Kur");
        if (capacity > 0) {
            suggestions.add(String.format("%d Kişilik Odalar", capacity));
        } else {
            suggestions.add("Daha Bütçe Dostu");
        }
        return new ChatResponseDto(html.toString(), matchedRooms,
                suggestions.stream().distinct().limit(4).collect(Collectors.toList()));
    }
}