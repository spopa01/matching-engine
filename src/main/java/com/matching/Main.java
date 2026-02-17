package com.matching;

import com.matching.annotation.FunctionMetadata;
import com.matching.engine.MatchingEngine;
import com.matching.engine.OrderBook;
import com.matching.io.CSVHandler;
import com.matching.model.ExecutionReport;
import com.matching.model.Order;

import java.math.BigDecimal;
import java.util.List;

public class Main {
    @FunctionMetadata(
        functionName = "main",
        uuid = "ydDh8qO0TF1eb3qLnA0eLw",
        description = "Application entry point orchestrating the complete order matching workflow. Business logic: Accepts command-line arguments for " +
                      "input/output file paths with sensible defaults (sample_orders.csv, executions.csv). Follows a strict processing pipeline: " +
                      "(1) Load all orders from CSV maintaining submission sequence, (2) Initialize clean matching engine state, (3) Process orders " +
                      "sequentially through the matching engine - this serial processing is intentional as order arrival sequence affects matching outcomes " +
                      "in time-priority systems, (4) Export all execution reports to CSV for trade confirmation, (5) Display comprehensive summary including " +
                      "order counts, execution counts, remaining book depth, and best bid/ask prices for market transparency. Error handling uses fail-fast " +
                      "approach with stack traces for debugging. Summary statistics provide immediate feedback on matching engine performance and market state."
    )
    public static void main(String[] args) {
        String inputFile = args.length > 0 ? args[0] : "orders.csv";
        String outputFile = args.length > 1 ? args[1] : "executions.csv";

        System.out.println("=== Matching Engine ===");
        System.out.println("Input file: " + inputFile);
        System.out.println("Output file: " + outputFile);
        System.out.println();

        try {
            List<Order> orders = CSVHandler.readOrders(inputFile);
            System.out.println("Read " + orders.size() + " orders from input file");

            MatchingEngine engine = new MatchingEngine();

            for (Order order : orders) {
                engine.addOrder(order);
            }

            List<ExecutionReport> executionReports = engine.getExecutionReports();
            System.out.println("Generated " + executionReports.size() + " execution reports");

            CSVHandler.writeExecutionReports(outputFile, executionReports);
            System.out.println("Wrote execution reports to " + outputFile);

        } catch (Exception e) {
            System.err.println("Error: " + e.getMessage());
            e.printStackTrace();
            System.exit(1);
        }
    }
}
