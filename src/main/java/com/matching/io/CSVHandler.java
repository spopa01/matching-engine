package com.matching.io;

import com.matching.annotation.FunctionMetadata;
import com.matching.model.*;

import java.io.*;
import java.math.BigDecimal;
import java.nio.ByteBuffer;
import java.util.ArrayList;
import java.util.Base64;
import java.util.List;
import java.util.UUID;

public class CSVHandler {

    @FunctionMetadata(
        functionName = "readOrders",
        uuid = "9qe4ydDhT1orPE1eb3qLnA",
        description = "Parses CSV file containing order submissions into Order objects for matching. Business logic: Reads file line-by-line, " +
                      "skipping the header row. Each subsequent line is parsed into an Order object with validation. Malformed lines trigger error " +
                      "messages but don't stop processing - the engine continues with remaining orders to maximize throughput. This fault-tolerant " +
                      "approach prevents single bad orders from blocking an entire batch. Returns ordered list maintaining original CSV sequence, " +
                      "ensuring orders are processed in submission time order which is critical for fair time-priority matching."
    )
    public static List<Order> readOrders(String filePath) throws IOException {
        List<Order> orders = new ArrayList<>();

        try (BufferedReader reader = new BufferedReader(new FileReader(filePath))) {
            String line = reader.readLine();

            if (line == null) {
                return orders;
            }

            while ((line = reader.readLine()) != null) {
                try {
                    Order order = parseOrderLine(line);
                    if (order != null) {
                        orders.add(order);
                    }
                } catch (Exception e) {
                    System.err.println("Error parsing line: " + line + " - " + e.getMessage());
                }
            }
        }

        return orders;
    }

    @FunctionMetadata(
        functionName = "parseOrderLine",
        uuid = "p7jJ0OHySls8TV5veoucDQ",
        description = "Converts a CSV line into a validated Order object. Business logic: Expects exactly 5 comma-separated fields: orderId (UUID), " +
                      "side (BUY/SELL), orderType (LIMIT/MARKET), quantity (positive long), price (decimal or empty). Performs critical validations: " +
                      "LIMIT orders must have a price (rejects if empty), MARKET orders accept empty price field, quantity must be positive. Empty price " +
                      "handling enables the same CSV format to support both order types. Throws exceptions on validation failures to prevent invalid orders " +
                      "from entering the matching engine, which could cause incorrect executions or crashes. UUID parsing ensures globally unique order IDs " +
                      "for accurate trade reporting and reconciliation."
    )
    private static Order parseOrderLine(String line) {
        String[] parts = line.split(",", -1);

        if (parts.length < 5) {
            throw new IllegalArgumentException("Invalid CSV format: expected 5 columns");
        }

        UUID orderId = base64ToUuid(parts[0].trim());
        Side side = Side.valueOf(parts[1].trim().toUpperCase());
        OrderType orderType = OrderType.valueOf(parts[2].trim().toUpperCase());
        long quantity = Long.parseLong(parts[3].trim());

        if (quantity <= 0) {
            throw new IllegalArgumentException("Quantity must be positive");
        }

        BigDecimal price = null;
        String priceStr = parts[4].trim();
        if (!priceStr.isEmpty()) {
            price = new BigDecimal(priceStr);
        } else if (orderType == OrderType.LIMIT) {
            throw new IllegalArgumentException("LIMIT orders must have a price");
        }

        return new Order(orderId, side, orderType, price, quantity);
    }

    @FunctionMetadata(
        functionName = "writeExecutionReports",
        uuid = "uMnQ4fKjS1xNXm96i5wNHg",
        description = "Exports execution reports to CSV for trade confirmation and audit trail. Business logic: Writes standardized CSV with header row " +
                      "containing orderId, price, lastQuantity, cumulativeQuantity. Each execution report becomes one row, enabling post-trade analysis. " +
                      "Reports are written in chronological order (order of generation), which reflects the exact sequence of matches - critical for " +
                      "regulatory compliance and dispute resolution. The lastQuantity field shows the specific fill size, while cumulativeQuantity tracks " +
                      "total filled across all executions for orders with multiple partial fills. This dual quantity tracking enables both micro-level " +
                      "(per-execution) and macro-level (per-order) analysis. File is fully buffered for performance on large trade volumes."
    )
    public static void writeExecutionReports(String filePath, List<ExecutionReport> reports) throws IOException {
        try (BufferedWriter writer = new BufferedWriter(new FileWriter(filePath))) {
            writer.write("orderId,side,executionType,orderSize,lastQuantity,cumulativeQuantity,price");
            writer.newLine();

            for (ExecutionReport report : reports) {
                StringBuilder line = new StringBuilder();
                line.append(uuidToBase64(report.getOrderId())).append(",");
                line.append(report.getSide()).append(",");
                line.append(report.getExecutionType()).append(",");
                line.append(report.getOrderSize()).append(",");
                line.append(report.getLastQuantity()).append(",");
                line.append(report.getCumulativeQuantity()).append(",");
                line.append(report.getPrice() != null ? report.getPrice() : "");

                writer.write(line.toString());
                writer.newLine();
            }
        }
    }

    private static UUID base64ToUuid(String base64) {
        byte[] bytes = Base64.getUrlDecoder().decode(base64);
        ByteBuffer buffer = ByteBuffer.wrap(bytes);
        long mostSigBits = buffer.getLong();
        long leastSigBits = buffer.getLong();
        return new UUID(mostSigBits, leastSigBits);
    }

    private static String uuidToBase64(UUID uuid) {
        ByteBuffer buffer = ByteBuffer.wrap(new byte[16]);
        buffer.putLong(uuid.getMostSignificantBits());
        buffer.putLong(uuid.getLeastSignificantBits());
        return Base64.getUrlEncoder().withoutPadding().encodeToString(buffer.array());
    }
}
