package com.matching.agent;

import net.bytebuddy.asm.Advice;

import java.io.BufferedWriter;
import java.io.FileWriter;
import java.io.IOException;
import java.io.PrintWriter;
import java.io.Writer;
import java.lang.invoke.MethodHandle;
import java.lang.invoke.MethodHandles;
import java.lang.invoke.MethodType;
import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.util.Collections;
import java.util.HashMap;
import java.util.TreeMap;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.locks.LockSupport;

public class MethodInterceptor {

    // Config: -Dmatching.agent.output=none disables file I/O (uses no-op writer)
    //         -Dmatching.agent.output=file (default) writes to instrumentation.log
    public static final String OUTPUT_MODE = System.getProperty("matching.agent.output", "file");

    // Config: -Dmatching.agent.logfile=path — output file path (default: instrumentation.log)
    public static final String LOG_FILE = System.getProperty("matching.agent.logfile", "instrumentation.log");

    // Config: -Dmatching.agent.snapshot.levels=N — number of top price levels per side in snapshots (default 5)
    public static final int SNAPSHOT_LEVELS = Integer.parseInt(System.getProperty("matching.agent.snapshot.levels", "5"));

    // Config: -Dmatching.agent.snapshot.interval=N — emit snapshot every N orders (default 1 = every order)
    public static final int SNAPSHOT_INTERVAL = Integer.parseInt(System.getProperty("matching.agent.snapshot.interval", "1"));

    // Config: -Dmatching.agent.emit=false — run advice but skip all event emission (profiling mode)
    public static final boolean EMIT_ENABLED = !"false".equalsIgnoreCase(System.getProperty("matching.agent.emit"));

    public static final PrintWriter instrumentationWriter;
    public static volatile boolean headerWritten = false;

    // Pre-computed indent strings (avoids "  ".repeat(depth) allocation per call)
    private static final int MAX_DEPTH = 16;
    private static final String[] INDENTS = new String[MAX_DEPTH];

    // Cached Order class reference
    private static volatile Class<?> orderClass;

    // UUID constants for special methods
    private static final String UUID_ADD_ORDER   = "8KGyw9TlT1prfI2eDxorPA";
    private static final String UUID_RECORD_EXEC = "5_al9qe4TF2eDxorPE1eaw";
    private static final String UUID_BOOK_ADD    = "-KmwwdLjT1prfI2eDxorPA";

    // Pre-populated by MatchingAgent during class transformation (write-once, then read-only)
    public static final HashMap<String, String> functionUuidByKey = new HashMap<>();

    // Lock-free ring buffer replacing ConcurrentLinkedQueue
    public static final SpscRingBuffer ringBuffer = new SpscRingBuffer(20); // 1M slots

    // Cache: FunctionMetadata annotation class (resolved once, used for header)
    private static volatile Class<? extends java.lang.annotation.Annotation> functionMetadataClass;

    // Cache: MethodHandle getters per class — Class → (fieldName → MethodHandle)
    // MethodHandle.invoke() is ~7x faster than Field.get() after JIT warmup
    private static final ConcurrentHashMap<Class<?>, ConcurrentHashMap<String, MethodHandle>> handleCache = new ConcurrentHashMap<>();

    private static volatile boolean logRunning = true;
    private static final Thread logDrainThread;

    static {
        for (int i = 0; i < MAX_DEPTH; i++) {
            INDENTS[i] = "  ".repeat(i);
        }

        PrintWriter writer;
        if ("none".equalsIgnoreCase(OUTPUT_MODE)) {
            writer = new PrintWriter(Writer.nullWriter());
            System.out.println("Agent output: none (dummy writer)");
        } else {
            Writer fileWriter = null;
            try {
                fileWriter = new BufferedWriter(new FileWriter(LOG_FILE, false), 1 << 20); // 1MB buffer
            } catch (IOException e) {
                System.err.println("Failed to create instrumentation log file: " + e.getMessage());
                fileWriter = Writer.nullWriter();
            }
            writer = new PrintWriter(fileWriter);
            System.out.println("Agent output: file (" + LOG_FILE + ")");
        }
        instrumentationWriter = writer;

        logDrainThread = new Thread(() -> {
            StringBuilder sb = new StringBuilder(1 << 16); // 64KB batch buffer
            while (logRunning || !ringBuffer.isEmpty()) {
                TraceEvent event = ringBuffer.poll();
                if (event != null) {
                    sb.setLength(0);
                    drainProcessEvent(event);
                    formatEvent(event, sb);
                    sb.append('\n');
                    ringBuffer.release(event);
                    // Batch drain: accumulate into one buffer, write once
                    while ((event = ringBuffer.poll()) != null) {
                        drainProcessEvent(event);
                        formatEvent(event, sb);
                        sb.append('\n');
                        ringBuffer.release(event);
                        if (sb.length() > (1 << 16)) { // flush every ~64KB
                            instrumentationWriter.write(sb.toString());
                            sb.setLength(0);
                        }
                    }
                    if (sb.length() > 0) {
                        instrumentationWriter.write(sb.toString());
                    }
                } else {
                    LockSupport.parkNanos(100_000); // 100μs
                }
            }
        }, "instrumentation-writer");
        logDrainThread.setDaemon(true);
        logDrainThread.start();

        Runtime.getRuntime().addShutdownHook(new Thread(() -> {
            logRunning = false;
            LockSupport.unpark(logDrainThread);
            try { logDrainThread.join(5000); } catch (InterruptedException e) { /* ignore */ }
            // Safety net: drain anything the background thread missed
            StringBuilder sb = new StringBuilder(1 << 16);
            TraceEvent event;
            while ((event = ringBuffer.poll()) != null) {
                drainProcessEvent(event);
                formatEvent(event, sb);
                sb.append('\n');
                ringBuffer.release(event);
            }
            if (sb.length() > 0) {
                instrumentationWriter.write(sb.toString());
            }
            instrumentationWriter.flush();
            instrumentationWriter.close();
        }));
    }

    // Single ThreadLocal replaces two separate ones (callDepth + currentOrderId)
    // Reduces ThreadLocal lookups from 4-8 per method to 2 (one in enter, one in exit)
    public static final class TraceContext {
        public int depth = 0;
        public String currentOrderId = null;
        public int orderCounter = 0;
    }

    public static final ThreadLocal<TraceContext> traceContext =
            ThreadLocal.withInitial(TraceContext::new);

    public static String indent(int depth) {
        return depth < MAX_DEPTH ? INDENTS[depth] : "  ".repeat(depth);
    }

    // ───────────────────── Advice: onEnter ─────────────────────

    @Advice.OnMethodEnter
    public static void onEnter(
            @Advice.Argument(value = 0, optional = true, readOnly = true) Object firstArg,
            @ResolvedUuid String functionUuid,
            @Advice.Local("traceCtx") Object traceCtx) {
        try {
            if (!headerWritten) {
                synchronized (MethodInterceptor.class) {
                    if (!headerWritten) {
                        writeFunctionMetadataHeader();
                        headerWritten = true;
                    }
                }
            }

            TraceContext ctx = traceContext.get(); // single TL lookup
            traceCtx = ctx; // cache for onExit — avoids second ThreadLocal.get()
            int depth = ctx.depth;

            // Only extract order ID for addOrder at top level
            if (depth == 0 && functionUuid == UUID_ADD_ORDER && firstArg != null) {
                String orderId = extractOrderIdDirect(firstArg);
                if (orderId != null) {
                    ctx.currentOrderId = orderId;
                }
            }

            if (EMIT_ENABLED) {
                String contextOrderId = ctx.currentOrderId;

                // Emit ORDER_IN (merged with CALL) for addOrder at depth 0
                if (depth == 0 && functionUuid == UUID_ADD_ORDER && firstArg != null) {
                    emitOrderInEvent(firstArg, contextOrderId, functionUuid);
                }

                // Skip CALL for methods that already emit richer events
                if (functionUuid != UUID_RECORD_EXEC && functionUuid != UUID_BOOK_ADD
                        && functionUuid != UUID_ADD_ORDER) {
                    emitCallEvent(contextOrderId, depth, functionUuid);
                }

                // Emit EXEC_REPORT for recordExecutionReport
                if (functionUuid == UUID_RECORD_EXEC && firstArg != null) {
                    emitExecReportEvent(firstArg, contextOrderId, depth);
                }

                // Emit BOOK_ADD for OrderBook.addOrder
                if (functionUuid == UUID_BOOK_ADD && firstArg != null) {
                    emitBookAddEvent(firstArg, contextOrderId, depth);
                }

                ringBuffer.publish(); // batch publish all events claimed above
            }

            ctx.depth = depth + 1;
        } catch (Throwable e) {
            // Silently ignore instrumentation errors
        }
    }

    // ───────────────────── Advice: onExit ─────────────────────

    @Advice.OnMethodExit(onThrowable = Throwable.class)
    public static void onExit(
            @ResolvedUuid String functionUuid,
            @Advice.Local("traceCtx") Object traceCtx) {
        try {
            TraceContext ctx = (TraceContext) traceCtx; // from @Advice.Local — no ThreadLocal.get()
            int depth = ctx.depth - 1;
            ctx.depth = Math.max(0, depth);

            if (functionUuid == UUID_ADD_ORDER && depth == 0) {
                if (EMIT_ENABLED) {
                    ctx.orderCounter++;
                    if (SNAPSHOT_INTERVAL <= 1 || ctx.orderCounter % SNAPSHOT_INTERVAL == 0) {
                        emitSnapshotEvent(ctx.currentOrderId, depth);
                        ringBuffer.publish(); // batch publish snapshot event
                    }
                }
                ctx.currentOrderId = null;
            }
        } catch (Throwable e) {
            // Silently ignore instrumentation errors
        }
    }

    // ───────────────────── Emit methods (write raw fields to ring buffer) ─────────────────────

    public static void emitCallEvent(String contextOrderId, int depth, String functionUuid) {
        TraceEvent event = ringBuffer.claim();
        if (event == null) return;
        event.eventType = TraceEvent.TYPE_CALL;
        event.contextOrderId = contextOrderId;
        event.depth = depth;
        event.functionUuid = functionUuid;
    }

    public static void emitOrderInEvent(Object order, String contextOrderId, String functionUuid) {
        if (order == null) return;
        try {
            Class<?> cls = order.getClass();
            TraceEvent event = ringBuffer.claim();
            if (event == null) return;
            event.eventType = TraceEvent.TYPE_ORDER_IN;
            event.contextOrderId = contextOrderId;
            event.depth = 0;
            event.functionUuid = functionUuid; // merged CALL — drain thread emits CALL line from this
            event.orderId = (String)(Object) getCachedHandle(cls, "orderId").invokeExact(order);
            event.side = (Object) getCachedHandle(cls, "side").invokeExact(order);
            event.orderType = (Object) getCachedHandle(cls, "orderType").invokeExact(order);
            event.price = (Object) getCachedHandle(cls, "price").invokeExact(order);
            event.quantity = (long) getCachedHandleLong(cls, "quantity").invokeExact(order);
        } catch (Throwable e) {
            // Silently ignore
        }
    }

    public static void emitExecReportEvent(Object report, String contextOrderId, int depth) {
        if (report == null) return;
        try {
            Class<?> cls = report.getClass();
            TraceEvent event = ringBuffer.claim();
            if (event == null) return;
            event.eventType = TraceEvent.TYPE_EXEC_REPORT;
            event.contextOrderId = contextOrderId;
            event.depth = depth;
            event.orderId = (String)(Object) getCachedHandle(cls, "orderId").invokeExact(report);
            event.side = (Object) getCachedHandle(cls, "side").invokeExact(report);
            event.executionType = (Object) getCachedHandle(cls, "executionType").invokeExact(report);
            event.orderSize = (long) getCachedHandleLong(cls, "orderSize").invokeExact(report);
            event.price = (Object) getCachedHandle(cls, "price").invokeExact(report);
            event.lastQuantity = (long) getCachedHandleLong(cls, "lastQuantity").invokeExact(report);
            event.cumulativeQuantity = (long) getCachedHandleLong(cls, "cumulativeQuantity").invokeExact(report);
        } catch (Throwable e) {
            // Silently ignore
        }
    }

    public static void emitBookAddEvent(Object order, String contextOrderId, int depth) {
        if (order == null) return;
        try {
            Class<?> cls = order.getClass();
            TraceEvent event = ringBuffer.claim();
            if (event == null) return;
            event.eventType = TraceEvent.TYPE_BOOK_ADD;
            event.contextOrderId = contextOrderId;
            event.depth = depth;
            event.orderId = (String)(Object) getCachedHandle(cls, "orderId").invokeExact(order);
            event.side = (Object) getCachedHandle(cls, "side").invokeExact(order);
            event.price = (Object) getCachedHandle(cls, "price").invokeExact(order);
            event.remainingQuantity = (long) getCachedHandleLong(cls, "remainingQuantity").invokeExact(order);
            event.cumulativeQuantity = (long) getCachedHandleLong(cls, "cumulativeQuantity").invokeExact(order);
        } catch (Throwable e) {
            // Silently ignore
        }
    }

    public static void emitSnapshotEvent(String contextOrderId, int depth) {
        TraceEvent event = ringBuffer.claim();
        if (event == null) return;
        event.eventType = TraceEvent.TYPE_SNAPSHOT;
        event.contextOrderId = contextOrderId;
        event.depth = depth;
    }

    // ───────────────────── Drain thread formatting ─────────────────────

    private static void formatEvent(TraceEvent event, StringBuilder sb) {
        String orderIdStr = event.contextOrderId != null ? event.contextOrderId : "N/A";
        String ind = indent(event.depth);

        switch (event.eventType) {
            case TraceEvent.TYPE_ORDER_IN:
                sb.append(orderIdStr).append(" | ").append(ind).append("ORDER_IN | ")
                  .append(event.orderId).append(" | ").append(event.side).append(" | ").append(event.orderType)
                  .append(" | qty=").append(event.quantity).append(" | price=")
                  .append(event.price != null ? event.price : "");
                // Merged CALL line — emitted from same event to save a ring buffer slot
                if (event.functionUuid != null) {
                    sb.append('\n');
                    sb.append(orderIdStr).append(" | ").append(ind).append("CALL | ").append(event.functionUuid);
                }
                break;
            case TraceEvent.TYPE_CALL:
                sb.append(orderIdStr).append(" | ").append(ind).append("CALL | ").append(event.functionUuid);
                break;
            case TraceEvent.TYPE_EXEC_REPORT:
                sb.append(orderIdStr).append(" | ").append(ind).append("  EXEC_REPORT | ")
                  .append(event.orderId).append(" | ").append(event.side).append(" | ").append(event.executionType)
                  .append(" | qty=").append(event.orderSize).append(" | lastQty=").append(event.lastQuantity)
                  .append(" | cumQty=").append(event.cumulativeQuantity).append(" | price=")
                  .append(event.price != null ? event.price : "");
                break;
            case TraceEvent.TYPE_BOOK_ADD:
                sb.append(orderIdStr).append(" | ").append(ind).append("  BOOK_ADD | ")
                  .append(event.orderId).append(" | ").append(event.side)
                  .append(" | price=").append(event.price != null ? event.price : "")
                  .append(" | remainingQty=").append(event.remainingQuantity)
                  .append(" | cumQty=").append(event.cumulativeQuantity);
                break;
            case TraceEvent.TYPE_SNAPSHOT:
                sb.append(orderIdStr).append(" | ").append(ind).append("SNAPSHOT | Buy: [");
                drainFormatLevels(sb, drainBuyLevels);
                sb.append("] Sell: [");
                drainFormatLevels(sb, drainSellLevels);
                sb.append(']');
                break;
        }
    }

    // ───────────────────── Helpers ─────────────────────

    public static String extractOrderIdDirect(Object order) {
        try {
            Class<?> cls = order.getClass();
            if (orderClass == null) {
                orderClass = cls;
            }
            return (String)(Object) getCachedHandle(cls, "orderId").invokeExact(order);
        } catch (Throwable e) {
            return null;
        }
    }

    // Returns handle pre-adapted to (Object)->Object for reference fields
    private static MethodHandle getCachedHandle(Class<?> clazz, String fieldName) throws Throwable {
        ConcurrentHashMap<String, MethodHandle> classHandles = handleCache.computeIfAbsent(clazz, k -> new ConcurrentHashMap<>());
        MethodHandle mh = classHandles.get(fieldName);
        if (mh == null) {
            Field f = clazz.getDeclaredField(fieldName);
            f.setAccessible(true);
            mh = MethodHandles.lookup().unreflectGetter(f)
                    .asType(MethodType.methodType(Object.class, Object.class));
            classHandles.put(fieldName, mh);
        }
        return mh;
    }

    // Returns handle pre-adapted to (Object)->long for primitive long fields
    private static MethodHandle getCachedHandleLong(Class<?> clazz, String fieldName) throws Throwable {
        String key = fieldName + "#long";
        ConcurrentHashMap<String, MethodHandle> classHandles = handleCache.computeIfAbsent(clazz, k -> new ConcurrentHashMap<>());
        MethodHandle mh = classHandles.get(key);
        if (mh == null) {
            Field f = clazz.getDeclaredField(fieldName);
            f.setAccessible(true);
            mh = MethodHandles.lookup().unreflectGetter(f)
                    .asType(MethodType.methodType(long.class, Object.class));
            classHandles.put(key, mh);
        }
        return mh;
    }

    // ───────────────────── Drain-thread book reconstruction ─────────────────────
    // The drain thread maintains its own book model from BOOK_ADD and EXEC_REPORT events,
    // eliminating the need for any book-related work on the engine thread.

    private static final class DrainOrder {
        Object side;
        Object price;
        long remainingQty;
    }

    // Order tracking: orderId → DrainOrder (orders currently in the book)
    private static final HashMap<String, DrainOrder> drainOrders = new HashMap<>(1024);

    // Aggregated price levels (same ordering as the real OrderBook)
    // Values: long[2] = {totalQty, orderCount}
    @SuppressWarnings({"unchecked", "rawtypes"})
    private static final TreeMap drainBuyLevels = new TreeMap(Collections.reverseOrder());
    @SuppressWarnings({"unchecked", "rawtypes"})
    private static final TreeMap drainSellLevels = new TreeMap();

    // Cached reference to the BUY side enum for fast identity comparison
    private static Object CACHED_BUY_SIDE = null;

    private static boolean isBuySide(Object side) {
        if (side == CACHED_BUY_SIDE) return true;
        if (CACHED_BUY_SIDE == null && "BUY".equals(String.valueOf(side))) {
            CACHED_BUY_SIDE = side;
            return true;
        }
        return false;
    }

    @SuppressWarnings("unchecked")
    private static TreeMap<Object, long[]> drainLevelsFor(Object side) {
        return isBuySide(side) ? drainBuyLevels : drainSellLevels;
    }

    /** Process events on drain thread to maintain virtual book state. */
    private static void drainProcessEvent(TraceEvent event) {
        switch (event.eventType) {
            case TraceEvent.TYPE_BOOK_ADD:
                drainProcessBookAdd(event);
                break;
            case TraceEvent.TYPE_EXEC_REPORT:
                drainProcessExecReport(event);
                break;
        }
    }

    @SuppressWarnings("unchecked")
    private static void drainProcessBookAdd(TraceEvent event) {
        DrainOrder order = new DrainOrder();
        order.side = event.side;
        order.price = event.price;
        order.remainingQty = event.remainingQuantity;
        drainOrders.put(event.orderId, order);

        TreeMap<Object, long[]> levels = drainLevelsFor(event.side);
        long[] info = (long[]) levels.get(event.price);
        if (info == null) {
            info = new long[]{0, 0};
            levels.put(event.price, info);
        }
        info[0] += event.remainingQuantity;
        info[1]++;
    }

    @SuppressWarnings("unchecked")
    private static void drainProcessExecReport(TraceEvent event) {
        DrainOrder order = drainOrders.get(event.orderId);
        if (order == null) return; // incoming order or cancellation, not in book

        TreeMap<Object, long[]> levels = drainLevelsFor(order.side);
        long[] info = (long[]) levels.get(order.price);

        long filled = event.lastQuantity;
        if (info != null) {
            info[0] -= filled;
        }

        order.remainingQty -= filled;
        if (order.remainingQty <= 0) {
            drainOrders.remove(event.orderId);
            if (info != null) {
                info[1]--;
                if (info[1] <= 0) {
                    levels.remove(order.price);
                }
            }
        }
    }

    @SuppressWarnings("unchecked")
    private static void drainFormatLevels(StringBuilder sb, TreeMap<Object, long[]> levels) {
        int count = 0;
        for (Object entryObj : levels.entrySet()) {
            if (count >= SNAPSHOT_LEVELS) break;
            java.util.Map.Entry<Object, long[]> entry = (java.util.Map.Entry<Object, long[]>) entryObj;
            long[] info = entry.getValue();
            if (count > 0) sb.append(", ");
            sb.append(entry.getKey()).append(':').append(info[0]).append('(').append((int) info[1]).append(')');
            count++;
        }
    }

    private static Class<? extends java.lang.annotation.Annotation> getFunctionMetadataClass() {
        if (functionMetadataClass == null) {
            synchronized (MethodInterceptor.class) {
                if (functionMetadataClass == null) {
                    try {
                        functionMetadataClass = Class.forName("com.matching.annotation.FunctionMetadata")
                                .asSubclass(java.lang.annotation.Annotation.class);
                    } catch (ClassNotFoundException e) {
                        // Will remain null
                    }
                }
            }
        }
        return functionMetadataClass;
    }

    public static void writeFunctionMetadataHeader() {
        try {
            instrumentationWriter.println("=== Function Metadata ===");
            instrumentationWriter.println();

            String[] classNames = {
                "com.matching.engine.MatchingEngine",
                "com.matching.engine.OrderBook"
            };

            Class<? extends java.lang.annotation.Annotation> annotationClass = getFunctionMetadataClass();
            if (annotationClass == null) {
                return;
            }

            for (String className : classNames) {
                try {
                    Class<?> clazz = Class.forName(className);
                    instrumentationWriter.println("Class: " + clazz.getSimpleName());
                    instrumentationWriter.println();

                    for (Method method : clazz.getDeclaredMethods()) {
                        try {
                            Object annotation = method.getAnnotation(annotationClass);

                            if (annotation != null) {
                                Method getFunctionName = annotation.getClass().getMethod("functionName");
                                Method getUuid = annotation.getClass().getMethod("uuid");
                                Method getDescription = annotation.getClass().getMethod("description");

                                String functionName = (String) getFunctionName.invoke(annotation);
                                String uuid = (String) getUuid.invoke(annotation);
                                String description = (String) getDescription.invoke(annotation);

                                instrumentationWriter.println("  Function: " + functionName);
                                instrumentationWriter.println("  UUID: " + uuid);
                                instrumentationWriter.println("  Description: " + description);
                                instrumentationWriter.println();
                            }
                        } catch (Throwable e) {
                            // Skip methods without annotation
                        }
                    }
                } catch (ClassNotFoundException e) {
                    // Skip if class not found
                }
            }

            instrumentationWriter.println("=== Execution Trace ===");
            instrumentationWriter.println();
        } catch (Throwable e) {
            System.err.println("Error writing function metadata header: " + e.getMessage());
        }
    }
}
