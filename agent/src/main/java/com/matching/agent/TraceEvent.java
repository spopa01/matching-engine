package com.matching.agent;

public class TraceEvent {

    public static final int TYPE_CALL = 0;
    public static final int TYPE_ORDER_IN = 1;
    public static final int TYPE_EXEC_REPORT = 2;
    public static final int TYPE_BOOK_ADD = 3;
    public static final int TYPE_SNAPSHOT = 4;

    public int eventType;
    public String contextOrderId;
    public int depth;

    // CALL events
    public String functionUuid;

    // ORDER_IN, EXEC_REPORT, BOOK_ADD
    public String orderId;
    public Object side;           // enum ref — toString in drain thread
    public Object orderType;      // enum ref
    public Object executionType;  // enum ref
    public Object price;          // BigDecimal ref

    public long quantity;
    public long orderSize;
    public long lastQuantity;
    public long cumulativeQuantity;
    public long remainingQuantity;

    // SNAPSHOT is a marker-only event (book state reconstructed on drain thread)

    public void reset() {
        contextOrderId = null;
        functionUuid = null;
        orderId = null;
        side = null;
        orderType = null;
        executionType = null;
        price = null;
    }
}
