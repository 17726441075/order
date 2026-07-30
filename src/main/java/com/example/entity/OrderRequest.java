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
    /** 基础币名称，例如 BTC、ETH。 */
    private String coin;

    /** 当前用户的多头 API 配置。 */
    private ExchangeApi longApi;

    /** 当前用户的空头 API 配置。 */
    private ExchangeApi shortApi;

    /** 套利模板配置。 */
    private OrderTemplate template;

    /** 当前持仓。 */
    private Position position;

    @Data
    @Builder
    @NoArgsConstructor
    @AllArgsConstructor
    public static class ExchangeApi {
        /** 交易所名称。 */
        private String ee;

        /** 用户 ID。 */
        private Integer ur;

        /** API Key。 */
        private String ak;

        /** Secret Key。 */
        private String ac;

        /** 密码。 */
        private String ap;
    }

    @Data
    @Builder
    @NoArgsConstructor
    @AllArgsConstructor
    public static class OrderTemplate {
        /** 用户 ID。 */
        private Integer ur;

        /** 开仓价差。 */
        private BigDecimal by;

        /** 清仓价差。 */
        private BigDecimal sl;

        /** 净资金费率。 */
        private BigDecimal af;

        /** 开仓总额。 */
        private BigDecimal ta;

        /** 已经总额。 */
        private BigDecimal ta2;

        /** 单笔开仓金额。 */
        private BigDecimal us;

        /** 模板状态。 */
        private Integer ss;
    }

    @Data
    @Builder
    @NoArgsConstructor
    @AllArgsConstructor
    public static class Position {
        /** 用户 ID。 */
        private Integer userId;

        /** 交易所名称。 */
        private String exchange;

        /** 币种。 */
        private String coin;

        /** 持仓方向。 */
        private String side;

        /** 平均开仓价格。 */
        private BigDecimal openPrice;

        /** 持仓数量。 */
        private BigDecimal quantity;

        /** 最新订单 ID。 */
        private Long orderId;

        /** 订单状态。 */
        private String status;

        /** 更新时间。 */
        private Long updatedAt;
    }
}
