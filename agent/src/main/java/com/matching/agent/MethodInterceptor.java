package com.matching.agent;

import net.bytebuddy.asm.Advice;

import java.io.FileWriter;
import java.io.IOException;
import java.io.PrintWriter;
import java.lang.reflect.Method;
import java.nio.ByteBuffer;
import java.time.Instant;
import java.util.Base64;
import java.util.UUID;

public class MethodInterceptor {

    public static final PrintWriter instrumentationWriter;
    public static volatile boolean headerWritten = false;

    static {
        PrintWriter writer = null;
        try {
            writer = new PrintWriter(new FileWriter("instrumentation.log", false), true);
        } catch (IOException e) {
            System.err.println("Failed to create instrumentation log file: " + e.getMessage());
        }
        instrumentationWriter = writer;
    }

    public static final ThreadLocal<Integer> callDepth = ThreadLocal.withInitial(() -> 0);
    public static final ThreadLocal<UUID> currentOrderId = ThreadLocal.withInitial(() -> null);

    @Advice.OnMethodEnter
    public static void onEnter(@Advice.Origin Method method,
                               @Advice.AllArguments Object[] args) {
        try {
            // Write function metadata header on first call
            if (!headerWritten) {
                synchronized (MethodInterceptor.class) {
                    if (!headerWritten) {
                        writeFunctionMetadataHeader();
                        headerWritten = true;
                    }
                }
            }

            UUID orderId = extractOrderId(args);
            String functionUuid = getFunctionUuid(method);
            int depth = callDepth.get();

            // Update current order ID only at top level (MatchingEngine.addOrder)
            if (orderId != null && depth == 0 && functionUuid.equals("8KGyw9TlT1prfI2eDxorPA")) {
                currentOrderId.set(orderId);
            }

            // Always use current order ID from context
            UUID contextOrderId = currentOrderId.get();

            // Print incoming order details at the start of MatchingEngine.addOrder
            if (depth == 0 && functionUuid.equals("8KGyw9TlT1prfI2eDxorPA") && args != null && args.length > 0) {
                printIncomingOrder(args[0], contextOrderId, "");
            }

            String indent = "  ".repeat(depth);
            String orderIdStr = contextOrderId != null ? uuidToBase64(contextOrderId) : "N/A";
            if (instrumentationWriter != null) {
                instrumentationWriter.println(Instant.now() + " | " +
                                 orderIdStr + " | " +
                                 indent + "CALL | " + functionUuid);
            }

            // Print execution report details if this is recordExecutionReport
            if (functionUuid.equals("5_al9qe4TF2eDxorPE1eaw") && args != null && args.length > 0) {
                printExecutionReport(args[0], contextOrderId, indent);
            }

            // Print order book addition details if this is OrderBook.addOrder
            if (functionUuid.equals("-KmwwdLjT1prfI2eDxorPA") && args != null && args.length > 0) {
                printOrderBookAddition(args[0], contextOrderId, indent);
            }

            callDepth.set(depth + 1);
        } catch (Exception e) {
            // Silently ignore instrumentation errors
        }
    }

    @Advice.OnMethodExit(onThrowable = Throwable.class)
    public static void onExit(@Advice.Origin Method method,
                              @Advice.AllArguments Object[] args,
                              @Advice.This(optional = true) Object instance) {
        try {
            int depth = callDepth.get() - 1;
            callDepth.set(Math.max(0, depth));

            String functionUuid = getFunctionUuid(method);
            UUID contextOrderId = currentOrderId.get();

            // Print order book snapshot after MatchingEngine.addOrder completes
            if (functionUuid.equals("8KGyw9TlT1prfI2eDxorPA") && depth == 0) {
                String indent = "  ".repeat(depth);
                printOrderBookSnapshot(instance, contextOrderId, indent);
                // Clear current order ID when exiting top-level MatchingEngine.addOrder
                currentOrderId.remove();
            }
        } catch (Exception e) {
            // Silently ignore instrumentation errors
        }
    }

    public static UUID extractOrderId(Object[] args) {
        if (args == null || args.length == 0) {
            return null;
        }

        for (Object arg : args) {
            if (arg instanceof UUID) {
                return (UUID) arg;
            }
            if (arg != null && arg.getClass().getSimpleName().equals("Order")) {
                try {
                    Method getOrderId = arg.getClass().getMethod("getOrderId");
                    Object result = getOrderId.invoke(arg);
                    if (result instanceof UUID) {
                        return (UUID) result;
                    }
                } catch (Exception e) {
                    // Continue searching
                }
            }
        }
        return null;
    }

    public static String getFunctionUuid(Method method) {
        try {
            Object annotation = method.getAnnotation(
                    Class.forName("com.matching.annotation.FunctionMetadata")
                           .asSubclass(java.lang.annotation.Annotation.class)
            );
            if (annotation != null) {
                Method uuidMethod = annotation.getClass().getMethod("uuid");
                // UUID is already in Base64 format in the annotation
                return (String) uuidMethod.invoke(annotation);
            }
        } catch (Exception e) {
            // Return method name if annotation not found
        }
        return "unknown";
    }

    public static void printOrderBookSnapshot(Object matchingEngine, UUID orderId, String indent) {
        try {
            // Get the OrderBook from MatchingEngine
            Method getOrderBook = matchingEngine.getClass().getMethod("getOrderBook");
            Object orderBook = getOrderBook.invoke(matchingEngine);

            // Access buySide and sellSide fields via reflection
            java.lang.reflect.Field buySideField = orderBook.getClass().getDeclaredField("buySide");
            java.lang.reflect.Field sellSideField = orderBook.getClass().getDeclaredField("sellSide");
            buySideField.setAccessible(true);
            sellSideField.setAccessible(true);

            Object buySide = buySideField.get(orderBook);
            Object sellSide = sellSideField.get(orderBook);

            // Format buy side (TreeMap<BigDecimal, LinkedList<Order>>)
            String buyLevels = formatPriceLevels(buySide);
            String sellLevels = formatPriceLevels(sellSide);

            String orderIdStr = orderId != null ? uuidToBase64(orderId) : "N/A";
            if (instrumentationWriter != null) {
                instrumentationWriter.println(Instant.now() + " | " +
                                 orderIdStr + " | " +
                                 indent + "SNAPSHOT | " +
                                 "Buy: [" + buyLevels + "] " +
                                 "Sell: [" + sellLevels + "]");
            }
        } catch (Exception e) {
            // Silently ignore snapshot errors
        }
    }

    @SuppressWarnings("unchecked")
    public static String formatPriceLevels(Object priceMap) {
        try {
            java.util.TreeMap<Object, Object> map = (java.util.TreeMap<Object, Object>) priceMap;
            if (map.isEmpty()) {
                return "";
            }

            StringBuilder sb = new StringBuilder();
            boolean firstPrice = true;

            for (java.util.Map.Entry<Object, Object> entry : map.entrySet()) {
                Object price = entry.getKey();
                Object orderList = entry.getValue();

                if (orderList instanceof java.util.List) {
                    java.util.List<?> orders = (java.util.List<?>) orderList;

                    for (Object order : orders) {
                        try {
                            Method getOrderId = order.getClass().getMethod("getOrderId");
                            Method getRemainingQuantity = order.getClass().getMethod("getRemainingQuantity");

                            Object orderId = getOrderId.invoke(order);
                            long quantity = (long) getRemainingQuantity.invoke(order);

                            // Format: price@orderIdBase64:qty
                            String orderIdBase64 = uuidToBase64((UUID) orderId);

                            if (!firstPrice) {
                                sb.append(", ");
                            }
                            sb.append(price).append("@").append(orderIdBase64).append(":").append(quantity);
                            firstPrice = false;
                        } catch (Exception e) {
                            // Skip if method not found
                        }
                    }
                }
            }

            return sb.toString();
        } catch (Exception e) {
            return "";
        }
    }

    public static String uuidToBase64(UUID uuid) {
        ByteBuffer buffer = ByteBuffer.wrap(new byte[16]);
        buffer.putLong(uuid.getMostSignificantBits());
        buffer.putLong(uuid.getLeastSignificantBits());
        return Base64.getUrlEncoder().withoutPadding().encodeToString(buffer.array());
    }

    public static void printExecutionReport(Object executionReport, UUID contextOrderId, String indent) {
        if (executionReport == null) {
            return;
        }

        try {
            // Extract fields from ExecutionReport using reflection
            Class<?> reportClass = executionReport.getClass();
            Method getOrderId = reportClass.getMethod("getOrderId");
            Method getSide = reportClass.getMethod("getSide");
            Method getExecutionType = reportClass.getMethod("getExecutionType");
            Method getOrderSize = reportClass.getMethod("getOrderSize");
            Method getPrice = reportClass.getMethod("getPrice");
            Method getLastQuantity = reportClass.getMethod("getLastQuantity");
            Method getCumulativeQuantity = reportClass.getMethod("getCumulativeQuantity");

            UUID reportOrderId = (UUID) getOrderId.invoke(executionReport);
            Object side = getSide.invoke(executionReport);
            Object executionType = getExecutionType.invoke(executionReport);
            long orderSize = (long) getOrderSize.invoke(executionReport);
            Object price = getPrice.invoke(executionReport);
            long lastQuantity = (long) getLastQuantity.invoke(executionReport);
            long cumulativeQuantity = (long) getCumulativeQuantity.invoke(executionReport);

            String orderIdBase64 = uuidToBase64(reportOrderId);
            String priceStr = price != null ? price.toString() : "";
            String contextOrderIdStr = contextOrderId != null ? uuidToBase64(contextOrderId) : "N/A";

            if (instrumentationWriter != null) {
                instrumentationWriter.println(Instant.now() + " | " +
                                 contextOrderIdStr + " | " +
                                 indent + "  EXEC_REPORT | " +
                                 orderIdBase64 + " | " +
                                 side + " | " +
                                 executionType + " | " +
                                 "qty=" + orderSize + " | " +
                                 "lastQty=" + lastQuantity + " | " +
                                 "cumQty=" + cumulativeQuantity + " | " +
                                 "price=" + priceStr);
            }
        } catch (Exception e) {
            String contextOrderIdStr = contextOrderId != null ? uuidToBase64(contextOrderId) : "N/A";
            System.err.println("Error printing execution report: " + e.getMessage());
            e.printStackTrace();
        }
    }

    public static void printOrderBookAddition(Object order, UUID contextOrderId, String indent) {
        if (order == null) {
            return;
        }

        try {
            // Extract fields from Order using reflection
            Class<?> orderClass = order.getClass();
            Method getOrderId = orderClass.getMethod("getOrderId");
            Method getSide = orderClass.getMethod("getSide");
            Method getPrice = orderClass.getMethod("getPrice");
            Method getRemainingQuantity = orderClass.getMethod("getRemainingQuantity");
            Method getCumulativeQuantity = orderClass.getMethod("getCumulativeQuantity");

            UUID orderId = (UUID) getOrderId.invoke(order);
            Object side = getSide.invoke(order);
            Object price = getPrice.invoke(order);
            long remainingQuantity = (long) getRemainingQuantity.invoke(order);
            long cumulativeQuantity = (long) getCumulativeQuantity.invoke(order);

            String orderIdBase64 = uuidToBase64(orderId);
            String priceStr = price != null ? price.toString() : "";
            String contextOrderIdStr = contextOrderId != null ? uuidToBase64(contextOrderId) : "N/A";

            if (instrumentationWriter != null) {
                instrumentationWriter.println(Instant.now() + " | " +
                                 contextOrderIdStr + " | " +
                                 indent + "  BOOK_ADD | " +
                                 orderIdBase64 + " | " +
                                 side + " | " +
                                 "price=" + priceStr + " | " +
                                 "remainingQty=" + remainingQuantity + " | " +
                                 "cumQty=" + cumulativeQuantity);
            }
        } catch (Exception e) {
            String contextOrderIdStr = contextOrderId != null ? uuidToBase64(contextOrderId) : "N/A";
            System.err.println("Error printing order book addition: " + e.getMessage());
            e.printStackTrace();
        }
    }

    public static void printIncomingOrder(Object order, UUID contextOrderId, String indent) {
        if (order == null) {
            return;
        }

        try {
            // Extract fields from Order using reflection
            Class<?> orderClass = order.getClass();
            Method getOrderId = orderClass.getMethod("getOrderId");
            Method getSide = orderClass.getMethod("getSide");
            Method getOrderType = orderClass.getMethod("getOrderType");
            Method getPrice = orderClass.getMethod("getPrice");
            Method getQuantity = orderClass.getMethod("getQuantity");

            UUID orderId = (UUID) getOrderId.invoke(order);
            Object side = getSide.invoke(order);
            Object orderType = getOrderType.invoke(order);
            Object price = getPrice.invoke(order);
            long quantity = (long) getQuantity.invoke(order);

            String orderIdBase64 = uuidToBase64(orderId);
            String priceStr = price != null ? price.toString() : "";
            String contextOrderIdStr = contextOrderId != null ? uuidToBase64(contextOrderId) : "N/A";

            if (instrumentationWriter != null) {
                instrumentationWriter.println(Instant.now() + " | " +
                                 contextOrderIdStr + " | " +
                                 indent + "ORDER_IN | " +
                                 orderIdBase64 + " | " +
                                 side + " | " +
                                 orderType + " | " +
                                 "qty=" + quantity + " | " +
                                 "price=" + priceStr);
            }
        } catch (Exception e) {
            String contextOrderIdStr = contextOrderId != null ? uuidToBase64(contextOrderId) : "N/A";
            System.err.println("Error printing incoming order: " + e.getMessage());
            e.printStackTrace();
        }
    }

    public static void writeFunctionMetadataHeader() {
        if (instrumentationWriter == null) {
            return;
        }

        try {
            instrumentationWriter.println("=== Function Metadata ===");
            instrumentationWriter.println();

            // Scan MatchingEngine and OrderBook classes
            String[] classNames = {
                "com.matching.engine.MatchingEngine",
                "com.matching.engine.OrderBook"
            };

            for (String className : classNames) {
                try {
                    Class<?> clazz = Class.forName(className);
                    instrumentationWriter.println("Class: " + clazz.getSimpleName());
                    instrumentationWriter.println();

                    // Get all methods with FunctionMetadata annotation
                    for (Method method : clazz.getDeclaredMethods()) {
                        try {
                            Object annotation = method.getAnnotation(
                                Class.forName("com.matching.annotation.FunctionMetadata")
                                     .asSubclass(java.lang.annotation.Annotation.class)
                            );

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
                        } catch (Exception e) {
                            // Skip methods without annotation
                        }
                    }
                } catch (ClassNotFoundException e) {
                    // Skip if class not found
                }
            }

            instrumentationWriter.println("=== Execution Trace ===");
            instrumentationWriter.println();
        } catch (Exception e) {
            System.err.println("Error writing function metadata header: " + e.getMessage());
        }
    }
}
