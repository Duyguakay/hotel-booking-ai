package com.hotel.booking.controller;

import com.hotel.booking.dto.ChatRequestDto;
import com.hotel.booking.dto.ChatResponseDto;
import com.hotel.booking.service.AiAssistantService;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping("/api/chat")
@CrossOrigin(origins = "*")
public class ChatController {

    private final AiAssistantService aiAssistantService;

    public ChatController(AiAssistantService aiAssistantService) {
        this.aiAssistantService = aiAssistantService;
    }

    @PostMapping
    public ResponseEntity<ChatResponseDto> chat(@RequestBody ChatRequestDto requestDto) {
        ChatResponseDto response = aiAssistantService.askAssistant(requestDto);
        return ResponseEntity.ok(response);
    }
}