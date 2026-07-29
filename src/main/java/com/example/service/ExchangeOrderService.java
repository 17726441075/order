package com.example.service;

import java.io.IOException;
import java.math.BigDecimal;
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
import java.util.Base64;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.msgpack.core.MessageBufferPacker;
import org.msgpack.core.MessagePack;
import org.msgpack.core.MessagePacker;
import org.springframework.stereotype.Service;
import org.web3j.crypto.ECKeyPair;
import org.web3j.crypto.Sign;
import org.web3j.utils.Numeric;

import com.example.entity.OrderRequest;
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
    private final ObjectMapper objectMapper;
    private final Map<String, AssetCache> hyperliquidAssetCache = new ConcurrentHashMap<>();
    private volatile long hyperliquidInfoRetryAt;

    public Map<String, Object> placeOpenOrders(OrderRequest request) throws Exception {
        if (request == null || request.getTemplate() == null || request.getTemplate().getUs() == null
                || request.getTemplate().getUs().signum() <= 0) {
            throw new IllegalArgumentException("template.us must be greater than 0");
        }
        Map<String, Object> result = new LinkedHashMap<>();
        Map<String, Object> hyperliquidResult = placeHyperliquidIfNeeded(request);
        if (!"no hyperliquid leg".equals(hyperliquidResult.get("message"))) {
            result.put("hyperliquid", hyperliquidResult);
        }
        placeLeg(request.getCoin(), request.getTemplate().getUs(), request.getLongApi(), true, result);
        placeLeg(request.getCoin(), request.getTemplate().getUs(), request.getShortApi(), false, result);
        return result;
    }

    private void placeLeg(String coin, BigDecimal notional, OrderRequest.ExchangeApi api, boolean isLong,
            Map<String, Object> result) throws Exception {
        if (api == null || blank(api.getEe())) return;
        String exchange = api.getEe().trim().toLowerCase(Locale.ROOT);
        BigDecimal size = notional;
        String response = switch (exchange) {
            case "okx" -> orderOkx(coin, size, api, isLong);
            case "binance" -> orderBinance(coin, size, api, isLong);
            case "bybit" -> orderBybit(coin, size, api, isLong);
            case "bitget" -> orderBitget(coin, size, api, isLong);
            case "gate", "gateio" -> orderGate(coin, size, api, isLong);
            default -> null;
        };
        if (response != null) result.put((isLong ? "long" : "short") + exchange, response);
    }

    private String orderOkx(String coin, BigDecimal size, OrderRequest.ExchangeApi api, boolean isLong)
            throws Exception {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("instId", coin);
        body.put("ordType", "market");
        body.put("side", isLong ? "buy" : "sell");
        body.put("posSide", isLong ? "long" : "short");
        body.put("tdMode", "cross");
        body.put("tag", "1311e341f73eSUDE");
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
        return requireSuccess(response, "OKX");
    }

    private String orderBinance(String coin, BigDecimal size, OrderRequest.ExchangeApi api, boolean isLong)
            throws Exception {
        Map<String, String> params = new LinkedHashMap<>();
        params.put("symbol", coin);
        params.put("side", isLong ? "BUY" : "SELL");
        params.put("positionSide", isLong ? "LONG" : "SHORT");
        params.put("type", "MARKET");
        params.put("newClientOrderId", "order" + System.currentTimeMillis());
        params.put("quantity", size.stripTrailingZeros().toPlainString());
        params.put("timestamp", String.valueOf(System.currentTimeMillis()));
        String query = query(params);
        String signedQuery = query + "&signature="
                + hmacHex("HmacSHA256", required(api.getAc(), "Binance secretKey"), query);
        HttpResponse<String> response = send("https://papi.binance.com/papi/v1/um/order?" + signedQuery,
                "POST", "", Map.of("X-MBX-APIKEY", required(api.getAk(), "Binance apiKey")));
        return requireSuccess(response, "Binance");
    }

    private String orderBybit(String coin, BigDecimal size, OrderRequest.ExchangeApi api, boolean isLong)
            throws Exception {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("category", "linear");
        body.put("symbol", coin);
        body.put("side", isLong ? "Buy" : "Sell");
        body.put("orderType", "Market");
        body.put("qty", size.stripTrailingZeros().toPlainString());
        body.put("timeInForce", "IOC");
        body.put("positionIdx", isLong ? 1 : 2);
        body.put("reduceOnly", false);
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
        return requireSuccess(response, "Bybit");
    }

    private String orderBitget(String coin, BigDecimal size, OrderRequest.ExchangeApi api, boolean isLong)
            throws Exception {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("symbol", coin);
        body.put("productType", "USDT-FUTURES");
        body.put("size", size.stripTrailingZeros().toPlainString());
        body.put("marginMode", "crossed");
        body.put("marginCoin", "USDT");
        body.put("side", isLong ? "buy" : "sell");
        body.put("tradeSide", "open");
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
        return requireSuccess(response, "Bitget");
    }

    private String orderGate(String coin, BigDecimal size, OrderRequest.ExchangeApi api, boolean isLong)
            throws Exception {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("contract", coin);
        body.put("size", (isLong ? size : size.negate()).stripTrailingZeros().toPlainString());
        body.put("price", "0");
        body.put("tif", "ioc");
        body.put("reduce_only", false);
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
        boolean orderRequest = url.contains("/order") || url.endsWith("/exchange");
        if (orderRequest) {
            log.info("Order request: method={}, url={}, body={}", method, url, body);
        }
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
        if (orderRequest) {
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

    private Map<String, Object> placeHyperliquidIfNeeded(OrderRequest request) throws Exception {
        OrderRequest.ExchangeApi api = null;
        boolean isBuy = false;
        if (isHyperliquid(request.getShortApi())) {
            api = request.getShortApi();
        } else if (isHyperliquid(request.getLongApi())) {
            api = request.getLongApi();
            isBuy = true;
        }
        if (api == null) return Map.of("message", "no hyperliquid leg");
        if (blank(api.getAk()) || blank(api.getAc()) || blank(api.getAp())) {
            throw new IllegalArgumentException("Hyperliquid ak, ac and ap are required");
        }
        requireAddress(api.getAk(), "Hyperliquid account address");
        requireAddress(api.getAp(), "Hyperliquid agent address");
        if (request.getTemplate() == null || request.getTemplate().getUs() == null
                || request.getTemplate().getUs().signum() <= 0) {
            throw new IllegalArgumentException("template.us must be greater than 0");
        }

        Asset asset = loadHyperliquidAsset(request.getCoin());
        BigDecimal quantity = request.getTemplate().getUs().divide(asset.midPrice(), 18, RoundingMode.DOWN)
                .setScale(asset.szDecimals(), RoundingMode.DOWN);
        if (quantity.signum() <= 0) throw new IllegalArgumentException("template.us is too small");
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
        order.put("r", false);
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
        return Map.of("exchange", "hyperliquid", "coin", request.getCoin(),
                "side", isBuy ? "buy" : "sell", "quantity", size, "price", price, "response", result);
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
            return new Asset(assetOffset + i, Integer.parseInt(text(row, "szDecimals")), price);
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
        return api != null && "hyperliquid".equalsIgnoreCase(api.getEe());
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

    private record Asset(int assetId, int szDecimals, BigDecimal midPrice) {}

    private record AssetCache(Asset asset, long expiresAt) {}

    private record Signature(String r, String s, int v) {
        Map<String, Object> toMap() { return Map.of("r", r, "s", s, "v", v); }
    }
}
