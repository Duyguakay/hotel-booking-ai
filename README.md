# 🏨 BookingAI - Akıllı Otel Rezervasyon & Yapay Zeka Asistan Sistemi
### 🎓 Staj Projesi Teslim Dokümantasyonu

BookingAI; modern otel yönetimi, dinamik oda rezervasyonları, Groq LLM tabanlı yapay zeka sohbet asistanı, otomatik rezervasyon/fiyat takip motoru ve Telegram anlık bildirim sistemi içeren kapsamlı ve kurumsal bir **Spring Boot 3** projesidir.

---

## 📌 1. Projenin Amacı

Bu projenin temel amacı; klasik otel rezervasyon sistemlerinin ötesine geçerek, kullanıcılara yapay zeka destekli akıllı bir tatil planlama ve rezervasyon deneyimi sunmaktır. Sistem:
- Kullanıcıların bütçelerine, istedikleri özelliklere (havuz, jakuzi, şömine vb.) ve kişi sayısına en uygun otel ve odaları **LLM (Büyük Dil Modeli)** ile doğal dilde analiz edip önerir.
- Fiyat takibi yaparak, istenen oda hedef bütçeye indiğinde veya uygunluk açıldığında **otomatik rezervasyon** gerçekleştirir.
- Kullanıcıları **Telegram Botu ve SMS** üzerinden anlık olarak bilgilendirir.
- Otel yöneticileri ve sistem yöneticileri için rol tabanlı yönetim paneli altyapısı sağlar.

---

## 🛠️ 2. Kullanılan Teknolojiler

| Katman / Bileşen | Teknoloji / Kütüphane | Sürüm | Açıklama |
|---|---|---|---|
| **Dil** | Java | 17 (LTS) | Modern Java özellikleri ve Records |
| **Framework** | Spring Boot | 3.3.3 | REST API, Bağımlılık Yönetimi |
| **Güvenlik** | Spring Security & JJWT | 0.11.5 | Stateless JWT Tabanlı Kimlik Doğrulama |
| **Veritabanı & ORM** | MySQL 8.0+, Spring Data JPA, Hibernate | 3.3.3 | İlişkisel Veritabanı ve Dinamik JPA Specification |
| **Yapay Zeka (AI)** | Groq Cloud API | LLaMA 3.3 70B Versatile | Yüksek Hızlı Doğal Dil İşleme & Öneri Motoru |
| **Bildirimler** | Telegram Bot API & Mock SMS | - | Anlık Mobil & SMS Bildirim Entegrasyonu |
| **API Dokümantasyonu** | SpringDoc OpenAPI (Swagger UI) | 2.6.0 | Canlı ve Etkileşimli API Dokümantasyonu |
| **Ön Yüz (Frontend)** | HTML5, CSS3, Vanilla JavaScript SPA | - | Responsive, Glassmorphism Tasarımlı Web UI |
| **Yardımcı Araçlar** | Lombok, Jakarta Bean Validation, Maven | - | Boilerplate kod azaltma ve doğrulama |

---

## 💻 3. Sistem Gereksinimleri

Projeyi başka bir ortamda derlemek ve çalıştırmak için aşağıdaki bileşenlerin yüklü olması gerekmektedir:

- **JDK:** Java Development Kit 17 veya üzeri (`java -version`)
- **Veritabanı:** MySQL Server 8.0 veya üzeri
- **Derleme Aracı:** Maven 3.8+ (veya proje kök dizinindeki `./mvnw` wrapper aracı)
- **İnternet Bağlantısı:** Maven bağımlılıklarını indirmek ve Groq AI / Telegram API çağrıları için gereklidir.

---

## 🗄️ 4. Veritabanı Kurulumu

1. MySQL sunucunuza bağlanın (MySQL Workbench, DBeaver veya MySQL CLI).
2. Projenin kullanacağı veritabanını aşağıdaki SQL komutu ile oluşturun:

```sql
CREATE DATABASE hotel_booking_db CHARACTER SET utf8mb4 COLLATE utf8mb4_unicode_ci;
```

> **Not:** Hibernate `ddl-auto=update` ayarı sayesinde; tablolar, ilişkiler, yabancı anahtarlar (foreign keys) ve başlangıç verileri (`DataInitializer` sınıfı) uygulama ilk başlatıldığında otomatik olarak oluşturulacaktır.

---

## ⚙️ 5. Gerekli Environment Variable'lar ve Konfigürasyon

Uygulamanın ayarları `src/main/resources/application.properties` dosyasında yönetilmektedir. Dilerseniz ortam değişkenleri (Environment Variables) ile de bu değerleri geçersiz kılabilirsiniz:

| Özellik / Parametre | Açıklama | Örnek / Placeholder Değer |
|---|---|---|
| `server.port` | Uygulamanın çalışacağı port | `8085` |
| `spring.datasource.url` | MySQL JDBC bağlantı URL'i | `jdbc:mysql://localhost:3306/hotel_booking_db?useSSL=false&serverTimezone=UTC&allowPublicKeyRetrieval=true` |
| `spring.datasource.username` | MySQL veritabanı kullanıcı adı | `root` |
| `spring.datasource.password` | MySQL veritabanı şifresi | `BURAYA_MYSQL_SIFRENIZI_YAZIN` |
| `groq.api.key` | Groq AI Cloud API Anahtarı | `BURAYA_GROQ_API_KEY_YAZIN` *(Örn: gsk_...)* |
| `groq.api.url` | Groq AI Chat Completions Endpoint'i | `https://api.groq.com/openai/v1/chat/completions` |
| `telegram.bot.token` | Telegram Bot Token (Opsiyonel) | `BURAYA_TELEGRAM_BOT_TOKEN_YAZIN` |
| `telegram.chat.id` | Telegram Bildirim Chat ID (Opsiyonel) | `BURAYA_TELEGRAM_CHAT_ID_YAZIN` |

---

## 🚀 6. Uygulamanın Kurulumu ve Çalıştırılması

### Adım 1: Projeyi İndirin ve Proje Dizinine Geçin
```bash
cd BookingAI_Staj_Teslim
```

### Adım 2: Konfigürasyon Dosyasını Kontrol Edin
`src/main/resources/application.properties` dosyasını bir metin düzenleyici ile açıp MySQL kullanıcı adı ve şifrenizi girin.

### Adım 3: Uygulamayı Derleyin ve Başlatın

**macOS / Linux:**
```bash
./mvnw clean spring-boot:run
```

**Windows:**
```cmd
mvnw.cmd clean spring-boot:run
```

Veya sisteminizde yüklü Maven ile:
```bash
mvn clean spring-boot:run
```

Uygulama başarıyla başlatıldığında terminalde `Tomcat started on port 8085 (http)` mesajını göreceksiniz.

---

## 🌐 7. Uygulama ve API Erişimi

- 🖥️ **Web Arayüzü:** [http://localhost:8085/](http://localhost:8085/) *(Tarayıcınızda açtığınızda doğrudan modern SPA rezervasyon ekranı karşılar)*
- 📑 **Swagger UI (API Test Paneli):** [http://localhost:8085/swagger-ui.html](http://localhost:8085/swagger-ui.html)
- 📋 **OpenAPI v3 JSON:** [http://localhost:8085/v3/api-docs](http://localhost:8085/v3/api-docs)

---

## 🔑 8. Hazır Test Kullanıcıları

Uygulama başladığında `DataInitializer` sınıfı tarafından otomatik yüklenen örnek hesaplar:

| Kullanıcı Adı | Şifre | Rol | Kapsam / Yetki |
|---|---|---|---|
| `zeynep` | `123456` | `ROLE_USER` | Standart Müşteri (Oda Arama, Rezervasyon, Favoriler, AI Chat) |
| `admin` | `123456` | `ROLE_ADMIN` | Genel Sistem Yöneticisi (Tüm otel ve rezervasyonları yönetme) |
| `sapanca_manager` | `123456` | `ROLE_HOTEL_MANAGER` | Sapanca Doğa Bungalov Müdürü |
| `antalya_manager` | `123456` | `ROLE_HOTEL_MANAGER` | Antalya Grand Azure Resort Müdürü |
| `fethiye_manager` | `123456` | `ROLE_HOTEL_MANAGER` | Fethiye Sunset Luxury Villa Müdürü |
| `bodrum_manager` | `123456` | `ROLE_HOTEL_MANAGER` | Bodrum Blue Aegean Hotel Müdürü |
| `istanbul_manager`| `123456` | `ROLE_HOTEL_MANAGER` | İstanbul Bosphorus Palace Müdürü |

---

## ✨ 9. Temel Modüller ve Özellikler

1. **🤖 AI Seyahat Asistanı (`AiAssistantService`):**
   - Groq API üzerinden LLaMA 3.3 modeline bağlanır.
   - Kullanıcının bütçe, şehir, kişi sayısı, havuz/şömine gibi özel isteklerini prompt mühendisliği ile işler ve uygun odaları eşleştirir.
2. **⚡ Otomatik Rezervasyon & Fiyat Takip Motoru (`AutoReservationService`):**
   - Belirli bir otel ve oda için hedef fiyat tanımlanmasını sağlar.
   - Fiyat düştüğünde veya uygunluk açıldığında arka planda otomatik rezervasyon kaydı oluşturur.
3. **📲 Telegram & SMS Bildirim Servisi (`SmsNotificationService`):**
   - Rezervasyon durum güncellemelerinde kullanıcıya SMS ve Telegram üzerinden anlık mesaj gönderir.
4. **🔐 JWT ve Spring Security Mimarisi (`SecurityConfig`, `JwtService`):**
   - Bütün endpoint'ler rol bazlı korunur. Token doğrulama `JwtAuthenticationFilter` filtresi üzerinden yürütülür.
5. **🏨 Dinamik Arama & Filtreleme (`RoomSpecification`):**
   - Şehir, kapasite, min/max fiyat ve oda özelliklerine göre dinamik JPA Criteria sorguları yürütülür.
6. **❤️ Favoriler (Wishlist) Yönetimi (`WishlistService`):**
   - Kullanıcılar beğendikleri otel ve odaları tek tıkla favorilerine ekleyebilir.

---

## 📁 10. Proje Klasör Yapısı

```
BookingAI_Staj_Teslim/
├── src/
│   ├── main/
│   │   ├── java/com/hotel/booking/
│   │   │   ├── config/              # Güvenlik, JWT Filtresi, Swagger ve Seed Data ayarları
│   │   │   ├── controller/          # REST Controller sınıfları (Auth, Hotel, Room, AI, Chat...)
│   │   │   ├── dto/                 # İstek / Yanıt Veri Transfer Nesneleri (DTOs)
│   │   │   ├── entity/              # JPA Varlık Sınıfları (Hotel, Room, Reservation, User...)
│   │   │   ├── exception/           # Global Exception Handler ve Hata Yanıtları
│   │   │   ├── repository/          # Spring Data JPA Repository ve Criteria Specification'ları
│   │   │   ├── service/             # İş Mantığı Servisleri (AI, Rezervasyon, Bildirim, vb.)
│   │   │   └── BookingApplication.java # Spring Boot Ana Giriş Noktası
│   │   └── resources/
│   │       ├── static/
│   │       │   └── index.html       # Web SPA Kullanıcı Arayüzü (HTML/CSS/JS)
│   │       └── application.properties # Veritabanı ve API Anahtarları Konfigürasyonu
│   └── test/                        # Birim / Entegrasyon Test Sınıfları
├── .mvn/wrapper/                    # Maven Wrapper Dosyaları
├── mvnw                             # Linux / macOS Maven Başlatıcı Script
├── mvnw.cmd                         # Windows Maven Başlatıcı Script
├── pom.xml                          # Maven Bağımlılıkları ve Proje Yapılandırması
├── .gitignore                       # Git İhmal Listesi
└── README.md                        # Detaylı Proje Dokümantasyonu
```

---

## 📄 Lisans ve Teslim Bilgisi
Bu proje **Staj Raporu ve Akademik Değerlendirme** kapsamında teslim edilmek üzere hazırlanmıştır.
Tüm hakları saklıdır.
