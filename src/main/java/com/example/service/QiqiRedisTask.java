package com.example.service;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.math.BigDecimal;
import java.util.concurrent.atomic.AtomicBoolean;

import lombok.Getter;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import org.springframework.util.StringUtils;

import com.example.entity.Taoli;
import com.example.entity.OrderRequest;
import com.example.entity.Position;

import tools.jackson.databind.ObjectMapper;

@Slf4j
@Component
@RequiredArgsConstructor
public class QiqiRedisTask {
    private static final String KEY = "qiqi";
    private static final String USERS_KEY = "qiqi:users";

    private final StringRedisTemplate stringRedisTemplate;
    private final ObjectMapper objectMapper;
    private final ExchangeOrderService exchangeOrderService;
    private final AtomicBoolean processing = new AtomicBoolean();
    @Getter
    private volatile ArrayList<Taoli> taoliList = new ArrayList<>();
    @Getter
    private volatile ArrayList<OrderRequest> usersList = new ArrayList<>();

    @Scheduled(fixedRate = 200)
    public void refreshQiqi() {
        try {
            String value = stringRedisTemplate.opsForValue().get(KEY);
            if (!StringUtils.hasText(value)) {
                taoliList = new ArrayList<>();
                usersList = readUsers();
                processUsers();
                return;
            }
            taoliList = new ArrayList<>(Arrays.asList(objectMapper.readValue(value, Taoli[].class)));
            usersList = readUsers();
            processUsers();
        } catch (Exception e) {
            log.error("Failed to read Redis key {}", KEY, e);
        }
    }

    private void processUsers() {
        if (!processing.compareAndSet(false, true)) {
            log.warn("Previous arbitrage processing is still running");
            return;
        }
        try {
            for (OrderRequest user : usersList) {
                try {
                    tryOpenForUser(user);
                } catch (Exception e) {
                    log.error("Arbitrage user processing failed: userId={}, coin={}; continue with next user",
                            user == null || user.getTemplate() == null ? null : user.getTemplate().getUr(),
                            user == null ? null : user.getCoin(), e);
                }
            }
        } finally {
            processing.set(false);
        }
    }

    private void tryOpenForUser(OrderRequest user) {
        if (user == null || user.getTemplate() == null || !StringUtils.hasText(user.getCoin())) {
            return;
        }
        OrderRequest.OrderTemplate template = user.getTemplate();
        if (template.getSs() != null && template.getSs() != 1) {
            return;
        }

        Taoli quote = findQuote(user);
        if (quote == null) {
            return;
        }

        Position position = loadRuntimePosition(user);
        BigDecimal openedAmount = openedAmount(position);
        if (position != null && position.getOpenedAmount() == null && template.getTa2() != null) {
            position.setOpenedAmount(template.getTa2());
            openedAmount = position.getOpenedAmount();
            persistPosition(user, position);
        } else if (position == null && template.getTa2() != null) {
            openedAmount = template.getTa2();
            // 兼容旧数据：旧版 ta2 仅用于等待一次仓位同步，之后不再读取或回写模板。
            openedAmount = template.getTa2();
        }
        if (isPending(position)) {
            exchangeOrderService.refreshPositionAsync(user, quote);
            return;
        }

        if (isClosed(position)) {
            if (openedAmount.signum() > 0) {
                position.setOpenedAmount(BigDecimal.ZERO);
                openedAmount = BigDecimal.ZERO;
                log.info("Arbitrage position closed: userId={}, coin={}, openedAmount=0",
                        template.getUr(), user.getCoin());
            }
            if (!canReopenAfterClose(template, quote)) {
                persistPosition(user, position);
                return;
            }
            position = null;
        }

        if (openedAmount.signum() > 0 && position == null) {
            exchangeOrderService.refreshPositionAsync(user, quote);
            return;
        }

        if (hasOpenPosition(position) && shouldClose(template, quote)) {
            closePosition(user, position, quote);
            return;
        }

        if (template.getSl() != null && quote.getCloseCha() == null) {
            return;
        }

        if (shouldClose(template, quote)) {
            return;
        }

        if (hasOpenPosition(position) && !isMatched(position)) {
            return;
        }

        if (template.getBy() == null || template.getAf() == null || template.getTa() == null
                || template.getUs() == null || template.getUs().signum() <= 0
                || quote.getOpenCha() == null) {
            return;
        }
        if (quote.getOpenCha().compareTo(template.getBy()) < 0
                || (quote.getAllFee() != null && quote.getAllFee().compareTo(template.getAf()) < 0)) {
            return;
        }

        BigDecimal remainingAmount = template.getTa().subtract(openedAmount);
        if (remainingAmount.signum() <= 0) {
            return;
        }

        BigDecimal orderAmount = template.getUs().min(remainingAmount);
        position = markPositionStatus(user, position, "OPENING");
        persistPosition(user, position);
        log.info("Arbitrage open before: userId={}, coin={}, longExchange={}, shortExchange={}, openCha={}, "
                        + "netFundingRate={}, amount={}, openedAmount={}, target={}",
                template.getUr(), user.getCoin(),
                user.getLongApi() == null ? null : user.getLongApi().getEe(),
                user.getShortApi() == null ? null : user.getShortApi().getEe(),
                quote.getOpenCha(), quote.getAllFee(), orderAmount, openedAmount, template.getTa());
        try {
            exchangeOrderService.placeOpenOrders(user, quote, orderAmount);
            Position latestPosition = loadRuntimePosition(user);
            if (latestPosition != null) {
                position = latestPosition;
            }
            position.setOpenedAmount(openedAmount.add(orderAmount));
            position = markPositionStatus(user, position, "OPENING");
            persistPosition(user, position);
            log.info("Arbitrage open after: userId={}, coin={}, amount={}, openedAmount={}",
                    template.getUr(), user.getCoin(), orderAmount, position.getOpenedAmount());
        } catch (Exception e) {
            position = markPositionStatus(user, position, "OPENING");
            persistPosition(user, position);
            exchangeOrderService.refreshPositionAsync(user, quote);
            log.error("Arbitrage open failed: userId={}, coin={}, amount={}",
                    template.getUr(), user.getCoin(), orderAmount, e);
        }
    }

    private void closePosition(OrderRequest user, Position position, Taoli quote) {
        OrderRequest.OrderTemplate template = user.getTemplate();
        position = markPositionStatus(user, position, "CLOSING");
        persistPosition(user, position);
        log.info("Arbitrage close before: userId={}, coin={}, longExchange={}, shortExchange={}, "
                        + "closeCha={}, closeTarget={}, longQuantity={}, shortQuantity={}",
                template.getUr(), user.getCoin(),
                user.getLongApi() == null ? null : user.getLongApi().getEe(),
                user.getShortApi() == null ? null : user.getShortApi().getEe(),
                quote.getCloseCha(), template.getSl(),
                position.getLongQuantity(), position.getShortQuantity());
        try {
            exchangeOrderService.placeCloseOrders(user, position, quote);
            Position latestPosition = loadRuntimePosition(user);
            if (latestPosition != null) {
                position = latestPosition;
            }
            position = markPositionStatus(user, position, "CLOSING");
            persistPosition(user, position);
            log.info("Arbitrage close submitted: userId={}, coin={}, longQuantity={}, shortQuantity={}",
                    template.getUr(), user.getCoin(),
                    position.getLongQuantity(), position.getShortQuantity());
        } catch (Exception e) {
            position = markPositionStatus(user, position, "CLOSING");
            persistPosition(user, position);
            exchangeOrderService.refreshPositionAsync(user, quote);
            log.error("Arbitrage close failed: userId={}, coin={}",
                    template.getUr(), user.getCoin(), e);
        }
    }

    private void persistPosition(OrderRequest user, Position position) {
        try {
            if (user.getTemplate() == null || user.getTemplate().getUr() == null
                    || position == null) {
                return;
            }
            String key = PositionKey.of(user);
            if (key == null) {
                return;
            }
            stringRedisTemplate.opsForValue().set(key, objectMapper.writeValueAsString(position));
        } catch (Exception e) {
            throw new IllegalStateException("Failed to update Redis position for user "
                    + (user.getTemplate() == null ? null : user.getTemplate().getUr()), e);
        }
    }

    private static Position markPositionStatus(OrderRequest user,
            Position position, String status) {
        if (position == null) {
            position = new Position();
            position.setUserId(user.getTemplate().getUr());
            position.setCoin(user.getCoin());
            position.setLongExchange(user.getLongApi() == null ? null : user.getLongApi().getEe());
            position.setShortExchange(user.getShortApi() == null ? null : user.getShortApi().getEe());
            position.setLongQuantity(BigDecimal.ZERO);
            position.setShortQuantity(BigDecimal.ZERO);
            position.setMatchedQuantity(BigDecimal.ZERO);
            position.setOpenedAmount(BigDecimal.ZERO);
        }
        position.setStatus(status);
        position.setUpdatedAt(System.currentTimeMillis());
        return position;
    }

    private static boolean shouldClose(OrderRequest.OrderTemplate template, Taoli quote) {
        return template.getSl() != null && quote.getCloseCha() != null
                && quote.getCloseCha().compareTo(template.getSl()) <= 0;
    }

    private static boolean canReopenAfterClose(OrderRequest.OrderTemplate template, Taoli quote) {
        return template.getSl() == null || (quote.getCloseCha() != null
                && quote.getCloseCha().compareTo(template.getSl()) > 0);
    }

    private static boolean hasOpenPosition(Position position) {
        return position != null
                && (isPositive(position.getLongQuantity()) || isPositive(position.getShortQuantity()));
    }

    private static boolean isMatched(Position position) {
        return position != null && "MATCHED".equalsIgnoreCase(position.getStatus());
    }

    private static boolean isPending(Position position) {
        return position != null && ("OPENING".equalsIgnoreCase(position.getStatus())
                || "CLOSING".equalsIgnoreCase(position.getStatus()));
    }

    private static boolean isClosed(Position position) {
        return position != null && "CLOSED".equalsIgnoreCase(position.getStatus())
                && !hasOpenPosition(position);
    }

    private static boolean isPositive(BigDecimal value) {
        return value != null && value.signum() > 0;
    }

    private static BigDecimal openedAmount(Position position) {
        return position == null || position.getOpenedAmount() == null
                ? BigDecimal.ZERO : position.getOpenedAmount();
    }

    private Taoli findQuote(OrderRequest user) {
        String longExchange = user.getLongApi() == null ? null : user.getLongApi().getEe();
        String shortExchange = user.getShortApi() == null ? null : user.getShortApi().getEe();
        for (Taoli quote : taoliList) {
            if (quote != null && same(quote.getCoin(), user.getCoin())
                    && same(quote.getLongExchange(), longExchange)
                    && same(quote.getShortExchange(), shortExchange)) {
                return quote;
            }
        }
        return null;
    }

    private boolean same(String left, String right) {
        return left != null && right != null
                && normalizeExchange(left).equals(normalizeExchange(right));
    }

    private String normalizeExchange(String exchange) {
        String value = exchange.trim().toLowerCase();
        return switch (value) {
            case "hyper" -> "hyperliquid";
            case "gateio" -> "gate";
            default -> value;
        };
    }

    private ArrayList<OrderRequest> readUsers() throws Exception {
        List<String> values = stringRedisTemplate.opsForList().range(USERS_KEY, 0, -1);
        ArrayList<OrderRequest> result = new ArrayList<>();
        if (values == null) {
            return result;
        }
        for (int index = 0; index < values.size(); index++) {
            String value = values.get(index);
            if (StringUtils.hasText(value)) {
                try {
                    result.add(readUser(value));
                } catch (Exception e) {
                    log.error("Failed to parse user JSON in Redis list at index {}; skip this user", index, e);
                }
            }
        }
        return result;
    }

    private Position loadRuntimePosition(OrderRequest user) {
        if (user.getTemplate() == null || user.getTemplate().getUr() == null) {
            return null;
        }
        String key = PositionKey.of(user);
        if (key == null) {
            return null;
        }
        String value = stringRedisTemplate.opsForValue().get(key);
        if (!StringUtils.hasText(value)) {
            return null;
        }
        try {
            Position position = objectMapper.readValue(value, Position.class);
            if (same(position.getCoin(), user.getCoin())) {
                return position;
            }
        } catch (Exception e) {
            log.warn("Failed to read Redis position for user {}; ignore position",
                    user.getTemplate().getUr(), e);
        }
        return null;
    }

    private OrderRequest readUser(String value) throws Exception {
        try {
            return objectMapper.readValue(value, OrderRequest.class);
        } catch (Exception ignored) {
            String normalized = value.replace("\r", "").replace("\n", "");
            return objectMapper.readValue(normalized, OrderRequest.class);
        }
    }
}
