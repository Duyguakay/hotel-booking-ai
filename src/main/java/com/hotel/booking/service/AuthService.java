package com.hotel.booking.service;

import com.hotel.booking.dto.AuthRequestDto;
import com.hotel.booking.dto.AuthResponseDto;
import com.hotel.booking.dto.RegisterRequestDto;
import com.hotel.booking.entity.Role;
import com.hotel.booking.entity.User;
import com.hotel.booking.repository.HotelRepository;
import com.hotel.booking.repository.UserRepository;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;

@Service
public class AuthService {

    private final UserRepository userRepository;
    private final HotelRepository hotelRepository;
    private final PasswordEncoder passwordEncoder;

    public AuthService(UserRepository userRepository, HotelRepository hotelRepository, PasswordEncoder passwordEncoder) {
        this.userRepository = userRepository;
        this.hotelRepository = hotelRepository;
        this.passwordEncoder = passwordEncoder;
    }

    public AuthResponseDto register(RegisterRequestDto request) {
        if (userRepository.findByEmail(request.getEmail()).isPresent()) {
            throw new RuntimeException("Bu e-posta adresi zaten kullanımda.");
        }

        User user = User.builder()
                .fullName(request.getFullName())
                .username(request.getUsername())
                .email(request.getEmail())
                .password(passwordEncoder.encode(request.getPassword()))
                .role(Role.USER)
                .build();

        User savedUser = userRepository.save(user);

        return AuthResponseDto.builder()
                .id(savedUser.getId())
                .fullName(savedUser.getFullName())
                .username(savedUser.getUsername())
                .email(savedUser.getEmail())
                .role(savedUser.getRole().name())
                .message("Kayıt işlemi başarılı.")
                .build();
    }

    public AuthResponseDto login(AuthRequestDto request) {
        String rawUsername = request.getUsername() != null ? request.getUsername().trim() : null;
        String rawEmail = request.getEmail() != null ? request.getEmail().trim() : null;

        String identifier = (rawUsername != null && !rawUsername.isBlank())
                ? rawUsername
                : (rawEmail != null ? rawEmail : "");

        if (identifier.isBlank()) {
            throw new RuntimeException("Kullanıcı adı veya e-posta boş olamaz.");
        }

        User user = userRepository.findByUsername(identifier)
                .or(() -> userRepository.findByEmail(identifier))
                .orElseThrow(() -> new RuntimeException("Kullanıcı adı/e-posta veya şifre hatalı."));

        if (!passwordEncoder.matches(request.getPassword(), user.getPassword())) {
            throw new RuntimeException("Kullanıcı adı/e-posta veya şifre hatalı.");
        }

        String hotelName = null;
        if (user.getHotelId() != null) {
            hotelName = hotelRepository.findById(user.getHotelId())
                    .map(com.hotel.booking.entity.Hotel::getName)
                    .orElse(null);
        }

        return AuthResponseDto.builder()
                .id(user.getId())
                .fullName(user.getFullName())
                .username(user.getUsername())
                .email(user.getEmail())
                .role(user.getRole().name())
                .hotelId(user.getHotelId())
                .hotelName(hotelName)
                .message("Giriş başarılı.")
                .build();
    }
}