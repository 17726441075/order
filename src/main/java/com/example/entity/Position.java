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
public class Position {
    private Integer userId;
    private String coin;
    private String longExchange;
    private String shortExchange;
    private BigDecimal longOpenPrice;
    private BigDecimal shortOpenPrice;
    private BigDecimal longQuantity;
    private BigDecimal shortQuantity;
    private BigDecimal matchedQuantity;
    private BigDecimal openedAmount;
    private String closeRequestId;
    private BigDecimal closeLongOpenPrice;
    private BigDecimal closeShortOpenPrice;
    private BigDecimal closeLongQuantity;
    private BigDecimal closeShortQuantity;
    private BigDecimal longClosePrice;
    private BigDecimal shortClosePrice;
    private Boolean profitRecorded;
    private String status;
    private Long updatedAt;
}
