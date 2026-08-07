package com.example.service;

import java.io.IOException;
import java.math.BigInteger;
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
import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;

import com.example.entity.OrderRequest;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.msgpack.core.MessageBufferPacker;
import org.msgpack.core.MessagePack;
import org.msgpack.core.MessagePacker;
import org.springframework.stereotype.Service;
import org.web3j.crypto.ECKeyPair;
import org.web3j.crypto.Sign;
import org.web3j.utils.Numeric;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

@Slf4j
@Service
@RequiredArgsConstructor
public class ExchangeApiValidationService {
    private static final String ZERO_ADDRESS = "0x0000000000000000000000000000000000000000";
    private static final DateTimeFormatter OKX_TIME = DateTimeFormatter
            .ofPattern("yyyy-MM-dd'T'HH:mm:ss.SSS'Z'").withZone(ZoneOffset.UTC);
    private static final HttpClient HTTP_CLIENT = HttpClient.newBuilder()
            .connectTimeout(Duration.ofSeconds(8)).build();

    private final ObjectMapper objectMapper;

    public void validate(OrderRequest request) throws Exception {
        if (request == null) {
            throw new IllegalArgumentException("order request is required");
        }
        OrderRequestPolicy.validateIbkrUser(request);
        validateApi("longApi", request.getLongApi());
        validateApi("shortApi", request.getShortApi());
    }

    private void validateApi(String side, OrderRequest.ExchangeApi api) throws Exception {
        if (api == null) {
            throw new IllegalArgumentException(side + " is required");
        }
        String exchange = required(api.getEe(), side + ".ee").trim().toLowerCase(Locale.ROOT);
        switch (exchange) {
            case "ibkr" -> {
            }
            case "okx" -> validateOkx(side, api);
            case "binance" -> validateBinance(side, api);
            case "bybit" -> validateBybit(side, api);
            case "bitget" -> validateBitget(side, api);
            case "gate", "gateio" -> validateGate(side, api);
            case "hyper", "hyperliquid" -> validateHyperliquid(side, api);
            default -> throw new IllegalArgumentException("Unsupported exchange for API validation: " + api.getEe());
        }
    }

    private void validateOkx(String side, OrderRequest.ExchangeApi api) throws Exception {
        String path = "/api/v5/account/balance";
        String timestamp = OKX_TIME.format(Instant.now());
        HttpResponse<String> response = send("https://www.okx.com" + path, "GET", "", Map.of(
                "OK-ACCESS-KEY", required(api.getAk(), "OKX apiKey"),
                "OK-ACCESS-SIGN", hmacBase64("HmacSHA256", required(api.getAc(), "OKX secretKey"),
                        timestamp + "GET" + path),
                "OK-ACCESS-TIMESTAMP", timestamp,
                "OK-ACCESS-PASSPHRASE", required(api.getAp(), "OKX passphrase")));
        String body = requireJsonCode(response, "OKX API validation", "code", "0");
        logValidation(side, "okx", body);
    }

    private void validateBinance(String side, OrderRequest.ExchangeApi api) throws Exception {
        Map<String, String> params = new LinkedHashMap<>();
        params.put("recvWindow", "5000");
        params.put("timestamp", String.valueOf(System.currentTimeMillis()));
        String query = query(params);
        String signedQuery = query + "&signature="
                + hmacHex("HmacSHA256", required(api.getAc(), "Binance secretKey"), query);
        HttpResponse<String> response = send("https://fapi.binance.com/fapi/v3/account?" + signedQuery,
                "GET", "", Map.of("X-MBX-APIKEY", required(api.getAk(), "Binance apiKey")));
        String body = requireSuccess(response, "Binance API validation");
        JsonNode root = objectMapper.readTree(body);
        if (!root.has("totalMarginBalance") && !root.has("assets")) {
            throw new IllegalStateException("Binance API validation response missing account balance: " + body);
        }
        logValidation(side, "binance", body);
    }

    private void validateBybit(String side, OrderRequest.ExchangeApi api) throws Exception {
        String query = "accountType=UNIFIED&coin=USDT";
        String timestamp = String.valueOf(System.currentTimeMillis());
        String key = required(api.getAk(), "Bybit apiKey");
        String window = "5000";
        HttpResponse<String> response = send(
                "https://api.bybit.com/v5/account/wallet-balance?" + query, "GET", "", Map.of(
                        "X-BAPI-SIGN", hmacHex("HmacSHA256", required(api.getAc(), "Bybit secretKey"),
                                timestamp + key + window + query),
                        "X-BAPI-API-KEY", key,
                        "X-BAPI-TIMESTAMP", timestamp,
                        "X-BAPI-RECV-WINDOW", window));
        String body = requireJsonCode(response, "Bybit API validation", "retCode", "0");
        logValidation(side, "bybit", body);
    }

    private void validateBitget(String side, OrderRequest.ExchangeApi api) throws Exception {
        String path = "/api/v2/mix/account/accounts";
        String query = "productType=USDT-FUTURES";
        String timestamp = String.valueOf(System.currentTimeMillis());
        HttpResponse<String> response = send("https://api.bitget.com" + path + "?" + query,
                "GET", "", Map.of(
                        "ACCESS-KEY", required(api.getAk(), "Bitget apiKey"),
                        "ACCESS-SIGN", hmacBase64("HmacSHA256", required(api.getAc(), "Bitget secretKey"),
                                timestamp + "GET" + path + "?" + query),
                        "ACCESS-TIMESTAMP", timestamp,
                        "ACCESS-PASSPHRASE", required(api.getAp(), "Bitget passphrase"),
                        "locale", "zh-CN",
                        "Content-Type", "application/json"));
        String body = requireJsonCode(response, "Bitget API validation", "code", "00000");
        logValidation(side, "bitget", body);
    }

    private void validateGate(String side, OrderRequest.ExchangeApi api) throws Exception {
        String path = "/api/v4/wallet/total_balance";
        String query = "";
        String timestamp = String.valueOf(System.currentTimeMillis() / 1000);
        String preSign = "GET\n" + path + "\n" + query + "\n" + sha512("") + "\n" + timestamp;
        HttpResponse<String> response = send("https://api.gateio.ws" + path, "GET", "", Map.of(
                "KEY", required(api.getAk(), "Gate apiKey"),
                "Timestamp", timestamp,
                "SIGN", hmacHex("HmacSHA512", required(api.getAc(), "Gate secretKey"), preSign)));
        String body = requireSuccess(response, "Gate API validation");
        logValidation(side, "gate", body);
    }

    private void validateHyperliquid(String side, OrderRequest.ExchangeApi api) throws Exception {
        requireAddress(api.getAk(), "Hyperliquid account address");
        String accountAddress = api.getAk().trim();
        String balanceRequest = objectMapper.writeValueAsString(Map.of(
                "type", "clearinghouseState",
                "user", accountAddress));
        HttpResponse<String> balanceResponse = send("https://api.hyperliquid.xyz/info",
                "POST", balanceRequest, Map.of());
        String balanceBody = requireSuccess(balanceResponse, "Hyperliquid balance validation");
        JsonNode balance = objectMapper.readTree(balanceBody);
        JsonNode summary = balance.get("marginSummary");
        if (summary == null || summary.isNull() || !summary.has("accountValue")) {
            throw new IllegalStateException("Hyperliquid balance response missing accountValue: " + balanceBody);
        }
        logValidation(side, "hyperliquid", balanceBody);

        if (!blank(api.getAc()) || !blank(api.getAp())) {
            validateHyperliquidSigner(side, api);
        }
    }

    private void validateHyperliquidSigner(String side, OrderRequest.ExchangeApi api) throws Exception {
        String privateKey = required(api.getAc(), "Hyperliquid private key");
        requireAddress(api.getAp(), "Hyperliquid agent address");
        LinkedHashMap<String, Object> action = new LinkedHashMap<>();
        action.put("type", "noop");
        long nonce = System.currentTimeMillis();
        long expiresAfter = nonce + 30_000L;
        Signature signature = signHyperliquidAction(privateKey, action, nonce, expiresAfter);
        LinkedHashMap<String, Object> body = new LinkedHashMap<>();
        body.put("action", action);
        body.put("nonce", nonce);
        body.put("signature", signature.toMap());
        body.put("expiresAfter", expiresAfter);
        String requestBody = objectMapper.writeValueAsString(body);
        HttpResponse<String> response = send("https://api.hyperliquid.xyz/exchange",
                "POST", requestBody, Map.of());
        String responseBody = requireSuccess(response, "Hyperliquid signer validation");
        JsonNode result = objectMapper.readTree(responseBody);
        if (!"ok".equalsIgnoreCase(text(result, "status"))) {
            throw new IllegalStateException("Hyperliquid signer validation rejected: " + responseBody);
        }
        logValidation(side, "hyperliquid-noop", responseBody);
    }

    private void logValidation(String side, String exchange, String body) {
        log.info("API validation response: side={}, exchange={}, body={}", side, exchange, body);
    }

    private HttpResponse<String> send(String url, String method, String body, Map<String, String> headers)
            throws Exception {
        HttpRequest.Builder builder = HttpRequest.newBuilder().uri(URI.create(url)).timeout(Duration.ofSeconds(10));
        headers.forEach(builder::header);
        if ("POST".equals(method)) {
            builder.header("Content-Type", "application/json")
                    .POST(HttpRequest.BodyPublishers.ofString(body, StandardCharsets.UTF_8));
        } else {
            builder.GET();
        }
        return HTTP_CLIENT.send(builder.build(), HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8));
    }

    private String requireJsonCode(HttpResponse<String> response, String exchange,
            String codeField, String successCode) throws Exception {
        String body = requireSuccess(response, exchange);
        JsonNode result = objectMapper.readTree(body);
        if (!successCode.equals(text(result, codeField))) {
            throw new IllegalStateException(exchange + " rejected: " + body);
        }
        return body;
    }

    private static String requireSuccess(HttpResponse<String> response, String exchange) {
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
                "0x" + Numeric.toHexStringNoPrefixZeroPadded(new BigInteger(1, signed.getR()), 64),
                "0x" + Numeric.toHexStringNoPrefixZeroPadded(new BigInteger(1, signed.getS()), 64),
                signed.getV()[0] & 0xff);
    }

    private static byte[] hyperliquidActionHash(LinkedHashMap<String, Object> action, long nonce,
            long expiresAfter) throws IOException {
        MessageBufferPacker packer = MessagePack.newDefaultBufferPacker();
        packMessagePack(packer, action);
        byte[] encoded = packer.toByteArray();
        packer.close();
        ByteBuffer data = ByteBuffer.allocate(encoded.length + 18).order(ByteOrder.BIG_ENDIAN);
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

    private static String stripHex(String value) {
        String result = value == null ? "" : value.trim();
        return result.startsWith("0x") || result.startsWith("0X") ? result.substring(2) : result;
    }

    private static void requireAddress(String value, String name) {
        if (value == null || !value.trim().matches("(?i)^0x[0-9a-f]{40}$")) {
            throw new IllegalArgumentException(name + " must be a 42-character hex address");
        }
    }

    private static String required(String value, String name) {
        if (blank(value)) throw new IllegalArgumentException(name + " is required");
        return value;
    }

    private static boolean blank(String value) {
        return value == null || value.isBlank();
    }

    private static String text(JsonNode node, String name) {
        return node != null && node.has(name) && !node.get(name).isNull() ? node.get(name).asText() : "";
    }

    private record Signature(String r, String s, int v) {
        Map<String, Object> toMap() { return Map.of("r", r, "s", s, "v", v); }
    }
}
