package com.matching.engine;

import com.matching.annotation.FunctionMetadata;
import com.matching.model.*;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.List;

public class MatchingEngine {
    private final OrderBook orderBook;
    private final List<ExecutionReport> executionReports;

    public MatchingEngine() {
        this.orderBook = new OrderBook();
        this.executionReports = new ArrayList<>();
    }

    @FunctionMetadata(
        functionName = "addOrder",
        uuid = "8KGyw9TlT1prfI2eDxorPA",
        description = "Main entry point for processing incoming orders through the matching engine. Business logic: Routes buy and sell orders to " +
                      "their respective matching algorithms. After matching attempts, handles unfilled quantity based on order type: LIMIT orders with " +
                      "remaining quantity are added to the order book to rest at their specified price; MARKET orders with remaining quantity trigger a " +
                      "warning as they indicate insufficient liquidity (market orders should fill immediately or be rejected). This two-phase approach " +
                      "(match first, then rest) ensures orders receive immediate execution when possible while maintaining a resting book for price discovery."
    )
    public void addOrder(Order order) {
        if (order.getSide() == Side.BUY) {
            matchBuyOrder(order);
        } else {
            matchSellOrder(order);
        }

        if (order.getRemainingQuantity() > 0) {
            if (order.getOrderType() == OrderType.LIMIT) {
                orderBook.addOrder(order);
            } else {
                // Generate cancellation report for unfilled market order quantity
                ExecutionReport cancellationReport = new ExecutionReport(
                        order.getOrderId(),
                        order.getSide(),
                        ExecutionType.CANCEL,
                        order.getQuantity(),
                        null,  // No price for cancellation
                        order.getRemainingQuantity(),
                        order.getCumulativeQuantity()
                );
                recordExecutionReport(cancellationReport);
            }
        }
    }

    @FunctionMetadata(
        functionName = "matchBuyOrder",
        uuid = "obLD1OX2Slt8jZ4PGis8TQ",
        description = "Attempts to match an incoming buy order against resting sell orders using price-time priority. Business logic: Continuously " +
                      "retrieves the best (lowest) sell order from the book while the buy order has remaining quantity. For LIMIT buy orders, matching " +
                      "stops when the best sell price exceeds the buy limit price (buyer's maximum price constraint). For MARKET buy orders, matching " +
                      "continues until either fully filled or no sell orders remain. Each successful match executes at the resting sell order's price " +
                      "(price priority favors the maker). Fully filled sell orders are removed from the book after execution. This implements aggressive " +
                      "taking behavior where the incoming order 'walks the book' consuming liquidity at progressively worse prices until filled or stopped."
    )
    private void matchBuyOrder(Order buyOrder) {
        while (buyOrder.getRemainingQuantity() > 0) {
            Order bestSell = orderBook.getBestSell();

            if (bestSell == null) {
                break;
            }

            if (buyOrder.getOrderType() == OrderType.LIMIT &&
                buyOrder.getPrice().compareTo(bestSell.getPrice()) < 0) {
                break;
            }

            executeMatch(buyOrder, bestSell, bestSell.getPrice());

            if (bestSell.isFullyFilled()) {
                orderBook.removeOrder(bestSell);
            }
        }
    }

    @FunctionMetadata(
        functionName = "matchSellOrder",
        uuid = "ssPU5fanS1yNng8aKzxNXg",
        description = "Attempts to match an incoming sell order against resting buy orders using price-time priority. Business logic: Continuously " +
                      "retrieves the best (highest) buy order from the book while the sell order has remaining quantity. For LIMIT sell orders, matching " +
                      "stops when the best buy price falls below the sell limit price (seller's minimum price constraint). For MARKET sell orders, matching " +
                      "continues until either fully filled or no buy orders remain. Each successful match executes at the resting buy order's price " +
                      "(price priority favors the maker). Fully filled buy orders are removed from the book after execution. This implements aggressive " +
                      "taking behavior where the incoming order 'walks the book' consuming liquidity at progressively worse prices until filled or stopped."
    )
    private void matchSellOrder(Order sellOrder) {
        while (sellOrder.getRemainingQuantity() > 0) {
            Order bestBuy = orderBook.getBestBuy();

            if (bestBuy == null) {
                break;
            }

            if (sellOrder.getOrderType() == OrderType.LIMIT &&
                sellOrder.getPrice().compareTo(bestBuy.getPrice()) > 0) {
                break;
            }

            executeMatch(sellOrder, bestBuy, bestBuy.getPrice());

            if (bestBuy.isFullyFilled()) {
                orderBook.removeOrder(bestBuy);
            }
        }
    }

    @FunctionMetadata(
        functionName = "executeMatch",
        uuid = "w9Tl9qe4TF2eDxorPE1ebw",
        description = "Executes a trade between an incoming order and a resting order, generating execution reports. Business logic: Calculates fill " +
                      "quantity as the minimum of both orders' remaining quantities, enabling partial fills. Updates both orders' cumulative and remaining " +
                      "quantities atomically. Execution occurs at the resting order's price (maker price), following standard price-time priority where the " +
                      "order already in the book gets price priority. Generates two execution reports (one for each side) with the same price and quantity " +
                      "but different cumulative quantities reflecting each order's total filled amount. These reports are essential for trade confirmation, " +
                      "position tracking, and regulatory reporting. Each match represents an atomic transaction ensuring both sides agree on price and quantity."
    )
    private void executeMatch(Order incomingOrder, Order restingOrder, BigDecimal executionPrice) {
        long fillQuantity = Math.min(incomingOrder.getRemainingQuantity(), restingOrder.getRemainingQuantity());

        incomingOrder.addToExecutedQuantity(fillQuantity);
        restingOrder.addToExecutedQuantity(fillQuantity);

        // Determine execution type based on whether order is fully filled
        ExecutionType incomingExecType = incomingOrder.getRemainingQuantity() == 0 ? ExecutionType.FULL_FILL : ExecutionType.PARTIAL_FILL;
        ExecutionType restingExecType = restingOrder.getRemainingQuantity() == 0 ? ExecutionType.FULL_FILL : ExecutionType.PARTIAL_FILL;

        ExecutionReport incomingReport = new ExecutionReport(
                incomingOrder.getOrderId(),
                incomingOrder.getSide(),
                incomingExecType,
                incomingOrder.getQuantity(),
                executionPrice,
                fillQuantity,
                incomingOrder.getCumulativeQuantity()
        );

        ExecutionReport restingReport = new ExecutionReport(
                restingOrder.getOrderId(),
                restingOrder.getSide(),
                restingExecType,
                restingOrder.getQuantity(),
                executionPrice,
                fillQuantity,
                restingOrder.getCumulativeQuantity()
        );

        recordExecutionReport(incomingReport);
        recordExecutionReport(restingReport);
    }

    @FunctionMetadata(
        functionName = "recordExecutionReport",
        uuid = "5_al9qe4TF2eDxorPE1eaw",
        description = "Records an execution report to the execution reports list. Business logic: Captures all order lifecycle events including " +
                      "fills, partial fills, and cancellations for audit trail and regulatory compliance. Each execution report documents a state " +
                      "change in an order's lifecycle with timestamp-ordered sequencing critical for trade reconstruction and dispute resolution."
    )
    private void recordExecutionReport(ExecutionReport report) {
        executionReports.add(report);
    }

    public List<ExecutionReport> getExecutionReports() {
        return executionReports;
    }

    public OrderBook getOrderBook() {
        return orderBook;
    }
}
