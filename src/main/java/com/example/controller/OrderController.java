package com.example.controller;

import java.util.Map;

import com.example.entity.OrderRequest;
import com.example.service.ExchangeApiValidationService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.ResponseEntity;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RestController;

import tools.jackson.databind.ObjectMapper;

@RestController
@Slf4j
@RequiredArgsConstructor
public class OrderController {
    private static final String USERS_KEY = "qiqi:users";

    private final StringRedisTemplate stringRedisTemplate;
    private final ObjectMapper objectMapper;
    private final ExchangeApiValidationService exchangeApiValidationService;

    @PostMapping("/order")
    public ResponseEntity<Map<String, Object>> order(@RequestBody(required = false) OrderRequest request) {
        if (request == null) {
            return ResponseEntity.badRequest().body(Map.of(
                    "success", false,
                    "message", "request JSON cannot be empty"));
        }
        log.info("order request received: {}", request);

        try {
            exchangeApiValidationService.validate(request);
        } catch (Exception e) {
            log.warn("order request rejected: coin={}", request.getCoin(), e);
            return ResponseEntity.badRequest().body(Map.of(
                    "success", false,
                    "message", e.getMessage()));
        }

        try {
            stringRedisTemplate.opsForList().rightPush(USERS_KEY, objectMapper.writeValueAsString(request));
        } catch (Exception e) {
            log.error("failed to save order user to Redis list {}", USERS_KEY, e);
            return ResponseEntity.internalServerError().body(Map.of(
                    "success", false,
                    "message", "failed to save order user"));
        }

        return ResponseEntity.ok(Map.of(
                "success", true,
                "message", "order request received"));
    }

}
