package com.example.entity;

import java.math.BigDecimal;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class OrderRequest {
    private String coin;
    private ExchangeApi longApi;
    private ExchangeApi shortApi;
    private OrderTemplate template;

    @Data
    @Builder
    @NoArgsConstructor
    @AllArgsConstructor
    public static class ExchangeApi {
        private String ee;
        private Integer ur;
        private String ak;
        private String ac;
        private String ap;
    }

    @Data
    @Builder
    @NoArgsConstructor
    @AllArgsConstructor
    public static class OrderTemplate {
        private Integer ur;
        private BigDecimal by;
        private BigDecimal sl;
        private BigDecimal af;
        private BigDecimal ta;
        private BigDecimal us;
        private Integer ss;
    }
}
