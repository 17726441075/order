package com.example.entity;

import java.math.BigDecimal;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

/** 一条套利行情数据。 */
@Builder
@Data
@AllArgsConstructor
@NoArgsConstructor
public class Taoli {
    private String coin;                    // 基础币种

    private String longExchange;            // 多头交易所

    private String shortExchange;           // 空头交易所

    private BigDecimal openCha;             // 开仓价差

    private BigDecimal closeCha;            // 清仓价差

    private BigDecimal longCha;             // 多头盘差

    private BigDecimal shortCha;            // 空头盘差

    private BigDecimal allFee;              // 净资金费率

    private BigDecimal longFee;             // 多头手续费

    private BigDecimal shortFee;            // 空头手续费

    private BigDecimal longRate;            // 多头资金费率

    private BigDecimal shortRate;           // 空头资金费率

    private BigDecimal longMaxFee;          // 多头最大费率

    private BigDecimal shortMaxFee;         // 空头最大费率

    private BigDecimal longIndexCha;        // 多头指数价差

    private BigDecimal shortIndexCha;       // 空头指数价差

    private BigDecimal longTurnover;        // 多头成交额

    private BigDecimal shortTurnover;       // 空头成交额

    private BigDecimal longLast;            // 多头最新价

    private BigDecimal shortLast;           // 空头最新价

    private BigDecimal longLot;             // 多头合约面值

    private BigDecimal shortLot;            // 空头合约面值

    private BigDecimal longMinSz;           // 多头最小数量

    private BigDecimal shortMinSz;          // 空头最小数量

    private BigDecimal longMutil;           // 多头合约乘数

    private BigDecimal shortMutil;          // 空头合约乘数

    private BigDecimal longIndex;           // 多头指数价

    private BigDecimal shortIndex;          // 空头指数价

    private BigDecimal longMark;            // 多头标记价

    private BigDecimal shortMark;           // 空头标记价

    private BigDecimal longAskPce;          // 多头卖一价

    private BigDecimal shortAskPce;         // 空头卖一价

    private BigDecimal longAskSz;           // 多头卖一量

    private BigDecimal shortAskSz;          // 空头卖一量

    private BigDecimal longBidPce;          // 多头买一价

    private BigDecimal shortBidPce;         // 空头买一价

    private BigDecimal longBidSz;           // 多头买一量

    private BigDecimal shortBidSz;          // 空头买一量
}
