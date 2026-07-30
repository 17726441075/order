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
            for (int index = 0; index < usersList.size(); index++) {
                OrderRequest user = usersList.get(index);
                try {
                    tryOpenForUser(index, user);
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

    private void tryOpenForUser(int userIndex, OrderRequest user) {
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

        BigDecimal openedAmount = template.getTa2() == null ? BigDecimal.ZERO : template.getTa2();
        OrderRequest.Position position = user.getPosition();
        if (isPending(position)) {
            exchangeOrderService.refreshPositionAsync(user, quote);
            return;
        }

        if (isClosed(position) && openedAmount.signum() > 0) {
            template.setTa2(BigDecimal.ZERO);
            persistUser(userIndex, user);
            log.info("Arbitrage position closed: userId={}, coin={}, ta2=0",
                    template.getUr(), user.getCoin());
            return;
        }

        if (openedAmount.signum() > 0 && position == null) {
            exchangeOrderService.refreshPositionAsync(user, quote);
            return;
        }

        if (hasOpenPosition(position) && shouldClose(template, quote)) {
            closePosition(userIndex, user, quote);
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
        BigDecimal originalAmount = template.getUs();
        markPositionStatus(user, "OPENING");
        persistUser(userIndex, user);
        template.setUs(orderAmount);
        log.info("Arbitrage open before: userId={}, coin={}, longExchange={}, shortExchange={}, openCha={}, "
                        + "netFundingRate={}, amount={}, ta2={}, target={}",
                template.getUr(), user.getCoin(),
                user.getLongApi() == null ? null : user.getLongApi().getEe(),
                user.getShortApi() == null ? null : user.getShortApi().getEe(),
                quote.getOpenCha(), quote.getAllFee(), orderAmount, openedAmount, template.getTa());
        try {
            exchangeOrderService.placeOpenOrders(user, quote);
            template.setTa2(openedAmount.add(orderAmount));
            template.setUs(originalAmount);
            markPositionStatus(user, "OPENING");
            persistUser(userIndex, user);
            log.info("Arbitrage open after: userId={}, coin={}, amount={}, ta2={}",
                    template.getUr(), user.getCoin(), orderAmount, template.getTa2());
        } catch (Exception e) {
            template.setUs(originalAmount);
            markPositionStatus(user, "OPENING");
            persistUser(userIndex, user);
            exchangeOrderService.refreshPositionAsync(user, quote);
            log.error("Arbitrage open failed: userId={}, coin={}, amount={}",
                    template.getUr(), user.getCoin(), orderAmount, e);
        } finally {
            template.setUs(originalAmount);
        }
    }

    private void closePosition(int userIndex, OrderRequest user, Taoli quote) {
        OrderRequest.OrderTemplate template = user.getTemplate();
        OrderRequest.Position position = user.getPosition();
        markPositionStatus(user, "CLOSING");
        persistUser(userIndex, user);
        log.info("Arbitrage close before: userId={}, coin={}, longExchange={}, shortExchange={}, "
                        + "closeCha={}, closeTarget={}, longQuantity={}, shortQuantity={}",
                template.getUr(), user.getCoin(),
                user.getLongApi() == null ? null : user.getLongApi().getEe(),
                user.getShortApi() == null ? null : user.getShortApi().getEe(),
                quote.getCloseCha(), template.getSl(),
                position.getLongQuantity(), position.getShortQuantity());
        try {
            exchangeOrderService.placeCloseOrders(user, quote);
            markPositionStatus(user, "CLOSING");
            persistUser(userIndex, user);
            log.info("Arbitrage close submitted: userId={}, coin={}, longQuantity={}, shortQuantity={}",
                    template.getUr(), user.getCoin(),
                    position.getLongQuantity(), position.getShortQuantity());
        } catch (Exception e) {
            markPositionStatus(user, "CLOSING");
            persistUser(userIndex, user);
            exchangeOrderService.refreshPositionAsync(user, quote);
            log.error("Arbitrage close failed: userId={}, coin={}",
                    template.getUr(), user.getCoin(), e);
        }
    }

    private void persistUser(int userIndex, OrderRequest user) {
        try {
            stringRedisTemplate.opsForList().set(
                    USERS_KEY, userIndex, objectMapper.writeValueAsString(user));
        } catch (Exception e) {
            throw new IllegalStateException("Failed to update Redis user at index " + userIndex, e);
        }
    }

    private static void markPositionStatus(OrderRequest user, String status) {
        OrderRequest.Position position = user.getPosition();
        if (position == null) {
            position = new OrderRequest.Position();
            position.setUserId(user.getTemplate().getUr());
            position.setCoin(user.getCoin());
            position.setLongExchange(user.getLongApi() == null ? null : user.getLongApi().getEe());
            position.setShortExchange(user.getShortApi() == null ? null : user.getShortApi().getEe());
            position.setLongQuantity(BigDecimal.ZERO);
            position.setShortQuantity(BigDecimal.ZERO);
            position.setMatchedQuantity(BigDecimal.ZERO);
            user.setPosition(position);
        }
        position.setStatus(status);
        position.setUpdatedAt(System.currentTimeMillis());
    }

    private static boolean shouldClose(OrderRequest.OrderTemplate template, Taoli quote) {
        return template.getSl() != null && quote.getCloseCha() != null
                && quote.getCloseCha().compareTo(template.getSl()) <= 0;
    }

    private static boolean hasOpenPosition(OrderRequest.Position position) {
        return position != null
                && (isPositive(position.getLongQuantity()) || isPositive(position.getShortQuantity()));
    }

    private static boolean isMatched(OrderRequest.Position position) {
        return position != null && "MATCHED".equalsIgnoreCase(position.getStatus());
    }

    private static boolean isPending(OrderRequest.Position position) {
        return position != null && ("OPENING".equalsIgnoreCase(position.getStatus())
                || "CLOSING".equalsIgnoreCase(position.getStatus()));
    }

    private static boolean isClosed(OrderRequest.Position position) {
        return position != null && "CLOSED".equalsIgnoreCase(position.getStatus())
                && !hasOpenPosition(position);
    }

    private static boolean isPositive(BigDecimal value) {
        return value != null && value.signum() > 0;
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

    private OrderRequest readUser(String value) throws Exception {
        try {
            return objectMapper.readValue(value, OrderRequest.class);
        } catch (Exception ignored) {
            String normalized = value.replace("\r", "").replace("\n", "");
            return objectMapper.readValue(normalized, OrderRequest.class);
        }
    }
}
