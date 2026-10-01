package com.hotel.booking.controller;

import com.hotel.booking.dto.ChatRequestDto;
import com.hotel.booking.dto.ChatResponseDto;
import com.hotel.booking.service.AiAssistantService;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping("/api/chat")
@CrossOrigin(origins = "*")
public class AiAssistantController {

    private final AiAssistantService aiAssistantService;

    public AiAssistantController(AiAssistantService aiAssistantService) {
        this.aiAssistantService = aiAssistantService;
    }

    @PostMapping("/ask")
    public ResponseEntity<ChatResponseDto> askAssistant(@RequestBody ChatRequestDto requestDto) {
        ChatResponseDto response = aiAssistantService.askAssistant(requestDto);
        return ResponseEntity.ok(response);
    }
}