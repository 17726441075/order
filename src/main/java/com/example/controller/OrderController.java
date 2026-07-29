package com.example.controller;

import java.util.Map;

import com.example.entity.OrderRequest;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RestController;

@RestController
@Slf4j
public class OrderController {

    @PostMapping("/order")
    public ResponseEntity<Map<String, Object>> order(@RequestBody(required = false) OrderRequest request) {
        log.info("order request received: {}", request);
        if (request == null) {
            return ResponseEntity.badRequest().body(Map.of(
                    "success", false,
                    "message", "request JSON cannot be empty"));
        }
        return ResponseEntity.ok(Map.of(
                "success", true,
                "message", "order request received"));
    }
}
