package com.matching.model;

import java.math.BigDecimal;

public class ExecutionReport {
    private final String orderId;
    private final Side side;
    private final ExecutionType executionType;
    private final long orderSize;
    private final BigDecimal price;
    private final long lastQuantity;
    private final long cumulativeQuantity;

    public ExecutionReport(String orderId, Side side, ExecutionType executionType, long orderSize, BigDecimal price, long lastQuantity, long cumulativeQuantity) {
        this.orderId = orderId;
        this.side = side;
        this.executionType = executionType;
        this.orderSize = orderSize;
        this.price = price;
        this.lastQuantity = lastQuantity;
        this.cumulativeQuantity = cumulativeQuantity;
    }

    public String getOrderId() {
        return orderId;
    }

    public Side getSide() {
        return side;
    }

    public ExecutionType getExecutionType() {
        return executionType;
    }

    public long getOrderSize() {
        return orderSize;
    }

    public BigDecimal getPrice() {
        return price;
    }

    public long getLastQuantity() {
        return lastQuantity;
    }

    public long getCumulativeQuantity() {
        return cumulativeQuantity;
    }

    @Override
    public String toString() {
        return String.format("ExecutionReport{orderId=%s, side=%s, type=%s, size=%d, price=%s, lastQty=%d, cumQty=%d}",
                orderId, side, executionType, orderSize, price, lastQuantity, cumulativeQuantity);
    }
}
