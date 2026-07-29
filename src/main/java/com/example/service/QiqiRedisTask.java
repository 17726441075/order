package com.example.service;

import java.util.ArrayList;

import lombok.Getter;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import org.springframework.util.StringUtils;

import com.example.entity.Taoli;

import tools.jackson.core.type.TypeReference;
import tools.jackson.databind.ObjectMapper;

@Slf4j
@Component
@RequiredArgsConstructor
public class QiqiRedisTask {
    private static final String KEY = "qiqi";
    private static final TypeReference<ArrayList<Taoli>> TAOLI_LIST_TYPE = new TypeReference<>() {
    };

    private final StringRedisTemplate stringRedisTemplate;
    private final ObjectMapper objectMapper;
    @Getter
    private volatile ArrayList<Taoli> taoliList = new ArrayList<>();

    @Scheduled(fixedRate = 5000)
    public void refreshQiqi() {
        try {
            String value = stringRedisTemplate.opsForValue().get(KEY);
            if (!StringUtils.hasText(value)) {
                taoliList = new ArrayList<>();
                log.info("Taoli list size: {}", taoliList.size());
                return;
            }
            taoliList = objectMapper.readValue(value, TAOLI_LIST_TYPE);
            log.info("Taoli list size: {}", taoliList.size());
        } catch (Exception e) {
            log.error("Failed to read Redis key {}", KEY, e);
        }
    }
}
