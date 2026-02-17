package com.matching.model;

import java.math.BigDecimal;
import java.util.UUID;

public class Order {
    private final UUID orderId;
    private final Side side;
    private final OrderType orderType;
    private final BigDecimal price;
    private final long quantity;
    private long remainingQuantity;
    private long cumulativeQuantity;
    private final long timestamp;

    public Order(UUID orderId, Side side, OrderType orderType, BigDecimal price, long quantity) {
        this.orderId = orderId;
        this.side = side;
        this.orderType = orderType;
        this.price = price;
        this.quantity = quantity;
        this.remainingQuantity = quantity;
        this.cumulativeQuantity = 0;
        this.timestamp = System.nanoTime();
    }

    public UUID getOrderId() {
        return orderId;
    }

    public Side getSide() {
        return side;
    }

    public OrderType getOrderType() {
        return orderType;
    }

    public BigDecimal getPrice() {
        return price;
    }

    public long getQuantity() {
        return quantity;
    }

    public long getRemainingQuantity() {
        return remainingQuantity;
    }

    public void setRemainingQuantity(long remainingQuantity) {
        this.remainingQuantity = remainingQuantity;
    }

    public long getCumulativeQuantity() {
        return cumulativeQuantity;
    }

    public void addToExecutedQuantity(long executedQuantity) {
        this.cumulativeQuantity += executedQuantity;
        this.remainingQuantity -= executedQuantity;
    }

    public long getTimestamp() {
        return timestamp;
    }

    public boolean isFullyFilled() {
        return remainingQuantity == 0;
    }

    @Override
    public String toString() {
        return String.format("Order{id=%s, side=%s, type=%s, price=%s, qty=%d, remaining=%d}",
                orderId, side, orderType, price, quantity, remainingQuantity);
    }
}
