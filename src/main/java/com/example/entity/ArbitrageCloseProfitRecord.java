package com.example.entity;

import java.math.BigDecimal;
import java.time.LocalDateTime;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableLogic;
import com.baomidou.mybatisplus.annotation.TableName;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
@TableName("t_arbitrage_close_profit_record")
public class ArbitrageCloseProfitRecord {
    @TableId(type = IdType.AUTO)
    private Long id;
    private Long userId;
    private String coin;
    private String longExchange;
    private String shortExchange;
    private String requestId;
    private BigDecimal longProfit;
    private BigDecimal shortProfit;
    private BigDecimal closeProfit;
    private Long closedAt;
    private LocalDateTime createTime;
    private LocalDateTime updateTime;
    @TableLogic
    private Integer isDeleted;
}
