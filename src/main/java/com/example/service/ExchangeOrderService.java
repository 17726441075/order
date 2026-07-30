package com.example.service;

import java.io.IOException;
import java.math.BigDecimal;
import java.math.BigInteger;
import java.math.MathContext;
import java.math.RoundingMode;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.Base64;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;

import jakarta.annotation.PreDestroy;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.msgpack.core.MessageBufferPacker;
import org.msgpack.core.MessagePack;
import org.msgpack.core.MessagePacker;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Service;
import org.web3j.crypto.ECKeyPair;
import org.web3j.crypto.Sign;
import org.web3j.utils.Numeric;

import com.example.entity.OrderRequest;
import com.example.entity.Position;
import com.example.entity.Taoli;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

@Service
@Slf4j
@RequiredArgsConstructor
public class ExchangeOrderService {
    private static final String ZERO_ADDRESS = "0x0000000000000000000000000000000000000000";
    private static final DateTimeFormatter OKX_TIME = DateTimeFormatter
            .ofPattern("yyyy-MM-dd'T'HH:mm:ss.SSS'Z'").withZone(ZoneOffset.UTC);
    private static final HttpClient HTTP_CLIENT = HttpClient.newBuilder()
            .connectTimeout(Duration.ofSeconds(8)).build();
    private static final long HYPER_INFO_CACHE_MILLIS = 1_000L;
    private static final long HYPER_INFO_RETRY_MILLIS = 3_000L;
    private static final long POSITION_QUERY_INITIAL_DELAY_MILLIS = 2_000L;
    private static final long POSITION_QUERY_INTERVAL_MILLIS = 2_000L;
    private static final long POSITION_QUERY_RETRY_MILLIS = 30_000L;
    private static final int IBKR_ORDER_QUERY_MAX_ATTEMPTS = 60;
    private static final Set<String> POSITION_QUERY_EXCHANGES = Set.of(
            "okx", "binance", "bybit", "bitget", "gate", "gateio", "hyper", "hyperliquid");
    private final ObjectMapper objectMapper;
    private final StringRedisTemplate stringRedisTemplate;
    private final Map<String, AssetCache> hyperliquidAssetCache = new ConcurrentHashMap<>();
    private final Set<String> pendingPositionQueries = ConcurrentHashMap.newKeySet();
    private final Map<String, Long> positionQueryRetryAt = new ConcurrentHashMap<>();
    private final Object positionQueryRateLock = new Object();
    private final ExecutorService positionQueryExecutor = Executors.newSingleThreadExecutor(runnable -> {
        Thread thread = new Thread(runnable, "position-query-1");
        thread.setDaemon(true);
        return thread;
    });
    private final Set<String> pendingIbkrOrderQueries = ConcurrentHashMap.newKeySet();
    private final ExecutorService ibkrOrderQueryExecutor = Executors.newSingleThreadExecutor(runnable -> {
        Thread thread = new Thread(runnable, "ibkr-order-query-1");
        thread.setDaemon(true);
        return thread;
    });
    private long nextPositionQueryAt;
    private volatile long hyperliquidInfoRetryAt;

    @Value("${ibkr.base-url}")
    private String ibkrBaseUrl;

    @Value("${ibkr.api-key}")
    private String ibkrApiKey;

    public Map<String, Object> placeOpenOrders(OrderRequest request, Taoli quote, BigDecimal orderAmount)
            throws Exception {
        if (request == null || request.getTemplate() == null || orderAmount == null
                || orderAmount.signum() <= 0) {
            throw new IllegalArgumentException("orderAmount must be greater than 0");
        }
        BigDecimal baseQuantity = calculateMatchedBaseQuantity(request, quote, orderAmount);
        Map<String, Object> result = new LinkedHashMap<>();
        try {
            placeLeg(request, quote, baseQuantity, request.getLongApi(), true, result);
            placeLeg(request, quote, baseQuantity, request.getShortApi(), false, result);
            Map<String, Object> hyperliquidResult = placeHyperliquidIfNeeded(request, baseQuantity);
            if (!"no hyperliquid leg".equals(hyperliquidResult.get("message"))) {
                result.put("hyperliquid", hyperliquidResult);
            }
            return result;
        } finally {
            refreshPositionAsync(request, quote);
        }
    }

    public Map<String, Object> placeCloseOrders(OrderRequest request, Position position,
            Taoli quote) throws Exception {
        if (request == null || position == null) {
            throw new IllegalArgumentException("position is required");
        }
        Map<String, Object> result = new LinkedHashMap<>();
        List<Exception> failures = new ArrayList<>();
        try {
            closeLeg(request, quote, request.getLongApi(), true,
                    position.getLongQuantity(), result, failures);
            closeLeg(request, quote, request.getShortApi(), false,
                    position.getShortQuantity(), result, failures);
        } finally {
            refreshPositionAsync(request, quote);
        }
        if (!failures.isEmpty()) {
            IllegalStateException failure = new IllegalStateException(
                    "Failed to close " + failures.size() + " arbitrage position leg(s)");
            failures.forEach(failure::addSuppressed);
            throw failure;
        }
        return result;
    }

    private void placeLeg(OrderRequest request, Taoli quote, BigDecimal baseQuantity,
            OrderRequest.ExchangeApi api, boolean isLong, Map<String, Object> result) throws Exception {
        if (api == null || blank(api.getEe())) return;
        String exchange = api.getEe().trim().toLowerCase(Locale.ROOT);
        String response = switch (exchange) {
            case "ibkr" -> orderIbkr(request, baseQuantity, isLong, false);
            case "okx" -> orderOkx(formatContract(request.getCoin(), exchange),
                    calculateExchangeSize(exchange, baseQuantity, quote, isLong), api, isLong);
            case "binance" -> orderBinance(formatContract(request.getCoin(), exchange),
                    calculateExchangeSize(exchange, baseQuantity, quote, isLong), api, isLong, false);
            case "bybit" -> orderBybit(formatContract(request.getCoin(), exchange),
                    calculateExchangeSize(exchange, baseQuantity, quote, isLong), api, isLong, false);
            case "bitget" -> orderBitget(formatContract(request.getCoin(), exchange),
                    calculateExchangeSize(exchange, baseQuantity, quote, isLong), api, isLong, false);
            case "gate", "gateio" -> orderGate(formatContract(request.getCoin(), exchange),
                    calculateExchangeSize(exchange, baseQuantity, quote, isLong), api, isLong, false);
            case "hyper", "hyperliquid" -> null;
            default -> throw new IllegalArgumentException("Unsupported exchange: " + api.getEe());
        };
        if (response != null) result.put((isLong ? "long" : "short") + exchange, response);
    }

    private void closeLeg(OrderRequest request, Taoli quote, OrderRequest.ExchangeApi api,
            boolean isLong, BigDecimal baseQuantity, Map<String, Object> result,
            List<Exception> failures) {
        if (api == null || blank(api.getEe()) || baseQuantity == null || baseQuantity.signum() <= 0) {
            return;
        }
        String exchange = api.getEe().trim().toLowerCase(Locale.ROOT);
        try {
            Object response = switch (exchange) {
                case "ibkr" -> orderIbkr(request, baseQuantity, isLong, true);
                case "okx" -> closeOkx(formatContract(request.getCoin(), exchange), api, isLong);
                case "binance" -> orderBinance(formatContract(request.getCoin(), exchange),
                        calculateExchangeSize(exchange, baseQuantity, quote, isLong), api, isLong, true);
                case "bybit" -> orderBybit(formatContract(request.getCoin(), exchange),
                        calculateExchangeSize(exchange, baseQuantity, quote, isLong), api, isLong, true);
                case "bitget" -> orderBitget(formatContract(request.getCoin(), exchange),
                        calculateExchangeSize(exchange, baseQuantity, quote, isLong), api, isLong, true);
                case "gate", "gateio" -> orderGate(formatContract(request.getCoin(), exchange),
                        calculateExchangeSize(exchange, baseQuantity, quote, isLong), api, isLong, true);
                case "hyper", "hyperliquid" ->
                    orderHyperliquid(request, baseQuantity, api, isLong, true);
                default -> throw new IllegalArgumentException("Unsupported exchange: " + api.getEe());
            };
            result.put("close-" + (isLong ? "long-" : "short-") + exchange, response);
        } catch (Exception e) {
            failures.add(e);
            log.error("Position leg close failed: userId={}, coin={}, exchange={}, side={}",
                    request.getTemplate() == null ? null : request.getTemplate().getUr(),
                    request.getCoin(), exchange, isLong ? "long" : "short", e);
        }
    }

    private String orderIbkr(OrderRequest request, BigDecimal baseQuantity, boolean isLong,
            boolean close) throws Exception {
        String requestId = createIbkrRequestId();
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("requestId", requestId);
        body.put("coin", request.getCoin());
        body.put("side", close ? (isLong ? "SELL" : "BUY") : (isLong ? "BUY" : "SELL"));
        body.put("quantity", baseQuantity);
        body.put("preview", false);
        body.put("reduceOnly", close);
        String requestBody = objectMapper.writeValueAsString(body);
        Map<String, String> headers = Map.of(
                "X-Taoli-Api-Key", required(ibkrApiKey, "IBKR downstream apiKey"));
        String url = ibkrUrl("/api/ibkr/orders/market");
        log.info("IBKR order request: method=POST, url={}, headers={{X-Taoli-Api-Key={}}}, body={}",
                url, maskSecret(headers.get("X-Taoli-Api-Key")), requestBody);
        HttpResponse<String> response = send(url,
                "POST", requestBody, headers);
        String responseBody = requireSuccess(response, "IBKR");
        JsonNode order = objectMapper.readTree(responseBody);
        if ("filled".equalsIgnoreCase(text(order, "status"))) {
            saveIbkrResult(request, requestId, isLong, close, responseBody);
        } else {
            scheduleIbkrOrderQuery(request, requestId, headers, isLong, close);
        }
        return responseBody;
    }

    private void scheduleIbkrOrderQuery(OrderRequest request, String requestId,
            Map<String, String> headers, boolean isLong, boolean close) throws Exception {
        if (!pendingIbkrOrderQueries.add(requestId)) {
            return;
        }
        OrderRequest requestSnapshot = objectMapper.readValue(
                objectMapper.writeValueAsString(request), OrderRequest.class);
        ibkrOrderQueryExecutor.execute(
                () -> queryIbkrOrder(requestSnapshot, requestId, headers, isLong, close));
    }

    private void queryIbkrOrder(OrderRequest request, String requestId,
            Map<String, String> headers, boolean isLong, boolean close) {
        String lastResponse = "";
        try {
            for (int attempt = 0; attempt < IBKR_ORDER_QUERY_MAX_ATTEMPTS; attempt++) {
                Thread.sleep(1_000L);
                HttpResponse<String> response = send(ibkrUrl("/api/ibkr/orders/" + requestId),
                        "GET", "", headers);
                lastResponse = requireSuccess(response, "IBKR order query");
                JsonNode order = objectMapper.readTree(lastResponse);
                String status = text(order, "status");
                log.info("IBKR order status: requestId={}, status={}, filled={}, remaining={}",
                        requestId, status, text(order, "filled"), text(order, "remaining"));
                if ("filled".equalsIgnoreCase(status)) {
                    saveIbkrResult(request, requestId, isLong, close, lastResponse);
                    return;
                }
                if (isIbkrFinalFailure(status)) {
                    log.error("IBKR order failed: requestId={}, response={}", requestId, lastResponse);
                    return;
                }
            }
            log.error("IBKR order query timeout: requestId={}, response={}", requestId, lastResponse);
        } catch (Exception e) {
            log.error("IBKR background order query failed: requestId={}", requestId, e);
        } finally {
            pendingIbkrOrderQueries.remove(requestId);
        }
    }

    private void saveIbkrResult(OrderRequest request, String requestId,
            boolean isLong, boolean close, String responseBody) throws Exception {
        if (close) {
            applyIbkrCloseFill(request, requestId, isLong, responseBody);
        } else {
            saveIbkrPosition(request, requestId, isLong, responseBody);
        }
    }

    private BigDecimal calculateExchangeSize(
            String exchange, BigDecimal baseQuantity, Taoli quote, boolean isLong) {
        BigDecimal step = isLong ? quote.getLongLot() : quote.getShortLot();
        BigDecimal size = baseQuantity;
        if ("okx".equals(exchange) || "gate".equals(exchange) || "gateio".equals(exchange)) {
            BigDecimal multiplier = isLong ? quote.getLongMutil() : quote.getShortMutil();
            if (multiplier == null || multiplier.signum() <= 0) {
                throw new IllegalArgumentException(exchange + " contract multiplier is unavailable");
            }
            size = baseQuantity.divide(multiplier, 18, RoundingMode.DOWN);
            if ("gate".equals(exchange) || "gateio".equals(exchange)) {
                step = BigDecimal.ONE;
            }
        }
        if (step != null && step.signum() > 0) {
            size = size.divide(step, 0, RoundingMode.DOWN).multiply(step);
        } else {
            size = size.setScale(8, RoundingMode.DOWN);
        }
        size = size.stripTrailingZeros();
        if (size.signum() <= 0) {
            throw new IllegalArgumentException(exchange + " order amount is below the minimum size");
        }
        return size;
    }

    private BigDecimal calculateMatchedBaseQuantity(OrderRequest request, Taoli quote, BigDecimal notional)
            throws Exception {
        if (quote == null) {
            throw new IllegalArgumentException("arbitrage quote is required");
        }
        BigDecimal longPrice = orderPrice(quote, true);
        BigDecimal shortPrice = orderPrice(quote, false);
        if (longPrice == null || shortPrice == null) {
            throw new IllegalArgumentException("arbitrage order price is unavailable");
        }
        BigDecimal longMaximum = notional.divide(longPrice, 18, RoundingMode.DOWN);
        BigDecimal shortMaximum = notional.divide(shortPrice, 18, RoundingMode.DOWN);
        BigDecimal commonStep = commonQuantityStep(
                baseQuantityStep(request.getCoin(), request.getLongApi(), quote, true),
                baseQuantityStep(request.getCoin(), request.getShortApi(), quote, false));
        BigDecimal quantity = longMaximum.min(shortMaximum)
                .divide(commonStep, 0, RoundingMode.DOWN)
                .multiply(commonStep)
                .stripTrailingZeros();
        BigDecimal ibkrQuantity = calculateIbkrIntegerQuantity(
                request, notional, longPrice, shortPrice, longMaximum, shortMaximum);
        if (ibkrQuantity != null) {
            quantity = ibkrQuantity;
        }
        if (quantity.signum() <= 0) {
            throw new IllegalArgumentException("order amount is below the common minimum quantity");
        }
        return quantity;
    }

    private BigDecimal calculateIbkrIntegerQuantity(OrderRequest request, BigDecimal notional,
            BigDecimal longPrice, BigDecimal shortPrice, BigDecimal longMaximum,
            BigDecimal shortMaximum) {
        boolean ibkrLong = isIbkr(request.getLongApi());
        boolean ibkrShort = isIbkr(request.getShortApi());
        if (!ibkrLong && !ibkrShort) {
            return null;
        }

        BigDecimal quantity = null;
        if (ibkrLong) {
            quantity = roundIbkrQuantity(notional, longPrice);
        }
        if (ibkrShort) {
            BigDecimal shortQuantity = roundIbkrQuantity(notional, shortPrice);
            quantity = quantity == null ? shortQuantity : quantity.max(shortQuantity);
        }

        if (ibkrLong) {
            validateIbkrIntegerAmount(request, notional, longPrice, quantity, "long");
        }
        if (ibkrShort) {
            validateIbkrIntegerAmount(request, notional, shortPrice, quantity, "short");
        }
        if (quantity.compareTo(longMaximum) > 0 || quantity.compareTo(shortMaximum) > 0) {
            log.error("IBKR integer quantity exceeds single order amount: coin={}, amount={}, quantity={}, "
                    + "longPrice={}, shortPrice={}, longMaximum={}, shortMaximum={}",
                    request.getCoin(), notional, quantity, longPrice, shortPrice,
                    longMaximum, shortMaximum);
            throw new IllegalArgumentException("IBKR integer share quantity exceeds single order amount");
        }
        return quantity.stripTrailingZeros();
    }

    private static BigDecimal roundIbkrQuantity(BigDecimal notional, BigDecimal price) {
        return notional.divide(price, 0, RoundingMode.DOWN).max(BigDecimal.ONE);
    }

    private void validateIbkrIntegerAmount(OrderRequest request, BigDecimal notional,
            BigDecimal price, BigDecimal quantity, String side) {
        BigDecimal requiredAmount = price.multiply(quantity);
        if (requiredAmount.compareTo(notional) > 0) {
            log.error("IBKR integer quantity exceeds single order amount: coin={}, side={}, amount={}, "
                    + "price={}, quantity={}, requiredAmount={}",
                    request.getCoin(), side, notional, price, quantity, requiredAmount);
            throw new IllegalArgumentException("IBKR integer share quantity exceeds single order amount");
        }
    }

    private BigDecimal baseQuantityStep(String coin, OrderRequest.ExchangeApi api, Taoli quote, boolean isLong)
            throws Exception {
        if (api == null || blank(api.getEe())) {
            throw new IllegalArgumentException((isLong ? "long" : "short") + " exchange is required");
        }
        String exchange = api.getEe().trim().toLowerCase(Locale.ROOT);
        BigDecimal lot = isLong ? quote.getLongLot() : quote.getShortLot();
        BigDecimal minimum = isLong ? quote.getLongMinSz() : quote.getShortMinSz();
        return switch (exchange) {
            case "okx" -> positive(lot, "OKX lot size")
                    .multiply(positive(isLong ? quote.getLongMutil() : quote.getShortMutil(),
                            "OKX contract multiplier"));
            case "gate", "gateio" -> positive(
                    isLong ? quote.getLongMutil() : quote.getShortMutil(), "Gate contract multiplier");
            case "hyper", "hyperliquid" -> BigDecimal.ONE.movePointLeft(loadHyperliquidAsset(coin).szDecimals());
            default -> firstPositive(lot, minimum, new BigDecimal("0.00000001"));
        };
    }

    private static BigDecimal commonQuantityStep(BigDecimal first, BigDecimal second) {
        BigDecimal left = positive(first, "long quantity step").stripTrailingZeros();
        BigDecimal right = positive(second, "short quantity step").stripTrailingZeros();
        int scale = Math.max(Math.max(left.scale(), right.scale()), 0);
        BigInteger leftUnits = left.movePointRight(scale).toBigIntegerExact();
        BigInteger rightUnits = right.movePointRight(scale).toBigIntegerExact();
        BigInteger lcm = leftUnits.divide(leftUnits.gcd(rightUnits)).multiply(rightUnits);
        return new BigDecimal(lcm, scale).stripTrailingZeros();
    }

    private static BigDecimal positive(BigDecimal value, String name) {
        if (value == null || value.signum() <= 0) {
            throw new IllegalArgumentException(name + " is unavailable");
        }
        return value;
    }

    public void refreshPositionAsync(OrderRequest request, Taoli quote) {
        String queryKey = positionQueryKey(request);
        if (queryKey == null || System.currentTimeMillis() < positionQueryRetryAt.getOrDefault(queryKey, 0L)
                || !pendingPositionQueries.add(queryKey)) {
            return;
        }
        try {
            OrderRequest requestSnapshot = objectMapper.readValue(
                    objectMapper.writeValueAsString(request), OrderRequest.class);
            Taoli quoteSnapshot = objectMapper.readValue(
                    objectMapper.writeValueAsString(quote), Taoli.class);
            positionQueryExecutor.execute(
                    () -> runPositionQuery(queryKey, requestSnapshot, quoteSnapshot));
            log.info("Position query queued: userId={}, coin={}",
                    requestSnapshot.getTemplate().getUr(), requestSnapshot.getCoin());
        } catch (Exception e) {
            pendingPositionQueries.remove(queryKey);
            log.warn("Failed to queue position query: key={}", queryKey, e);
        }
    }

    private void runPositionQuery(String queryKey, OrderRequest request, Taoli quote) {
        try {
            Thread.sleep(POSITION_QUERY_INITIAL_DELAY_MILLIS);
            refreshPosition(request, quote);
            positionQueryRetryAt.remove(queryKey);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        } catch (Exception e) {
            positionQueryRetryAt.put(queryKey,
                    System.currentTimeMillis() + POSITION_QUERY_RETRY_MILLIS);
            log.warn("Background position query failed: userId={}, coin={}; retry after {} seconds",
                    request.getTemplate().getUr(), request.getCoin(),
                    POSITION_QUERY_RETRY_MILLIS / 1_000L, e);
        } finally {
            pendingPositionQueries.remove(queryKey);
        }
    }

    private void refreshPosition(OrderRequest request, Taoli quote) throws Exception {
        if (request == null || request.getTemplate() == null || request.getTemplate().getUr() == null) {
            throw new IllegalArgumentException("template.ur is required");
        }
        LegPosition longPosition = loadExchangePosition(
                request.getCoin(), request.getLongApi(), quote, true);
        LegPosition shortPosition = loadExchangePosition(
                request.getCoin(), request.getShortApi(), quote, false);
        if (longPosition == null && shortPosition == null) {
            throw new IllegalStateException("Position query is not supported for this exchange pair");
        }

        String key = PositionKey.of(request);
        if (key == null) {
            throw new IllegalArgumentException("position key cannot be created");
        }
        Position position = currentPosition(key);
        position.setUserId(request.getTemplate().getUr());
        position.setCoin(request.getCoin());
        if (longPosition != null) {
            position.setLongExchange(longPosition.exchange());
            position.setLongOpenPrice(longPosition.openPrice());
            position.setLongQuantity(longPosition.quantity());
        }
        if (shortPosition != null) {
            position.setShortExchange(shortPosition.exchange());
            position.setShortOpenPrice(shortPosition.openPrice());
            position.setShortQuantity(shortPosition.quantity());
        }
        if (isOpeningWithoutObservedPosition(position, longPosition, shortPosition)) {
            position.setUpdatedAt(System.currentTimeMillis());
            stringRedisTemplate.opsForValue().set(key, objectMapper.writeValueAsString(position));
            return;
        }
        savePosition(key, position);
    }

    private static boolean isOpeningWithoutObservedPosition(Position position,
            LegPosition longPosition, LegPosition shortPosition) {
        return position != null
                && "OPENING".equalsIgnoreCase(position.getStatus())
                && !hasObservedQuantity(longPosition)
                && !hasObservedQuantity(shortPosition);
    }

    private static boolean hasObservedQuantity(LegPosition position) {
        return position != null && position.quantity() != null && position.quantity().signum() > 0;
    }

    private LegPosition loadExchangePosition(String coin, OrderRequest.ExchangeApi api,
            Taoli quote, boolean isLong) throws Exception {
        if (api == null || blank(api.getEe())) return null;
        String exchange = api.getEe().trim().toLowerCase(Locale.ROOT);
        if (!POSITION_QUERY_EXCHANGES.contains(exchange)) return null;
        waitForPositionQuerySlot();
        return switch (exchange) {
            case "okx" -> loadOkxPosition(coin, api, quote, isLong);
            case "binance" -> loadBinancePosition(coin, api, isLong);
            case "bybit" -> loadBybitPosition(coin, api, isLong);
            case "bitget" -> loadBitgetPosition(coin, api, isLong);
            case "gate", "gateio" -> loadGatePosition(coin, api, quote, isLong);
            case "hyper", "hyperliquid" -> loadHyperliquidPosition(coin, api, isLong);
            default -> null;
        };
    }

    private void waitForPositionQuerySlot() throws InterruptedException {
        synchronized (positionQueryRateLock) {
            long waitMillis = nextPositionQueryAt - System.currentTimeMillis();
            if (waitMillis > 0) {
                Thread.sleep(waitMillis);
            }
            nextPositionQueryAt = System.currentTimeMillis() + POSITION_QUERY_INTERVAL_MILLIS;
        }
    }

    private static String positionQueryKey(OrderRequest request) {
        if (request == null || request.getTemplate() == null
                || request.getTemplate().getUr() == null || blank(request.getCoin())) {
            return null;
        }
        return PositionKey.of(request);
    }

    @PreDestroy
    public void stopPositionQueryExecutor() {
        positionQueryExecutor.shutdownNow();
        ibkrOrderQueryExecutor.shutdownNow();
    }

    private LegPosition loadOkxPosition(String coin, OrderRequest.ExchangeApi api,
            Taoli quote, boolean isLong) throws Exception {
        String instrument = formatContract(coin, "okx");
        String path = "/api/v5/account/positions?instId=" + instrument;
        String timestamp = OKX_TIME.format(Instant.now());
        HttpResponse<String> response = send("https://www.okx.com" + path, "GET", "", Map.of(
                "OK-ACCESS-KEY", required(api.getAk(), "OKX apiKey"),
                "OK-ACCESS-SIGN", hmacBase64("HmacSHA256", required(api.getAc(), "OKX secretKey"),
                        timestamp + "GET" + path),
                "OK-ACCESS-TIMESTAMP", timestamp,
                "OK-ACCESS-PASSPHRASE", required(api.getAp(), "OKX passphrase")));
        String body = requireSuccess(response, "OKX position");
        JsonNode result = objectMapper.readTree(body);
        if (!"0".equals(text(result, "code"))) {
            throw new IllegalStateException("OKX position query rejected: " + body);
        }
        BigDecimal multiplier = positive(
                isLong ? quote.getLongMutil() : quote.getShortMutil(), "OKX contract multiplier");
        JsonNode data = result.get("data");
        for (int index = 0; data != null && index < data.size(); index++) {
            JsonNode row = data.get(index);
            BigDecimal contracts = decimal(row, "pos").abs();
            String positionSide = text(row, "posSide");
            boolean matches = isLong
                    ? "long".equalsIgnoreCase(positionSide)
                    : "short".equalsIgnoreCase(positionSide);
            if (matches && contracts.signum() > 0) {
                return new LegPosition("okx", decimal(row, "avgPx"),
                        contracts.multiply(multiplier).stripTrailingZeros());
            }
        }
        return new LegPosition("okx", BigDecimal.ZERO, BigDecimal.ZERO);
    }

    private LegPosition loadBinancePosition(String coin, OrderRequest.ExchangeApi api, boolean isLong)
            throws Exception {
        Map<String, String> params = new LinkedHashMap<>();
        params.put("symbol", formatContract(coin, "binance"));
        params.put("recvWindow", "5000");
        params.put("timestamp", String.valueOf(System.currentTimeMillis()));
        String query = query(params);
        String signedQuery = query + "&signature="
                + hmacHex("HmacSHA256", required(api.getAc(), "Binance secretKey"), query);
        HttpResponse<String> response = send(
                "https://fapi.binance.com/fapi/v3/positionRisk?" + signedQuery,
                "GET", "", Map.of("X-MBX-APIKEY", required(api.getAk(), "Binance apiKey")));
        String body = requireSuccess(response, "Binance position");
        JsonNode result = objectMapper.readTree(body);
        for (int index = 0; index < result.size(); index++) {
            JsonNode row = result.get(index);
            String positionSide = text(row, "positionSide");
            boolean matches = isLong
                    ? "LONG".equalsIgnoreCase(positionSide)
                    : "SHORT".equalsIgnoreCase(positionSide);
            if (matches) {
                return new LegPosition("binance", decimal(row, "entryPrice"),
                        decimal(row, "positionAmt").abs().stripTrailingZeros());
            }
        }
        return new LegPosition("binance", BigDecimal.ZERO, BigDecimal.ZERO);
    }

    private LegPosition loadBybitPosition(String coin, OrderRequest.ExchangeApi api, boolean isLong)
            throws Exception {
        Map<String, String> params = new LinkedHashMap<>();
        params.put("category", "linear");
        params.put("symbol", formatContract(coin, "bybit"));
        String query = query(params);
        String timestamp = String.valueOf(System.currentTimeMillis());
        String key = required(api.getAk(), "Bybit apiKey");
        String window = "5000";
        HttpResponse<String> response = send(
                "https://api.bybit.com/v5/position/list?" + query, "GET", "", Map.of(
                        "X-BAPI-SIGN", hmacHex("HmacSHA256",
                                required(api.getAc(), "Bybit secretKey"),
                                timestamp + key + window + query),
                        "X-BAPI-API-KEY", key,
                        "X-BAPI-TIMESTAMP", timestamp,
                        "X-BAPI-RECV-WINDOW", window));
        String body = requireSuccess(response, "Bybit position");
        JsonNode result = objectMapper.readTree(body);
        if (!"0".equals(text(result, "retCode"))) {
            throw new IllegalStateException("Bybit position query rejected: " + body);
        }
        JsonNode rows = result.get("result") == null ? null : result.get("result").get("list");
        for (int index = 0; rows != null && index < rows.size(); index++) {
            JsonNode row = rows.get(index);
            String side = text(row, "side");
            int positionIndex = row.has("positionIdx") ? row.get("positionIdx").asInt() : 0;
            boolean matches = isLong
                    ? positionIndex == 1 || "Buy".equalsIgnoreCase(side)
                    : positionIndex == 2 || "Sell".equalsIgnoreCase(side);
            if (matches) {
                return new LegPosition("bybit", decimal(row, "avgPrice"),
                        decimal(row, "size").abs().stripTrailingZeros());
            }
        }
        return new LegPosition("bybit", BigDecimal.ZERO, BigDecimal.ZERO);
    }

    private LegPosition loadBitgetPosition(String coin, OrderRequest.ExchangeApi api, boolean isLong)
            throws Exception {
        String path = "/api/v2/mix/position/single-position";
        Map<String, String> params = new LinkedHashMap<>();
        params.put("symbol", formatContract(coin, "bitget"));
        params.put("productType", "USDT-FUTURES");
        params.put("marginCoin", "USDT");
        String query = query(params);
        String timestamp = String.valueOf(System.currentTimeMillis());
        HttpResponse<String> response = send("https://api.bitget.com" + path + "?" + query,
                "GET", "", Map.of(
                        "ACCESS-KEY", required(api.getAk(), "Bitget apiKey"),
                        "ACCESS-SIGN", hmacBase64("HmacSHA256",
                                required(api.getAc(), "Bitget secretKey"),
                                timestamp + "GET" + path + "?" + query),
                        "ACCESS-TIMESTAMP", timestamp,
                        "ACCESS-PASSPHRASE", required(api.getAp(), "Bitget passphrase")));
        String body = requireSuccess(response, "Bitget position");
        JsonNode result = objectMapper.readTree(body);
        if (!"00000".equals(text(result, "code"))) {
            throw new IllegalStateException("Bitget position query rejected: " + body);
        }
        JsonNode rows = result.get("data");
        for (int index = 0; rows != null && index < rows.size(); index++) {
            JsonNode row = rows.get(index);
            boolean matches = isLong
                    ? "long".equalsIgnoreCase(text(row, "holdSide"))
                    : "short".equalsIgnoreCase(text(row, "holdSide"));
            if (matches) {
                return new LegPosition("bitget", decimal(row, "openPriceAvg"),
                        decimal(row, "total").abs().stripTrailingZeros());
            }
        }
        return new LegPosition("bitget", BigDecimal.ZERO, BigDecimal.ZERO);
    }

    private LegPosition loadGatePosition(String coin, OrderRequest.ExchangeApi api,
            Taoli quote, boolean isLong) throws Exception {
        String path = "/api/v4/futures/usdt/positions";
        String query = "holding=false";
        String timestamp = String.valueOf(System.currentTimeMillis() / 1000);
        String preSign = "GET\n" + path + "\n" + query + "\n" + sha512("") + "\n" + timestamp;
        HttpResponse<String> response = send("https://api.gateio.ws" + path + "?" + query,
                "GET", "", Map.of(
                        "KEY", required(api.getAk(), "Gate apiKey"),
                        "Timestamp", timestamp,
                        "SIGN", hmacHex("HmacSHA512",
                                required(api.getAc(), "Gate secretKey"), preSign)));
        String body = requireSuccess(response, "Gate position");
        JsonNode rows = objectMapper.readTree(body);
        String contract = formatContract(coin, "gate");
        BigDecimal multiplier = positive(
                isLong ? quote.getLongMutil() : quote.getShortMutil(), "Gate contract multiplier");
        for (int index = 0; index < rows.size(); index++) {
            JsonNode row = rows.get(index);
            if (!contract.equalsIgnoreCase(text(row, "contract"))) continue;
            BigDecimal contracts = decimal(row, "size");
            String mode = text(row, "mode");
            boolean matches = isLong
                    ? "dual_long".equalsIgnoreCase(mode)
                            || ("single".equalsIgnoreCase(mode) && contracts.signum() > 0)
                    : "dual_short".equalsIgnoreCase(mode)
                            || ("single".equalsIgnoreCase(mode) && contracts.signum() < 0);
            if (matches) {
                return new LegPosition("gate", decimal(row, "entry_price"),
                        contracts.abs().multiply(multiplier).stripTrailingZeros());
            }
        }
        return new LegPosition("gate", BigDecimal.ZERO, BigDecimal.ZERO);
    }

    private LegPosition loadHyperliquidPosition(String coin, OrderRequest.ExchangeApi api, boolean isLong)
            throws Exception {
        requireAddress(api.getAk(), "Hyperliquid account address");
        Asset asset = loadHyperliquidAsset(coin);
        Map<String, Object> request = new LinkedHashMap<>();
        request.put("type", "clearinghouseState");
        request.put("user", api.getAk().trim());
        request.put("dex", asset.dex());
        JsonNode result = requestHyperliquidInfo(
                objectMapper.writeValueAsString(request), System.currentTimeMillis());
        JsonNode rows = result.get("assetPositions");
        for (int index = 0; rows != null && index < rows.size(); index++) {
            JsonNode position = rows.get(index).get("position");
            if (position == null
                    || !asset.marketName().equalsIgnoreCase(text(position, "coin"))) continue;
            BigDecimal signedQuantity = decimal(position, "szi");
            boolean matches = isLong ? signedQuantity.signum() > 0 : signedQuantity.signum() < 0;
            if (matches) {
                return new LegPosition("hyperliquid", decimal(position, "entryPx"),
                        signedQuantity.abs().stripTrailingZeros());
            }
        }
        return new LegPosition("hyperliquid", BigDecimal.ZERO, BigDecimal.ZERO);
    }

    private void saveIbkrPosition(OrderRequest request, String requestId, boolean isLong,
            String responseBody) throws Exception {
        JsonNode order = objectMapper.readTree(responseBody);
        BigDecimal fillQuantity = decimal(order, "filled");
        BigDecimal fillPrice = decimal(order, "averageFillPrice");
        if (fillQuantity.signum() <= 0 || fillPrice.signum() <= 0) {
            throw new IllegalStateException(
                    "IBKR filled order has no valid fill details: " + responseBody);
        }
        Integer userId = request.getTemplate() == null ? null : request.getTemplate().getUr();
        if (userId == null) throw new IllegalArgumentException("template.ur is required");
        String key = PositionKey.of(request);
        if (key == null) throw new IllegalArgumentException("position key cannot be created");
        Position position = currentPosition(key);
        position.setUserId(userId);
        position.setCoin(request.getCoin());
        if (isLong) {
            position.setLongExchange("ibkr");
            position.setLongOpenPrice(mergedPrice(
                    position.getLongOpenPrice(), position.getLongQuantity(), fillPrice, fillQuantity));
            position.setLongQuantity(sum(position.getLongQuantity(), fillQuantity));
        } else {
            position.setShortExchange("ibkr");
            position.setShortOpenPrice(mergedPrice(
                    position.getShortOpenPrice(), position.getShortQuantity(), fillPrice, fillQuantity));
            position.setShortQuantity(sum(position.getShortQuantity(), fillQuantity));
        }
        savePosition(key, position);
        log.info("IBKR position updated: key={}, requestId={}, coin={}, side={}, "
                        + "fillPrice={}, fillQuantity={}",
                key, requestId, request.getCoin(), isLong ? "long" : "short",
                fillPrice, fillQuantity);
    }

    private void applyIbkrCloseFill(OrderRequest request, String requestId, boolean isLong,
            String responseBody) throws Exception {
        JsonNode order = objectMapper.readTree(responseBody);
        BigDecimal fillQuantity = decimal(order, "filled").abs();
        if (fillQuantity.signum() <= 0) {
            throw new IllegalStateException(
                    "IBKR close order has no valid fill quantity: " + responseBody);
        }
        Integer userId = request.getTemplate() == null ? null : request.getTemplate().getUr();
        if (userId == null) throw new IllegalArgumentException("template.ur is required");
        String key = PositionKey.of(request);
        if (key == null) throw new IllegalArgumentException("position key cannot be created");
        Position position = currentPosition(key);
        BigDecimal currentQuantity = isLong
                ? zeroIfNull(position.getLongQuantity())
                : zeroIfNull(position.getShortQuantity());
        BigDecimal remainingQuantity = currentQuantity.subtract(fillQuantity).max(BigDecimal.ZERO)
                .stripTrailingZeros();
        if (isLong) {
            position.setLongQuantity(remainingQuantity);
            if (remainingQuantity.signum() == 0) position.setLongOpenPrice(BigDecimal.ZERO);
        } else {
            position.setShortQuantity(remainingQuantity);
            if (remainingQuantity.signum() == 0) position.setShortOpenPrice(BigDecimal.ZERO);
        }
        savePosition(key, position);
        log.info("IBKR position reduced: key={}, requestId={}, coin={}, side={}, "
                        + "fillQuantity={}, remainingQuantity={}",
                key, requestId, request.getCoin(), isLong ? "long" : "short",
                fillQuantity, remainingQuantity);
    }

    private void savePosition(String key, Position position) throws Exception {
        BigDecimal longQuantity = position.getLongQuantity();
        BigDecimal shortQuantity = position.getShortQuantity();
        if (longQuantity != null && shortQuantity != null) {
            position.setMatchedQuantity(longQuantity.min(shortQuantity).stripTrailingZeros());
            if (longQuantity.signum() == 0 && shortQuantity.signum() == 0) {
                position.setStatus("CLOSED");
            } else {
                position.setStatus(longQuantity.compareTo(shortQuantity) == 0 ? "MATCHED" : "MISMATCHED");
            }
        } else {
            position.setStatus("PARTIAL");
        }
        position.setUpdatedAt(System.currentTimeMillis());
        stringRedisTemplate.opsForValue().set(key, objectMapper.writeValueAsString(position));
        log.info("Arbitrage position saved: key={}, coin={}, longQuantity={}, shortQuantity={}, status={}",
                key, position.getCoin(), longQuantity, shortQuantity, position.getStatus());
    }

    private Position currentPosition(String key) {
        Position stored = readPosition(key);
        return stored == null ? new Position() : stored;
    }

    private static BigDecimal decimal(JsonNode node, String name) {
        String value = text(node, name);
        return value.isBlank() ? BigDecimal.ZERO : new BigDecimal(value);
    }

    private static String createIbkrRequestId() {
        return "qiqi-" + System.currentTimeMillis();
    }

    private String ibkrUrl(String path) {
        return ibkrBaseUrl.replaceAll("/+$", "") + path;
    }

    private static BigDecimal firstPositive(BigDecimal... values) {
        for (BigDecimal value : values) {
            if (value != null && value.signum() > 0) return value;
        }
        return null;
    }

    private static BigDecimal zeroIfNull(BigDecimal value) {
        return value == null ? BigDecimal.ZERO : value;
    }

    private static BigDecimal orderPrice(Taoli quote, boolean isLong) {
        return isLong
                ? firstPositive(quote.getLongAskPce(), quote.getLongLast(), quote.getLongMark())
                : firstPositive(quote.getShortBidPce(), quote.getShortLast(), quote.getShortMark());
    }

    private static String formatContract(String coin, String exchange) {
        String base = baseCoin(coin);
        return switch (exchange) {
            case "okx" -> base + "-USDT-SWAP";
            case "binance", "bybit", "bitget" -> base + "USDT";
            case "gate", "gateio" -> base + "_USDT";
            default -> base;
        };
    }

    private static String baseCoin(String coin) {
        String value = required(coin, "coin").trim().toUpperCase(Locale.ROOT);
        int colon = value.lastIndexOf(':');
        if (colon >= 0 && colon + 1 < value.length()) {
            value = value.substring(colon + 1);
        }
        for (String suffix : List.of("-USDT-SWAP", "-SWAP-USDT", "_USDT", "-USDT", "/USDT", "USDT")) {
            if (value.length() > suffix.length() && value.endsWith(suffix)) {
                return value.substring(0, value.length() - suffix.length());
            }
        }
        return value;
    }

    private static boolean isIbkrFinalFailure(String status) {
        return "cancelled".equalsIgnoreCase(status)
                || "inactive".equalsIgnoreCase(status)
                || "error".equalsIgnoreCase(status)
                || "local_rejected".equalsIgnoreCase(status);
    }

    private String orderOkx(String coin, BigDecimal size, OrderRequest.ExchangeApi api, boolean isLong)
            throws Exception {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("instId", coin);
        body.put("ordType", "market");
        body.put("side", isLong ? "buy" : "sell");
        body.put("posSide", isLong ? "long" : "short");
        body.put("tdMode", "cross");
        body.put("sz", size.stripTrailingZeros().toPlainString());
        String path = "/api/v5/trade/order";
        String requestBody = objectMapper.writeValueAsString(body);
        String timestamp = OKX_TIME.format(Instant.now());
        HttpResponse<String> response = send("https://www.okx.com" + path, "POST", requestBody, Map.of(
                "OK-ACCESS-KEY", required(api.getAk(), "OKX apiKey"),
                "OK-ACCESS-SIGN", hmacBase64("HmacSHA256", required(api.getAc(), "OKX secretKey"),
                        timestamp + "POST" + path + requestBody),
                "OK-ACCESS-TIMESTAMP", timestamp,
                "OK-ACCESS-PASSPHRASE", required(api.getAp(), "OKX passphrase")));
        return requireOkxSuccess(response);
    }

    private String closeOkx(String coin, OrderRequest.ExchangeApi api, boolean isLong)
            throws Exception {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("instId", coin);
        body.put("posSide", isLong ? "long" : "short");
        body.put("mgnMode", "cross");
        String path = "/api/v5/trade/close-position";
        String requestBody = objectMapper.writeValueAsString(body);
        String timestamp = OKX_TIME.format(Instant.now());
        HttpResponse<String> response = send("https://www.okx.com" + path, "POST", requestBody, Map.of(
                "OK-ACCESS-KEY", required(api.getAk(), "OKX apiKey"),
                "OK-ACCESS-SIGN", hmacBase64("HmacSHA256", required(api.getAc(), "OKX secretKey"),
                        timestamp + "POST" + path + requestBody),
                "OK-ACCESS-TIMESTAMP", timestamp,
                "OK-ACCESS-PASSPHRASE", required(api.getAp(), "OKX passphrase")));
        return requireOkxSuccess(response);
    }

    private String orderBinance(String coin, BigDecimal size, OrderRequest.ExchangeApi api,
            boolean isLong, boolean close) throws Exception {
        Map<String, String> params = new LinkedHashMap<>();
        params.put("symbol", coin);
        params.put("side", close ? (isLong ? "SELL" : "BUY") : (isLong ? "BUY" : "SELL"));
        params.put("positionSide", isLong ? "LONG" : "SHORT");
        params.put("type", "MARKET");
        params.put("newClientOrderId", (close ? "close" : "order") + System.currentTimeMillis());
        params.put("quantity", size.stripTrailingZeros().toPlainString());
        params.put("timestamp", String.valueOf(System.currentTimeMillis()));
        String query = query(params);
        String signedQuery = query + "&signature="
                + hmacHex("HmacSHA256", required(api.getAc(), "Binance secretKey"), query);
        HttpResponse<String> response = send("https://fapi.binance.com/fapi/v1/order?" + signedQuery,
                "POST", "", Map.of("X-MBX-APIKEY", required(api.getAk(), "Binance apiKey")));
        return requireSuccess(response, "Binance");
    }

    private String orderBybit(String coin, BigDecimal size, OrderRequest.ExchangeApi api,
            boolean isLong, boolean close) throws Exception {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("category", "linear");
        body.put("symbol", coin);
        body.put("side", close ? (isLong ? "Sell" : "Buy") : (isLong ? "Buy" : "Sell"));
        body.put("orderType", "Market");
        body.put("qty", size.stripTrailingZeros().toPlainString());
        body.put("timeInForce", "IOC");
        body.put("positionIdx", isLong ? 1 : 2);
        body.put("reduceOnly", close);
        String requestBody = objectMapper.writeValueAsString(body);
        String timestamp = String.valueOf(System.currentTimeMillis());
        String key = required(api.getAk(), "Bybit apiKey");
        String window = "5000";
        HttpResponse<String> response = send("https://api.bybit.com/v5/order/create", "POST", requestBody, Map.of(
                "X-BAPI-SIGN", hmacHex("HmacSHA256", required(api.getAc(), "Bybit secretKey"),
                        timestamp + key + window + requestBody),
                "X-BAPI-API-KEY", key,
                "X-BAPI-TIMESTAMP", timestamp,
                "X-BAPI-RECV-WINDOW", window));
        return requireJsonCode(response, "Bybit", "retCode", "0");
    }

    private String orderBitget(String coin, BigDecimal size, OrderRequest.ExchangeApi api,
            boolean isLong, boolean close) throws Exception {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("symbol", coin);
        body.put("productType", "USDT-FUTURES");
        body.put("size", size.stripTrailingZeros().toPlainString());
        body.put("marginMode", "crossed");
        body.put("marginCoin", "USDT");
        body.put("side", isLong ? "buy" : "sell");
        body.put("tradeSide", close ? "close" : "open");
        body.put("orderType", "market");
        body.put("force", "gtc");
        String requestBody = objectMapper.writeValueAsString(body);
        String path = "/api/v2/mix/order/place-order";
        String timestamp = String.valueOf(System.currentTimeMillis());
        HttpResponse<String> response = send("https://api.bitget.com" + path, "POST", requestBody, Map.of(
                "ACCESS-KEY", required(api.getAk(), "Bitget apiKey"),
                "ACCESS-SIGN", hmacBase64("HmacSHA256", required(api.getAc(), "Bitget secretKey"),
                        timestamp + "POST" + path + requestBody),
                "ACCESS-TIMESTAMP", timestamp,
                "ACCESS-PASSPHRASE", required(api.getAp(), "Bitget passphrase"),
                "locale", "zh-CN"));
        return requireJsonCode(response, "Bitget", "code", "00000");
    }

    private String orderGate(String coin, BigDecimal size, OrderRequest.ExchangeApi api,
            boolean isLong, boolean close) throws Exception {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("contract", coin);
        boolean buy = close ? !isLong : isLong;
        body.put("size", (buy ? size : size.negate()).stripTrailingZeros().toPlainString());
        body.put("price", "0");
        body.put("tif", "ioc");
        body.put("reduce_only", close);
        String requestBody = objectMapper.writeValueAsString(body);
        String path = "/api/v4/futures/usdt/orders";
        String timestamp = String.valueOf(System.currentTimeMillis() / 1000);
        String payloadHash = sha512(requestBody);
        String preSign = "POST\n" + path + "\n\n" + payloadHash + "\n" + timestamp;
        HttpResponse<String> response = send("https://api.gateio.ws" + path, "POST", requestBody, Map.of(
                "KEY", required(api.getAk(), "Gate apiKey"),
                "Timestamp", timestamp,
                "SIGN", hmacHex("HmacSHA512", required(api.getAc(), "Gate secretKey"), preSign)));
        return requireSuccess(response, "Gate");
    }

    private HttpResponse<String> send(String url, String method, String body, Map<String, String> headers)
            throws Exception {
        boolean orderOperation = url.contains("/order") || url.contains("/close-position")
                || url.endsWith("/exchange");
        HttpRequest.Builder builder = HttpRequest.newBuilder().uri(URI.create(url)).timeout(Duration.ofSeconds(10));
        headers.forEach(builder::header);
        if ("POST".equals(method)) {
            builder.header("Content-Type", "application/json")
                    .POST(HttpRequest.BodyPublishers.ofString(body, StandardCharsets.UTF_8));
        } else {
            builder.method(method, HttpRequest.BodyPublishers.ofString(body, StandardCharsets.UTF_8));
        }
        HttpResponse<String> response = HTTP_CLIENT.send(builder.build(),
                HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8));
        if (orderOperation) {
            log.info("Order response: status={}, body={}", response.statusCode(), response.body());
        }
        return response;
    }

    private String requireSuccess(HttpResponse<String> response, String exchange) {
        if (response.statusCode() < 200 || response.statusCode() >= 300) {
            throw new IllegalStateException(exchange + " HTTP status " + response.statusCode() + ": " + response.body());
        }
        return response.body();
    }

    private String requireOkxSuccess(HttpResponse<String> response) throws Exception {
        String body = requireSuccess(response, "OKX");
        JsonNode result = objectMapper.readTree(body);
        JsonNode data = result.get("data");
        String subCode = data != null && data.size() > 0 ? text(data.get(0), "sCode") : "";
        if (!"0".equals(text(result, "code")) || (!subCode.isBlank() && !"0".equals(subCode))) {
            throw new IllegalStateException("OKX order rejected: " + body);
        }
        return body;
    }

    private String requireJsonCode(HttpResponse<String> response, String exchange,
            String codeField, String successCode) throws Exception {
        String body = requireSuccess(response, exchange);
        JsonNode result = objectMapper.readTree(body);
        if (!successCode.equals(text(result, codeField))) {
            throw new IllegalStateException(exchange + " order rejected: " + body);
        }
        return body;
    }

    private static String query(Map<String, String> params) {
        StringBuilder result = new StringBuilder();
        params.forEach((key, value) -> {
            if (!result.isEmpty()) result.append('&');
            result.append(key).append('=').append(value);
        });
        return result.toString();
    }

    private static String sha512(String value) throws Exception {
        return hex(MessageDigest.getInstance("SHA-512").digest(value.getBytes(StandardCharsets.UTF_8)));
    }

    private static String hmacBase64(String algorithm, String secret, String value) throws Exception {
        return Base64.getEncoder().encodeToString(mac(algorithm, secret, value));
    }

    private static String hmacHex(String algorithm, String secret, String value) throws Exception {
        return hex(mac(algorithm, secret, value));
    }

    private static byte[] mac(String algorithm, String secret, String value) throws Exception {
        Mac mac = Mac.getInstance(algorithm);
        mac.init(new SecretKeySpec(secret.getBytes(StandardCharsets.UTF_8), algorithm));
        return mac.doFinal(value.getBytes(StandardCharsets.UTF_8));
    }

    private static String hex(byte[] bytes) {
        StringBuilder result = new StringBuilder(bytes.length * 2);
        for (byte value : bytes) result.append(String.format("%02x", value));
        return result.toString();
    }

    private static String required(String value, String name) {
        if (blank(value)) throw new IllegalArgumentException(name + " is required");
        return value;
    }

    private static boolean blank(String value) { return value == null || value.isBlank(); }

    private static String maskSecret(String value) {
        if (blank(value)) return "";
        if (value.length() <= 8) return "****";
        return value.substring(0, 4) + "****" + value.substring(value.length() - 4);
    }

    private Map<String, Object> placeHyperliquidIfNeeded(
            OrderRequest request, BigDecimal baseQuantity) throws Exception {
        OrderRequest.ExchangeApi api = null;
        boolean isLong = false;
        if (isHyperliquid(request.getShortApi())) {
            api = request.getShortApi();
        } else if (isHyperliquid(request.getLongApi())) {
            api = request.getLongApi();
            isLong = true;
        }
        if (api == null) return Map.of("message", "no hyperliquid leg");
        return orderHyperliquid(request, baseQuantity, api, isLong, false);
    }

    private Map<String, Object> orderHyperliquid(OrderRequest request, BigDecimal baseQuantity,
            OrderRequest.ExchangeApi api, boolean isLong, boolean close) throws Exception {
        if (blank(api.getAk()) || blank(api.getAc()) || blank(api.getAp())) {
            throw new IllegalArgumentException("Hyperliquid ak, ac and ap are required");
        }
        requireAddress(api.getAk(), "Hyperliquid account address");
        requireAddress(api.getAp(), "Hyperliquid agent address");
        if (!close && (request.getTemplate() == null || request.getTemplate().getUs() == null
                || request.getTemplate().getUs().signum() <= 0)) {
            throw new IllegalArgumentException("template.us must be greater than 0");
        }

        Asset asset = loadHyperliquidAsset(request.getCoin());
        BigDecimal quantity = baseQuantity.setScale(asset.szDecimals(), RoundingMode.DOWN);
        if (quantity.signum() <= 0) throw new IllegalArgumentException("matched quantity is too small");
        boolean isBuy = close ? !isLong : isLong;
        BigDecimal marketPrice = asset.midPrice().multiply(isBuy
                ? new BigDecimal("1.05") : new BigDecimal("0.95"));
        int priceDecimals = Math.max(0, 6 - asset.szDecimals());
        String price = marketPrice.round(new MathContext(5, RoundingMode.HALF_UP))
                .setScale(Math.min(priceDecimals, Math.max(0, marketPrice.scale())), RoundingMode.HALF_UP)
                .stripTrailingZeros().toPlainString();
        String size = quantity.stripTrailingZeros().toPlainString();

        LinkedHashMap<String, Object> order = new LinkedHashMap<>();
        order.put("a", asset.assetId());
        order.put("b", isBuy);
        order.put("p", price);
        order.put("s", size);
        order.put("r", close);
        order.put("t", Map.of("limit", Map.of("tif", "Ioc")));
        LinkedHashMap<String, Object> action = new LinkedHashMap<>();
        action.put("type", "order");
        action.put("orders", List.of(order));
        action.put("grouping", "na");

        long nonce = System.currentTimeMillis();
        long expiresAfter = nonce + 30_000L;
        Signature signature = signHyperliquidAction(api.getAc(), action, nonce, expiresAfter);
        LinkedHashMap<String, Object> body = new LinkedHashMap<>();
        body.put("action", action);
        body.put("nonce", nonce);
        body.put("signature", signature.toMap());
        body.put("expiresAfter", expiresAfter);
        HttpResponse<String> response = send("https://api.hyperliquid.xyz/exchange", "POST",
                objectMapper.writeValueAsString(body), Map.of());
        String responseBody = requireSuccess(response, "Hyperliquid");
        JsonNode result = objectMapper.readTree(responseBody);
        if (!"ok".equalsIgnoreCase(text(result, "status"))) {
            throw new IllegalStateException("Hyperliquid order rejected: " + responseBody);
        }
        JsonNode filled = result.get("response").get("data").get("statuses").get(0).get("filled");
        if (filled == null || filled.isNull()) {
            throw new IllegalStateException("Hyperliquid order was not filled: " + responseBody);
        }
        long orderId = filled.get("oid").asLong();
        JsonNode orderStatus = queryHyperliquidOrder(api.getAk(), orderId);
        if (!close) {
            saveHyperliquidPosition(request, isBuy, orderId, filled, orderStatus);
        }
        return Map.of("exchange", "hyperliquid", "coin", request.getCoin(),
                "side", isBuy ? "buy" : "sell", "quantity", size, "price", price,
                "reduceOnly", close, "orderStatus", orderStatus, "response", result);
    }

    private JsonNode queryHyperliquidOrder(String accountAddress, long orderId) throws Exception {
        String requestBody = objectMapper.writeValueAsString(Map.of(
                "type", "orderStatus",
                "user", accountAddress.trim(),
                "oid", orderId));
        JsonNode result = requestHyperliquidInfo(requestBody, System.currentTimeMillis());
        JsonNode order = result.get("order");
        String status = text(order, "status");
        log.info("Hyperliquid order status: orderId={}, status={}", orderId, status);
        if (!"order".equalsIgnoreCase(text(result, "status")) || !"filled".equalsIgnoreCase(status)) {
            throw new IllegalStateException(
                    "Hyperliquid order is not filled: orderId=" + orderId + ", response=" + result);
        }
        return result;
    }

    private void saveHyperliquidPosition(OrderRequest request, boolean isBuy, long orderId,
            JsonNode filled, JsonNode orderStatus) throws Exception {
        Integer userId = request.getTemplate() == null ? null : request.getTemplate().getUr();
        if (userId == null) {
            throw new IllegalArgumentException("template.ur is required");
        }
        BigDecimal fillPrice = new BigDecimal(text(filled, "avgPx"));
        BigDecimal fillQuantity = new BigDecimal(text(filled, "totalSz"));
        String key = PositionKey.of(request);
        if (key == null) throw new IllegalArgumentException("position key cannot be created");
        Position position = currentPosition(key);
        position.setUserId(userId);
        position.setCoin(request.getCoin());
        if (isBuy) {
            position.setLongExchange("hyperliquid");
            position.setLongOpenPrice(mergedPrice(
                    position.getLongOpenPrice(), position.getLongQuantity(), fillPrice, fillQuantity));
            position.setLongQuantity(sum(position.getLongQuantity(), fillQuantity));
        } else {
            position.setShortExchange("hyperliquid");
            position.setShortOpenPrice(mergedPrice(
                    position.getShortOpenPrice(), position.getShortQuantity(), fillPrice, fillQuantity));
            position.setShortQuantity(sum(position.getShortQuantity(), fillQuantity));
        }
        savePosition(key, position);
        log.info("Hyperliquid position updated: key={}, coin={}, side={}, fillPrice={}, fillQuantity={}, "
                        + "orderId={}, orderStatus={}",
                key, request.getCoin(), isBuy ? "long" : "short", fillPrice, fillQuantity,
                orderId, text(orderStatus.get("order"), "status"));
    }

    private Position readPosition(String key) {
        String value = stringRedisTemplate.opsForValue().get(key);
        if (blank(value)) return null;
        try {
            return objectMapper.readValue(value, Position.class);
        } catch (Exception e) {
            log.warn("Failed to read existing position from {}; overwrite it", key, e);
            return null;
        }
    }

    private static BigDecimal mergedPrice(BigDecimal currentPrice, BigDecimal currentQuantity,
            BigDecimal fillPrice, BigDecimal fillQuantity) {
        if (currentPrice == null || currentQuantity == null || currentQuantity.signum() <= 0) {
            return fillPrice;
        }
        BigDecimal totalQuantity = currentQuantity.add(fillQuantity);
        return currentPrice.multiply(currentQuantity).add(fillPrice.multiply(fillQuantity))
                .divide(totalQuantity, 18, RoundingMode.HALF_UP)
                .stripTrailingZeros();
    }

    private static BigDecimal sum(BigDecimal current, BigDecimal added) {
        return (current == null ? BigDecimal.ZERO : current).add(added).stripTrailingZeros();
    }

    private Asset loadHyperliquidAsset(String requestedCoin) throws Exception {
        String coin = requestedCoin.trim().toUpperCase(Locale.ROOT);
        long now = System.currentTimeMillis();
        AssetCache cached = hyperliquidAssetCache.get(coin);
        if (cached != null && cached.expiresAt() > now) {
            if (cached.asset() == null) {
                throw new IllegalArgumentException("Hyperliquid coin not found: " + requestedCoin);
            }
            return cached.asset();
        }
        if (now < hyperliquidInfoRetryAt) {
            throw new IllegalStateException("Hyperliquid info request is cooling down after rate limit");
        }

        if (coin.contains(":")) {
            String dex = coin.substring(0, coin.indexOf(':'));
            Asset asset = loadHyperliquidAssetFromDex(dex, coin, 110000, now);
            if (asset != null) {
                hyperliquidAssetCache.put(coin, new AssetCache(asset, now + HYPER_INFO_CACHE_MILLIS));
                return asset;
            }
        } else {
            Asset asset = loadHyperliquidAssetFromDex("", coin, 0, now);
            if (asset != null) {
                hyperliquidAssetCache.put(coin, new AssetCache(asset, now + HYPER_INFO_CACHE_MILLIS));
                return asset;
            }

            JsonNode perpDexes = requestHyperliquidInfo("{\"type\":\"perpDexs\"}", now);
            int dexOffsetIndex = 0;
            for (int i = 1; i < perpDexes.size(); i++) {
                JsonNode dexNode = perpDexes.get(i);
                if (dexNode == null || dexNode.isNull()) continue;
                String dex = text(dexNode, "name");
                if (dex.isBlank()) continue;
                Asset hip3Asset = loadHyperliquidAssetFromDex(dex, dex + ":" + coin,
                        110000 + dexOffsetIndex * 10000, now);
                if (hip3Asset != null) {
                    hyperliquidAssetCache.put(coin, new AssetCache(hip3Asset, now + HYPER_INFO_CACHE_MILLIS));
                    return hip3Asset;
                }
                dexOffsetIndex++;
            }
        }

        hyperliquidAssetCache.put(coin, new AssetCache(null, now + HYPER_INFO_CACHE_MILLIS));
        throw new IllegalArgumentException("Hyperliquid coin not found: " + requestedCoin);
    }

    private Asset loadHyperliquidAssetFromDex(String dex, String requestedCoin, int assetOffset, long now)
            throws Exception {
        String requestBody = objectMapper.writeValueAsString(Map.of(
                "type", "metaAndAssetCtxs",
                "dex", dex));
        JsonNode root = requestHyperliquidInfo(requestBody, now);
        JsonNode universe = root.get(0).get("universe");
        JsonNode contexts = root.get(1);
        for (int i = 0; i < universe.size(); i++) {
            JsonNode row = universe.get(i);
            if (!requestedCoin.equalsIgnoreCase(text(row, "name"))) continue;
            JsonNode context = contexts.get(i);
            String mid = text(context, "midPx");
            if (mid.isBlank()) mid = text(context, "markPx");
            BigDecimal price = new BigDecimal(mid);
            if (price.signum() <= 0) throw new IllegalStateException("Hyperliquid mid price unavailable");
            return new Asset(assetOffset + i, Integer.parseInt(text(row, "szDecimals")),
                    price, dex, text(row, "name"));
        }
        return null;
    }

    private JsonNode requestHyperliquidInfo(String requestBody, long now) throws Exception {
        HttpResponse<String> response = send("https://api.hyperliquid.xyz/info", "POST", requestBody, Map.of());
        if (response.statusCode() == 429) {
            hyperliquidInfoRetryAt = now + HYPER_INFO_RETRY_MILLIS;
        }
        return objectMapper.readTree(requireSuccess(response, "Hyperliquid info"));
    }

    private static boolean isHyperliquid(OrderRequest.ExchangeApi api) {
        return api != null && ("hyperliquid".equalsIgnoreCase(api.getEe())
                || "hyper".equalsIgnoreCase(api.getEe()));
    }

    private static boolean isIbkr(OrderRequest.ExchangeApi api) {
        return api != null && api.getEe() != null && "ibkr".equalsIgnoreCase(api.getEe().trim());
    }

    private Signature signHyperliquidAction(String privateKey, LinkedHashMap<String, Object> action,
            long nonce, long expiresAfter) throws Exception {
        byte[] actionHash = hyperliquidActionHash(action, nonce, expiresAfter);
        byte[] domain = keccak(concat(
                keccak("EIP712Domain(string name,string version,uint256 chainId,address verifyingContract)"
                        .getBytes(StandardCharsets.UTF_8)),
                keccak("Exchange".getBytes(StandardCharsets.UTF_8)),
                keccak("1".getBytes(StandardCharsets.UTF_8)), uint256(1337), address(ZERO_ADDRESS)));
        byte[] struct = keccak(concat(
                keccak("Agent(string source,bytes32 connectionId)".getBytes(StandardCharsets.UTF_8)),
                keccak("a".getBytes(StandardCharsets.UTF_8)), actionHash));
        byte[] digest = keccak(concat(new byte[] {0x19, 0x01}, domain, struct));
        ECKeyPair keyPair = ECKeyPair.create(Numeric.hexStringToByteArray(stripHex(privateKey)));
        Sign.SignatureData signed = Sign.signMessage(digest, keyPair, false);
        return new Signature(
                "0x" + Numeric.toHexStringNoPrefixZeroPadded(new java.math.BigInteger(1, signed.getR()), 64),
                "0x" + Numeric.toHexStringNoPrefixZeroPadded(new java.math.BigInteger(1, signed.getS()), 64),
                signed.getV()[0] & 0xff);
    }

    private static byte[] hyperliquidActionHash(LinkedHashMap<String, Object> action, long nonce,
            long expiresAfter) throws IOException {
        MessageBufferPacker packer = MessagePack.newDefaultBufferPacker();
        packMessagePack(packer, action);
        byte[] encoded = packer.toByteArray();
        packer.close();
        java.nio.ByteBuffer data = java.nio.ByteBuffer.allocate(encoded.length + 18)
                .order(java.nio.ByteOrder.BIG_ENDIAN);
        data.put(encoded).putLong(nonce).put((byte) 0).put((byte) 0).putLong(expiresAfter);
        return keccak(data.array());
    }

    private static void packMessagePack(MessagePacker packer, Object value) throws IOException {
        if (value instanceof Map<?, ?> map) {
            packer.packMapHeader(map.size());
            for (Map.Entry<?, ?> entry : map.entrySet()) {
                packMessagePack(packer, entry.getKey());
                packMessagePack(packer, entry.getValue());
            }
        } else if (value instanceof List<?> list) {
            packer.packArrayHeader(list.size());
            for (Object item : list) packMessagePack(packer, item);
        } else if (value instanceof String string) packer.packString(string);
        else if (value instanceof Boolean bool) packer.packBoolean(bool);
        else if (value instanceof Integer integer) packer.packInt(integer);
        else if (value instanceof Long longValue) packer.packLong(longValue);
        else throw new IllegalArgumentException("Unsupported MessagePack value: " + value);
    }

    private static byte[] uint256(long value) {
        byte[] result = new byte[32];
        ByteBuffer.wrap(result, 24, 8).putLong(value);
        return result;
    }

    private static byte[] address(String value) {
        byte[] raw = Numeric.hexStringToByteArray(stripHex(value));
        byte[] result = new byte[32];
        System.arraycopy(raw, 0, result, 32 - raw.length, raw.length);
        return result;
    }

    private static byte[] keccak(byte[] value) { return org.web3j.crypto.Hash.sha3(value); }

    private static byte[] concat(byte[]... values) {
        int size = 0;
        for (byte[] value : values) size += value.length;
        byte[] result = new byte[size];
        int offset = 0;
        for (byte[] value : values) {
            System.arraycopy(value, 0, result, offset, value.length);
            offset += value.length;
        }
        return result;
    }

    private static String stripHex(String value) {
        String result = value == null ? "" : value.trim();
        return result.startsWith("0x") || result.startsWith("0X") ? result.substring(2) : result;
    }

    private static void requireAddress(String value, String name) {
        if (value == null || !value.trim().matches("(?i)^0x[0-9a-f]{40}$")) {
            throw new IllegalArgumentException(name + " must be a 42-character hex address");
        }
    }

    private static String text(JsonNode node, String name) {
        return node != null && node.has(name) && !node.get(name).isNull() ? node.get(name).asText() : "";
    }

    private record Asset(int assetId, int szDecimals, BigDecimal midPrice,
            String dex, String marketName) {}

    private record AssetCache(Asset asset, long expiresAt) {}

    private record LegPosition(String exchange, BigDecimal openPrice, BigDecimal quantity) {}

    private record Signature(String r, String s, int v) {
        Map<String, Object> toMap() { return Map.of("r", r, "s", s, "v", v); }
    }
}
