package com.hotel.booking.dto;

import jakarta.validation.constraints.NotBlank;
import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

@Data
@NoArgsConstructor
@AllArgsConstructor
public class AuthRequestDto {

    @NotBlank(message = "Kullanıcı adı veya e-posta boş bırakılamaz")
    private String username;

    private String email;

    @NotBlank(message = "Şifre boş bırakılamaz")
    private String password;
}