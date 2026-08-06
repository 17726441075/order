package com.example.service;

import java.util.List;
import java.util.Locale;

import com.example.entity.OrderRequest;

final class PositionKey {
    private static final String PREFIX = "qiqi:position:";

    private PositionKey() {
    }

    static String of(OrderRequest request) {
        if (request == null || request.getTemplate() == null
                || request.getTemplate().getUr() == null) {
            return null;
        }
        return of(request.getTemplate().getUr(), request.getCoin(),
                request.getLongApi() == null ? null : request.getLongApi().getEe(),
                request.getShortApi() == null ? null : request.getShortApi().getEe());
    }

    static String of(Integer userId, String coin, String longExchange, String shortExchange) {
        if (userId == null || blank(coin) || blank(longExchange) || blank(shortExchange)) {
            return null;
        }
        return PREFIX + userId + ":" + baseCoin(coin) + ":"
                + normalizeExchange(longExchange) + ":" + normalizeExchange(shortExchange);
    }

    static String normalizeExchange(String exchange) {
        String value = exchange.trim().toLowerCase(Locale.ROOT);
        return switch (value) {
            case "hyper" -> "hyperliquid";
            case "gateio" -> "gate";
            default -> value;
        };
    }

    static String baseCoin(String coin) {
        String value = coin.trim().toUpperCase(Locale.ROOT);
        int colon = value.lastIndexOf(':');
        if (colon >= 0 && colon + 1 < value.length()) {
            value = value.substring(colon + 1);
        }
        for (String suffix : List.of("-USDT-SWAP", "-SWAP-USDT", "_USDT",
                "-USDT", "/USDT", "USDT")) {
            if (value.length() > suffix.length() && value.endsWith(suffix)) {
                return value.substring(0, value.length() - suffix.length());
            }
        }
        return value;
    }

    private static boolean blank(String value) {
        return value == null || value.isBlank();
    }
}
