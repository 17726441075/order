# Order API

## 普通下单接口

### 请求

```http
POST /order
Content-Type: application/json
```

请求体接收原始 JSON，不绑定固定实体类。例如：

```json
{
  "coin": "AAPL",
  "longExchange": "IBKR",
  "shortExchange": "OKX",
  "openCha": 1.23
}
```

### 成功响应

```json
{
  "success": true,
  "message": "order request received"
}
```

### 空请求体响应

HTTP 状态码：`400`

```json
{
  "success": false,
  "message": "request JSON cannot be empty"
}
```

### curl 测试

```bash
curl -i -X POST "http://127.0.0.1:8081/order" \
  -H "Content-Type: application/json" \
  --data-raw '{"coin":"AAPL","longExchange":"IBKR","shortExchange":"OKX","openCha":1.23}'
```

## Test 用户下单接口

```http
POST /test/order
Content-Type: application/json
```

该接口与普通下单接口一样接收原始 JSON，但使用独立的 test 用户下单逻辑。
