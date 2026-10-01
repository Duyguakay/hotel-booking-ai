
package com.hotel.booking.dto;

import com.hotel.booking.entity.Room;
import lombok.*;

import java.util.ArrayList;
import java.util.List;

@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class ChatResponseDto {
    private String reply;
    private String message;
    private List<Room> rooms;
    @Builder.Default
    private List<String> suggestions = new ArrayList<>();

    public ChatResponseDto(String message) {
        this.reply = message;
        this.message = message;
        this.suggestions = new ArrayList<>();
    }

    public ChatResponseDto(String message, List<Room> rooms) {
        this.reply = message;
        this.message = message;
        this.rooms = rooms;
        this.suggestions = new ArrayList<>();
    }

    public ChatResponseDto(String message, List<Room> rooms, List<String> suggestions) {
        this.reply = message;
        this.message = message;
        this.rooms = rooms;
        this.suggestions = suggestions != null ? suggestions : new ArrayList<>();
    }
}