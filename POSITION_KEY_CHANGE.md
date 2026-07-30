# 仓位 Redis Key 改造

## 新 Key 格式

仓位信息现在按用户、基础币和多空交易所分别存储：

```text
qiqi:position:<用户ID>:<基础币>:<多头交易所>:<空头交易所>
```

示例：

```text
qiqi:position:120:QNT:hyperliquid:binance
```

## 改造内容

- 开仓前读取对应策略的仓位。
- 开仓、平仓后写入对应策略的仓位。
- IBKR 和 Hyperliquid 成交后写入对应策略的仓位。
- 异步仓位查询使用完整策略 key 去重。
- 不同交易所组合的同一用户策略互不覆盖。

## Key 统一规则

- 基础币统一为大写，例如 `qnt` 转为 `QNT`。
- `hyper` 统一为 `hyperliquid`。
- `gateio` 统一为 `gate`。
- 已包含交易对后缀时，生成 key 前提取基础币名称。

## 旧 Key

旧格式：

```text
qiqi:positon:<用户ID>
```

代码已不再读取或写入旧格式。旧数据不会自动迁移到新 key。
