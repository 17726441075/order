package com.example.test;

import java.util.Map;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.ResponseEntity;
import org.springframework.util.StringUtils;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@Slf4j
@RequiredArgsConstructor
@RequestMapping("/test")
public class TestOrderController {
    private final TestOrderService testOrderService;

    @PostMapping("/order")
    public ResponseEntity<Map<String, Object>> order(@RequestBody(required = false) String json) {
        log.info("test user order request received: {}", json);
        if (!StringUtils.hasText(json)) {
            return ResponseEntity.badRequest().body(Map.of("success", false, "message", "套利模板JSON不能为空"));
        }
        testOrderService.order(json);
        return ResponseEntity.ok(Map.of("success", true, "message", "test用户下单请求已接收"));
    }
}
