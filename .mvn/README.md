# 🏨 Smart Hotel Reservation & AI Assistant API

Spring Boot, MySQL ve **Google Gemini Flash (RAG)** destekli, kurumsal mimari standartlarına uygun olarak geliştirilmiş akıllı otel rezervasyon ve yönetim sistemi.

---

## 📌 Özellikler

* **🔐 Güvenlik & Yetkilendirme:** Spring Security 6 ve JJWT (JSON Web Token) ile stateless kimlik doğrulama, rol tabanlı erişim kontrolü (USER/ADMIN) ve BCrypt şifreleme.
* **🤖 AI Destekli Rezervasyon Asistanı (RAG):** Gemini Flash modeli entegrasyonu; sistem talimatları ve veritabanı bağlamı (context) üzerinden doğal dil girdilerini işleme ve otonom rezervasyon komutlarını (`ACTION_CREATE_RESERVATION`) yürütme.
* **⚡ Eşzamanlılık (Concurrency) Yönetimi:** Çakışan rezervasyon taleplerini önleyen veritabanı kilit mekanizmaları.
* **📊 Katmanlı Mimari (Layered Architecture):** Controller, Service, Repository, DTO ve Entity katmanları arasında net sorumluluk ayrımı.
* **📖 API Dokümantasyonu:** JWT Bearer Token destekli OpenAPI (Swagger UI) entegrasyonu.

---

## 🛠️ Teknolojiler

* **Backend:** Java 17+, Spring Boot 3.x, Spring Data JPA, Spring Security
* **Veritabanı:** MySQL, Hibernate ORM
* **Yapay Zeka:** Google Gemini Flash API (REST Integration)
* **Güvenlik & Token:** JJWT (io.jsonwebtoken), BCrypt
* **Dokümantasyon & Araçlar:** Lombok, OpenAPI 3 (Swagger UI), Maven

---

## 📋 API Uç Noktaları (Endpoints)

| Modül | HTTP Metodu | Endpoint | Erişim | Açıklama |
| :--- | :--- | :--- | :--- | :--- |
| **Auth** | `POST` | `/api/v1/auth/register` | Public | Yeni kullanıcı kaydı |
| **Auth** | `POST` | `/api/v1/auth/login` | Public | Giriş yapma ve JWT Token üretimi |
| **Hotels** | `GET` | `/api/v1/hotels/search` | Public | Şehir ve kriterlere göre otel arama |
| **Rooms** | `GET` | `/api/v1/rooms/available` | Public | Müsait odaları listeleme |
| **Chatbot** | `POST` | `/api/v1/chat` | Public | Gemini AI ile doğal dilde otel sorgulama/işlem |
| **Reservation** | `POST` | `/api/v1/reservations` | Protected (JWT) | Rezervasyon oluşturma |
| **Reservation** | `GET` | `/api/v1/reservations/{id}` | Protected (JWT) | Rezervasyon detayı getirme |

---

## ⚙️ Kurulum ve Çalıştırma

### 1. Gereksinimler
* JDK 17 veya üzeri
* Maven 3.8+
* MySQL 8.x
* Google Gemini API Key

### 2. Yapılandırma
`src/main/resources/application.properties` dosyasındaki veritabanı ve API ayarlarını düzenleyin:

```properties
# Veritabanı Yapılandırması
spring.datasource.url=jdbc:mysql://localhost:3306/booking_db?useSSL=false&serverTimezone=UTC&allowPublicKeyRetrieval=true
spring.datasource.username=root
spring.datasource.password=parolaniz
spring.jpa.hibernate.ddl-auto=update
spring.jpa.show-sql=true

# Gemini AI Yapılandırması
gemini.api.key=YOUR_GEMINI_API_KEY
gemini.api.url=[https://generativelanguage.googleapis.com/v1beta/models/gemini-1.5-flash:generateContent](https://generativelanguage.googleapis.com/v1beta/models/gemini-1.5-flash:generateContent)