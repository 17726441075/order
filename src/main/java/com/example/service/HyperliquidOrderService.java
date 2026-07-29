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
import java.time.Duration;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.msgpack.core.MessagePack;
import org.msgpack.core.MessageBufferPacker;
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
public class HyperliquidOrderService {
    private static final String INFO_URL = "https://api.hyperliquid.xyz/info";
    private static final String EXCHANGE_URL = "https://api.hyperliquid.xyz/exchange";
    private static final String ZERO_ADDRESS = "0x0000000000000000000000000000000000000000";
    private static final HttpClient HTTP_CLIENT = HttpClient.newBuilder()
            .connectTimeout(Duration.ofSeconds(8))
            .build();

    private final ObjectMapper objectMapper;

    public Map<String, Object> placeIfNeeded(OrderRequest request) throws Exception {
        Leg leg = hyperliquidLeg(request);
        if (leg == null) {
            return Map.of("message", "no hyperliquid leg");
        }
        return placeMarketOrder(request.getCoin(), leg.api(), leg.isBuy(), request.getTemplate());
    }

    private Map<String, Object> placeMarketOrder(
            String coin, OrderRequest.ExchangeApi api, boolean isBuy, OrderRequest.OrderTemplate template)
            throws Exception {
        if (api == null || blank(api.getAk()) || blank(api.getAc())) {
            throw new IllegalArgumentException("Hyperliquid ak and ac are required");
        }
        requireAddress(api.getAk(), "Hyperliquid account address");
        if (template == null || template.getUs() == null || template.getUs().signum() <= 0) {
            throw new IllegalArgumentException("template.us must be greater than 0");
        }

        Asset asset = loadAsset(coin);
        BigDecimal referencePrice = asset.midPrice();
        BigDecimal quantity = template.getUs().divide(referencePrice, 18, RoundingMode.DOWN)
                .setScale(asset.szDecimals(), RoundingMode.DOWN);
        if (quantity.signum() <= 0) {
            throw new IllegalArgumentException("template.us is too small for Hyperliquid size precision");
        }

        BigDecimal marketPrice = referencePrice.multiply(
                isBuy ? new BigDecimal("1.05") : new BigDecimal("0.95"));
        int maxPriceDecimals = Math.max(0, 6 - asset.szDecimals());
        String price = marketPrice.round(new MathContext(5, RoundingMode.HALF_UP))
                .setScale(Math.min(maxPriceDecimals, Math.max(0, marketPrice.scale())), RoundingMode.HALF_UP)
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
        Signature signature = signAction(api.getAc(), action, nonce, expiresAfter);
        LinkedHashMap<String, Object> body = new LinkedHashMap<>();
        body.put("action", action);
        body.put("nonce", nonce);
        body.put("signature", signature.toMap());
        body.put("expiresAfter", expiresAfter);

        HttpResponse<String> response = post(EXCHANGE_URL, objectMapper.writeValueAsString(body));
        if (response.statusCode() < 200 || response.statusCode() >= 300) {
            throw new IllegalStateException("Hyperliquid HTTP status: " + response.statusCode());
        }
        JsonNode result = objectMapper.readTree(response.body());
        String status = text(result, "status");
        if (!"ok".equalsIgnoreCase(status)) {
            throw new IllegalStateException("Hyperliquid order rejected: " + response.body());
        }

        Map<String, Object> output = new LinkedHashMap<>();
        output.put("exchange", "hyperliquid");
        output.put("coin", coin);
        output.put("side", isBuy ? "buy" : "sell");
        output.put("quantity", size);
        output.put("price", price);
        output.put("response", result);
        return output;
    }

    private Asset loadAsset(String requestedCoin) throws Exception {
        HttpResponse<String> response = post(INFO_URL, "{\"type\":\"metaAndAssetCtxs\"}");
        if (response.statusCode() != 200) {
            throw new IllegalStateException("Hyperliquid info HTTP status: " + response.statusCode());
        }
        JsonNode root = objectMapper.readTree(response.body());
        JsonNode universe = root.get(0).get("universe");
        JsonNode contexts = root.get(1);
        String coin = requestedCoin.toUpperCase(Locale.ROOT);
        for (int i = 0; i < universe.size(); i++) {
            JsonNode row = universe.get(i);
            if (!coin.equals(text(row, "name"))) {
                continue;
            }
            JsonNode context = contexts.get(i);
            String mid = text(context, "midPx");
            if (mid.isBlank()) {
                mid = text(context, "markPx");
            }
            BigDecimal price = new BigDecimal(mid);
            if (price.signum() <= 0) {
                throw new IllegalStateException("Hyperliquid mid price is unavailable");
            }
            return new Asset(i, integer(row, "szDecimals"), price);
        }
        throw new IllegalArgumentException("Hyperliquid coin is not found: " + requestedCoin);
    }

    private static Leg hyperliquidLeg(OrderRequest request) {
        if (request == null) {
            return null;
        }
        if (isHyperliquid(request.getShortApi())) {
            return new Leg(request.getShortApi(), false);
        }
        if (isHyperliquid(request.getLongApi())) {
            return new Leg(request.getLongApi(), true);
        }
        return null;
    }

    private static boolean isHyperliquid(OrderRequest.ExchangeApi api) {
        return api != null && "hyperliquid".equalsIgnoreCase(api.getEe());
    }

    private Signature signAction(String privateKey, LinkedHashMap<String, Object> action, long nonce, long expiresAfter)
            throws Exception {
        byte[] actionHash = actionHash(action, nonce, expiresAfter);
        byte[] domain = domainSeparator();
        byte[] struct = keccak(concat(
                keccak("Agent(string source,bytes32 connectionId)".getBytes(StandardCharsets.UTF_8)),
                keccak("a".getBytes(StandardCharsets.UTF_8)),
                actionHash));
        byte[] digest = keccak(concat(new byte[] {0x19, 0x01}, domain, struct));
        ECKeyPair keyPair = ECKeyPair.create(Numeric.hexStringToByteArray(stripHex(privateKey)));
        Sign.SignatureData signed = Sign.signMessage(digest, keyPair, false);
        return new Signature(
                "0x" + Numeric.toHexStringNoPrefixZeroPadded(new java.math.BigInteger(1, signed.getR()), 64),
                "0x" + Numeric.toHexStringNoPrefixZeroPadded(new java.math.BigInteger(1, signed.getS()), 64),
                signed.getV()[0] & 0xff);
    }

    private static byte[] actionHash(LinkedHashMap<String, Object> action, long nonce, long expiresAfter)
            throws IOException {
        MessageBufferPacker packer = MessagePack.newDefaultBufferPacker();
        packValue(packer, action);
        byte[] base = packer.toByteArray();
        packer.close();
        ByteBuffer buffer = ByteBuffer.allocate(base.length + 1 + 8 + 1 + 8).order(ByteOrder.BIG_ENDIAN);
        buffer.put(base).putLong(nonce).put((byte) 0).put((byte) 0).putLong(expiresAfter);
        return keccak(buffer.array());
    }

    @SuppressWarnings("unchecked")
    private static void packValue(MessagePacker packer, Object value) throws IOException {
        if (value instanceof Map<?, ?> map) {
            packer.packMapHeader(map.size());
            for (Map.Entry<?, ?> entry : map.entrySet()) {
                packValue(packer, entry.getKey());
                packValue(packer, entry.getValue());
            }
        } else if (value instanceof List<?> list) {
            packer.packArrayHeader(list.size());
            for (Object item : list) packValue(packer, item);
        } else if (value instanceof String string) {
            packer.packString(string);
        } else if (value instanceof Boolean bool) {
            packer.packBoolean(bool);
        } else if (value instanceof Integer integer) {
            packer.packInt(integer);
        } else if (value instanceof Long longValue) {
            packer.packLong(longValue);
        } else {
            throw new IllegalArgumentException("Unsupported MessagePack value: " + value);
        }
    }

    private static byte[] domainSeparator() {
        return keccak(concat(
                keccak("EIP712Domain(string name,string version,uint256 chainId,address verifyingContract)"
                        .getBytes(StandardCharsets.UTF_8)),
                keccak("Exchange".getBytes(StandardCharsets.UTF_8)),
                keccak("1".getBytes(StandardCharsets.UTF_8)),
                uint256(1337),
                address(ZERO_ADDRESS)));
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

    private static byte[] keccak(byte[] value) {
        return org.web3j.crypto.Hash.sha3(value);
    }

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

    private HttpResponse<String> post(String url, String body) throws Exception {
        HttpRequest request = HttpRequest.newBuilder(URI.create(url))
                .timeout(Duration.ofSeconds(10))
                .header("Content-Type", "application/json")
                .POST(HttpRequest.BodyPublishers.ofString(body, StandardCharsets.UTF_8))
                .build();
        return HTTP_CLIENT.send(request, HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8));
    }

    private static String stripHex(String value) {
        String result = value == null ? "" : value.trim();
        return result.startsWith("0x") || result.startsWith("0X") ? result.substring(2) : result;
    }

    private static boolean blank(String value) { return value == null || value.isBlank(); }

    private static void requireAddress(String value, String name) {
        if (value == null || !value.trim().matches("(?i)^0x[0-9a-f]{40}$")) {
            throw new IllegalArgumentException(name + " must be a 42-character hex address");
        }
    }

    private static String text(JsonNode node, String name) {
        return node != null && node.has(name) && !node.get(name).isNull() ? node.get(name).asText() : "";
    }

    private static int integer(JsonNode node, String name) { return Integer.parseInt(text(node, name)); }

    private record Asset(int assetId, int szDecimals, BigDecimal midPrice) {}

    private record Leg(OrderRequest.ExchangeApi api, boolean isBuy) {}

    private record Signature(String r, String s, int v) {
        Map<String, Object> toMap() { return Map.of("r", r, "s", s, "v", v); }
    }
}
