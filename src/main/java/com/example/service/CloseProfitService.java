package com.example.service;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.LocalDateTime;
import java.util.UUID;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.stereotype.Service;

import com.example.entity.ArbitrageCloseProfitRecord;
import com.example.entity.Position;
import com.example.mapper.ArbitrageCloseProfitRecordMapper;

@Slf4j
@Service
@RequiredArgsConstructor
public class CloseProfitService {
    private static final int PROFIT_SCALE = 8;

    private final ArbitrageCloseProfitRecordMapper profitRecordMapper;

    public void prepareClose(Position position) {
        if (position == null || hasText(position.getCloseRequestId())) {
            return;
        }
        String coin = PositionKey.baseCoin(position.getCoin());
        position.setCloseRequestId("close-" + coin + "-" + System.currentTimeMillis()
                + "-" + UUID.randomUUID().toString().substring(0, 8));
        position.setCloseLongOpenPrice(position.getLongOpenPrice());
        position.setCloseShortOpenPrice(position.getShortOpenPrice());
        position.setCloseLongQuantity(position.getLongQuantity());
        position.setCloseShortQuantity(position.getShortQuantity());
        position.setLongClosePrice(null);
        position.setShortClosePrice(null);
        position.setProfitRecorded(false);
    }

    public boolean recordIfReady(Position position) {
        if (!isReady(position)) {
            return false;
        }
        BigDecimal longProfit = position.getLongClosePrice()
                .subtract(position.getCloseLongOpenPrice())
                .multiply(position.getCloseLongQuantity())
                .setScale(PROFIT_SCALE, RoundingMode.HALF_UP);
        BigDecimal shortProfit = position.getCloseShortOpenPrice()
                .subtract(position.getShortClosePrice())
                .multiply(position.getCloseShortQuantity())
                .setScale(PROFIT_SCALE, RoundingMode.HALF_UP);
        BigDecimal closeProfit = longProfit.add(shortProfit)
                .setScale(PROFIT_SCALE, RoundingMode.HALF_UP);
        long closedAt = System.currentTimeMillis();
        LocalDateTime now = LocalDateTime.now();
        ArbitrageCloseProfitRecord record = ArbitrageCloseProfitRecord.builder()
                .userId(position.getUserId().longValue())
                .coin(PositionKey.baseCoin(position.getCoin()))
                .longExchange(PositionKey.normalizeExchange(position.getLongExchange()))
                .shortExchange(PositionKey.normalizeExchange(position.getShortExchange()))
                .requestId(position.getCloseRequestId())
                .longProfit(longProfit)
                .shortProfit(shortProfit)
                .closeProfit(closeProfit)
                .closedAt(closedAt)
                .createTime(now)
                .updateTime(now)
                .isDeleted(0)
                .build();
        try {
            profitRecordMapper.insert(record);
            position.setProfitRecorded(true);
            log.info("Arbitrage close profit saved: userId={}, coin={}, requestId={}, "
                            + "longProfit={}, shortProfit={}, closeProfit={}",
                    position.getUserId(), record.getCoin(), record.getRequestId(),
                    longProfit, shortProfit, closeProfit);
            return true;
        } catch (DuplicateKeyException e) {
            position.setProfitRecorded(true);
            log.info("Arbitrage close profit already saved: userId={}, requestId={}",
                    position.getUserId(), position.getCloseRequestId());
            return true;
        } catch (Exception e) {
            log.error("Failed to save arbitrage close profit: userId={}, coin={}, requestId={}",
                    position.getUserId(), position.getCoin(), position.getCloseRequestId(), e);
            return false;
        }
    }

    private static boolean isReady(Position position) {
        return position != null
                && !Boolean.TRUE.equals(position.getProfitRecorded())
                && "CLOSED".equalsIgnoreCase(position.getStatus())
                && position.getUserId() != null
                && hasText(position.getCoin())
                && hasText(position.getLongExchange())
                && hasText(position.getShortExchange())
                && hasText(position.getCloseRequestId())
                && positive(position.getCloseLongOpenPrice())
                && positive(position.getCloseShortOpenPrice())
                && positive(position.getCloseLongQuantity())
                && positive(position.getCloseShortQuantity())
                && positive(position.getLongClosePrice())
                && positive(position.getShortClosePrice());
    }

    private static boolean positive(BigDecimal value) {
        return value != null && value.signum() > 0;
    }

    private static boolean hasText(String value) {
        return value != null && !value.isBlank();
    }
}
