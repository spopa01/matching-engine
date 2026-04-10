package com.matching;

import com.matching.engine.MatchingEngine;
import com.matching.engine.OrderBook;
import com.matching.model.*;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.math.BigDecimal;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.stream.Stream;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Tests that run with the instrumentation agent attached
 * (-javaagent:agent/target/matching-agent-1.0-SNAPSHOT.jar).
 *
 * Maven surefire picks this up because the class name ends with "Test".
 * In IntelliJ, add the VM option to your run configuration:
 *   -javaagent:agent/target/matching-agent-1.0-SNAPSHOT.jar
 */
class MatchingEngineAgentTest {

    private MatchingEngine engine;

    @BeforeEach
    void setUp() {
        engine = new MatchingEngine();
    }

    @Test
    void limitBuyMatchesLimitSell() {
        engine.addOrder(new Order("BUY1", Side.BUY, OrderType.LIMIT, new BigDecimal("100.50"), 10));
        engine.addOrder(new Order("SELL1", Side.SELL, OrderType.LIMIT, new BigDecimal("100.50"), 10));

        List<ExecutionReport> reports = engine.getExecutionReports();
        assertEquals(2, reports.size());
        reports.forEach(r -> {
            assertEquals(ExecutionType.FULL_FILL, r.getExecutionType());
            assertEquals(10, r.getLastQuantity());
            assertEquals(0, new BigDecimal("100.50").compareTo(r.getPrice()));
        });
    }

    @Test
    void partialFillProducesCorrectReports() {
        engine.addOrder(new Order("BUY1", Side.BUY, OrderType.LIMIT, new BigDecimal("100.00"), 20));
        engine.addOrder(new Order("SELL1", Side.SELL, OrderType.LIMIT, new BigDecimal("100.00"), 8));

        List<ExecutionReport> reports = engine.getExecutionReports();
        assertEquals(2, reports.size());

        ExecutionReport buyReport = reports.stream()
                .filter(r -> r.getOrderId().equals("BUY1")).findFirst().orElseThrow();
        assertEquals(ExecutionType.PARTIAL_FILL, buyReport.getExecutionType());
        assertEquals(8, buyReport.getLastQuantity());
        assertEquals(8, buyReport.getCumulativeQuantity());

        ExecutionReport sellReport = reports.stream()
                .filter(r -> r.getOrderId().equals("SELL1")).findFirst().orElseThrow();
        assertEquals(ExecutionType.FULL_FILL, sellReport.getExecutionType());
    }

    @Test
    void marketOrderWalksTheBook() {
        engine.addOrder(new Order("SELL1", Side.SELL, OrderType.LIMIT, new BigDecimal("100.00"), 5));
        engine.addOrder(new Order("SELL2", Side.SELL, OrderType.LIMIT, new BigDecimal("101.00"), 5));
        engine.addOrder(new Order("BUY1", Side.BUY, OrderType.MARKET, null, 10));

        List<ExecutionReport> reports = engine.getExecutionReports();
        // 2 matches x 2 reports each = 4
        assertEquals(4, reports.size());

        // Verify buyer filled fully across both price levels
        long buyerCumQty = reports.stream()
                .filter(r -> r.getOrderId().equals("BUY1"))
                .mapToLong(ExecutionReport::getLastQuantity)
                .sum();
        assertEquals(10, buyerCumQty);
    }

    @Test
    void unfilledMarketOrderGetsCancelReport() {
        // No sell liquidity — market buy should get a CANCEL report
        engine.addOrder(new Order("BUY1", Side.BUY, OrderType.MARKET, null, 10));

        List<ExecutionReport> reports = engine.getExecutionReports();
        assertEquals(1, reports.size());
        assertEquals(ExecutionType.CANCEL, reports.get(0).getExecutionType());
    }

    @Test
    void limitOrdersRestOnBookWhenNoMatch() {
        engine.addOrder(new Order("BUY1", Side.BUY, OrderType.LIMIT, new BigDecimal("99.00"), 10));
        engine.addOrder(new Order("SELL1", Side.SELL, OrderType.LIMIT, new BigDecimal("101.00"), 10));

        assertTrue(engine.getExecutionReports().isEmpty());

        OrderBook book = engine.getOrderBook();
        assertNotNull(book.getBestBuy());
        assertNotNull(book.getBestSell());
        assertEquals(0, new BigDecimal("99.00").compareTo(book.getBestBuy().getPrice()));
        assertEquals(0, new BigDecimal("101.00").compareTo(book.getBestSell().getPrice()));
    }

    @Test
    void fullEndToEndViaCsvRoundTrip(@TempDir Path tmpDir) throws Exception {
        Path input = tmpDir.resolve("orders.csv");
        Files.writeString(input, """
                orderId,side,orderType,quantity,price
                O1,BUY,LIMIT,10,100.50
                O2,SELL,LIMIT,5,100.50
                O3,BUY,MARKET,3,
                O4,SELL,LIMIT,3,100.60
                """);

        Path output = tmpDir.resolve("executions.csv");

        // Run via Main exactly like the CLI
        Main.main(new String[]{input.toString(), output.toString()});

        List<String> lines = Files.readAllLines(output);
        // header + at least 2 execution reports
        assertTrue(lines.size() >= 3, "Expected header + execution rows, got " + lines.size());
        assertEquals("orderId,side,executionType,orderSize,lastQuantity,cumulativeQuantity,price", lines.get(0));
    }

    @Test
    void agentEmitsExpectedInstrumentationEvents() throws Exception {
        engine.addOrder(new Order("AGENT_BUY", Side.BUY, OrderType.LIMIT, new BigDecimal("100.00"), 10));
        engine.addOrder(new Order("AGENT_SELL", Side.SELL, OrderType.LIMIT, new BigDecimal("100.00"), 10));

        // Drain thread is async — give it time to process the ring buffer
        Thread.sleep(500);

        // Flush the buffered writer so all content is on disk
        Class<?> interceptor = Class.forName("com.matching.agent.MethodInterceptor");
        java.io.PrintWriter writer = (java.io.PrintWriter) interceptor.getField("instrumentationWriter").get(null);
        writer.flush();

        Path logFile = Path.of(System.getProperty("matching.agent.logfile", "instrumentation.log"));
        assertTrue(Files.exists(logFile), "Instrumentation log not found at " + logFile);

        List<String> lines = Files.readAllLines(logFile);
        String log = String.join("\n", lines);

        // Header: function metadata was written
        assertTrue(log.contains("=== Function Metadata ==="), "Missing metadata header");
        assertTrue(log.contains("=== Execution Trace ==="), "Missing trace header");

        // ORDER_IN events for both orders
        assertTrue(lines.stream().anyMatch(l -> l.contains("ORDER_IN") && l.contains("AGENT_BUY")),
                "Missing ORDER_IN for AGENT_BUY");
        assertTrue(lines.stream().anyMatch(l -> l.contains("ORDER_IN") && l.contains("AGENT_SELL")),
                "Missing ORDER_IN for AGENT_SELL");

        // CALL events for matching functions
        assertTrue(lines.stream().anyMatch(l -> l.contains("CALL")),
                "Missing CALL events");

        // EXEC_REPORT events for both sides of the trade
        assertTrue(lines.stream().anyMatch(l -> l.contains("EXEC_REPORT") && l.contains("FULL_FILL")),
                "Missing EXEC_REPORT with FULL_FILL");

        // SNAPSHOT events (emitted after each addOrder returns)
        long snapshotCount = lines.stream().filter(l -> l.contains("SNAPSHOT")).count();
        assertTrue(snapshotCount >= 2, "Expected at least 2 SNAPSHOT events, got " + snapshotCount);
    }
}
