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
        @Deprecated
        private BigDecimal ta2;

        /** 单笔最小开仓金额。 */
        private BigDecimal min;

        /** 单笔最大开仓金额。 */
        private BigDecimal max;

        /** 模板状态。 */
        private Integer ss;
    }

}
