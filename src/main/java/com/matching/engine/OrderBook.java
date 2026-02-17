package com.matching.engine;

import com.matching.annotation.FunctionMetadata;
import com.matching.model.Order;
import com.matching.model.Side;

import java.math.BigDecimal;
import java.util.*;

public class OrderBook {
    private final TreeMap<BigDecimal, LinkedList<Order>> buySide;
    private final TreeMap<BigDecimal, LinkedList<Order>> sellSide;

    public OrderBook() {
        this.buySide = new TreeMap<>(Collections.reverseOrder());
        this.sellSide = new TreeMap<>();
    }

    @FunctionMetadata(
        functionName = "addOrder",
        uuid = "-KmwwdLjT1prfI2eDxorPA",
        description = "Inserts an unfilled or partially filled order into the order book. Business logic: Orders are organized by price level, " +
                      "with each price level maintaining a FIFO queue. The buy side uses descending order (highest price first) while the sell side " +
                      "uses ascending order (lowest price first). If no orders exist at the given price, creates a new LinkedList for that price level. " +
                      "This structure enables efficient best price lookups and time-priority matching within each price level."
    )
    public void addOrder(Order order) {
        TreeMap<BigDecimal, LinkedList<Order>> side = getSide(order.getSide());
        side.computeIfAbsent(order.getPrice(), k -> new LinkedList<>()).add(order);
    }

    @FunctionMetadata(
        functionName = "getBestBuy",
        uuid = "qbDB0uP0Slt8jZ4PGis8TQ",
        description = "Retrieves the first order at the highest buy price (best bid). Business logic: Used by the matching engine to find the " +
                      "best counter-order for incoming sell orders. Returns the head of the FIFO queue at the best bid price level, ensuring " +
                      "time priority. Returns null if the buy side is empty or the price level queue is empty. This is called repeatedly during " +
                      "matching to process orders in price-time priority."
    )
    public Order getBestBuy() {
        if (buySide.isEmpty()) {
            return null;
        }
        LinkedList<Order> orders = buySide.firstEntry().getValue();
        return orders.isEmpty() ? null : orders.peek();
    }

    @FunctionMetadata(
        functionName = "getBestSell",
        uuid = "sMHS4_SlS1yNng8aKzxNXg",
        description = "Retrieves the first order at the lowest sell price (best ask). Business logic: Used by the matching engine to find the " +
                      "best counter-order for incoming buy orders. Returns the head of the FIFO queue at the best ask price level, ensuring " +
                      "time priority. Returns null if the sell side is empty or the price level queue is empty. This is called repeatedly during " +
                      "matching to process orders in price-time priority."
    )
    public Order getBestSell() {
        if (sellSide.isEmpty()) {
            return null;
        }
        LinkedList<Order> orders = sellSide.firstEntry().getValue();
        return orders.isEmpty() ? null : orders.peek();
    }

    @FunctionMetadata(
        functionName = "removeOrder",
        uuid = "4_SltsfYTl8aKzxNXm96iw",
        description = "Removes a fully filled order from the book and performs cleanup. Business logic: Locates the order at its price level " +
                      "and removes it from the FIFO queue. If the removal leaves the price level empty (no more orders at that price), the entire " +
                      "price level entry is removed from the TreeMap to prevent memory leaks and maintain efficient tree structure. Called by the " +
                      "matching engine after each order is fully filled. Critical for maintaining book accuracy and preventing re-matching of filled orders."
    )
    public void removeOrder(Order order) {
        TreeMap<BigDecimal, LinkedList<Order>> side = getSide(order.getSide());
        LinkedList<Order> orders = side.get(order.getPrice());
        if (orders != null) {
            orders.remove(order);
            if (orders.isEmpty()) {
                side.remove(order.getPrice());
            }
        }
    }

    private TreeMap<BigDecimal, LinkedList<Order>> getSide(Side side) {
        return side == Side.BUY ? buySide : sellSide;
    }
}
