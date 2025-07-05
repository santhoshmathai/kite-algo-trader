package com.example.trading.order;

import com.example.trading.kite.KiteService; // To interact with Kite Connect for placing orders

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Manages trading orders, including placement, modification, cancellation, and status tracking.
 * Interacts with KiteService to execute trades via the broker's API.
 */
public class OrderManager {

    private final KiteService kiteService;
    // Stores active/pending orders. Key: Order ID, Value: OrderDetails (a new class to hold order info)
    private final ConcurrentHashMap<String, OrderDetails> activeOrders;
    // Stores filled/completed orders or positions. Key: Instrument Token, Value: PositionDetails
    private final ConcurrentHashMap<String, PositionDetails> openPositions;


    /**
     * Represents details of an order.
     */
    public static class OrderDetails {
        public final String orderId;
        public final String instrumentToken; // Or tradingSymbol, depending on what's used more
        public final String transactionType; // "BUY" or "SELL"
        public final int quantity;
        public final double price; // Entry price for limit orders, or fill price
        public String status; // e.g., "PENDING", "OPEN", "FILLED", "CANCELLED", "REJECTED"
        public final String orderType; // "LIMIT", "MARKET", etc.
        public final String productType; // "MIS", "CNC", etc.
        public final String tag; // Optional tag for strategy or tracking

        public OrderDetails(String orderId, String instrumentToken, String transactionType, int quantity, double price, String orderType, String productType, String tag) {
            this.orderId = orderId;
            this.instrumentToken = instrumentToken;
            this.transactionType = transactionType;
            this.quantity = quantity;
            this.price = price;
            this.orderType = orderType;
            this.productType = productType;
            this.status = "PENDING"; // Initial status
            this.tag = tag;
        }

        @Override
        public String toString() {
            return "OrderDetails{" +
                   "orderId='" + orderId + '\'' +
                   ", instrumentToken='" + instrumentToken + '\'' +
                   ", transactionType='" + transactionType + '\'' +
                   ", quantity=" + quantity +
                   ", price=" + price +
                   ", status='" + status + '\'' +
                   ", orderType='" + orderType + '\'' +
                   ", productType='" + productType + '\'' +
                   ", tag='" + tag + '\'' +
                   '}';
        }
    }

    /**
     * Represents details of an open position.
     */
    public static class PositionDetails {
        public final String instrumentToken;
        public int netQuantity; // Positive for long, negative for short
        public double averageBuyPrice;
        public double averageSellPrice; // For short positions or if tracking sells separately
        public double realizedPnl;
        public double unrealizedPnl; // Needs market data to update

        public PositionDetails(String instrumentToken) {
            this.instrumentToken = instrumentToken;
            this.netQuantity = 0;
            this.averageBuyPrice = 0;
            this.averageSellPrice = 0;
            this.realizedPnl = 0;
            this.unrealizedPnl = 0;
        }
        // Methods to update position based on fills, calculate P&L, etc.
        // This can get complex.
    }


    public OrderManager(KiteService kiteService) {
        this.kiteService = kiteService;
        this.activeOrders = new ConcurrentHashMap<>();
        this.openPositions = new ConcurrentHashMap<>();
        System.out.println("OrderManager initialized.");
    }

    /**
     * Places a new order.
     *
     * @param orderParams Map containing all necessary parameters for the KiteConnectAPI.placeOrder call.
     *                    Example keys: "tradingsymbol", "exchange", "transaction_type", "quantity",
     *                    "product", "order_type", "price" (if limit/sl), "trigger_price" (if sl/slm), "tag".
     * @param variety     The order variety (e.g., "regular", "amo", "bo", "co").
     * @return The order ID if successfully placed, otherwise null.
     */
    public String placeOrder(Map<String, Object> orderParams, String variety) {
        if (kiteService == null) {
            System.err.println("OrderManager: KiteService is not initialized. Cannot place order.");
            return null;
        }

        // Extract some details for our internal tracking
        String tradingSymbol = (String) orderParams.get("tradingsymbol");
        String transactionType = (String) orderParams.get("transaction_type");
        Integer quantity = (Integer) orderParams.get("quantity");
        Double price = (Double) orderParams.getOrDefault("price", 0.0); // Market orders might not have price
        String orderType = (String) orderParams.get("order_type");
        String productType = (String) orderParams.get("product");
        String tag = (String) orderParams.getOrDefault("tag", "default_tag");

        if (tradingSymbol == null || transactionType == null || quantity == null || orderType == null || productType == null) {
            System.err.println("OrderManager: Missing crucial order parameters for placing order.");
            return null;
        }

        System.out.println("OrderManager: Attempting to place " + transactionType + " order for " + quantity + " of " + tradingSymbol + " at " + price);
        String orderId = kiteService.placeOrder(orderParams); // Assuming KiteService.placeOrder takes Map and variety (or variety is in map)

        if (orderId != null) {
            OrderDetails details = new OrderDetails(orderId, tradingSymbol, transactionType, quantity, price, orderType, productType, tag);
            activeOrders.put(orderId, details);
            System.out.println("OrderManager: Order placed successfully. Order ID: " + orderId + ", Details: " + details);
        } else {
            System.err.println("OrderManager: Failed to place order for " + tradingSymbol + " via KiteService.");
        }
        return orderId;
    }

    /**
     * Modifies an existing pending order.
     *
     * @param orderId     The ID of the order to modify.
     * @param newParams   Map containing parameters to change (e.g., quantity, price, trigger_price).
     * @param variety     The order variety.
     * @return The new order ID if modification is successful (some brokers return a new ID), or original/updated ID.
     */
    public String modifyOrder(String orderId, Map<String, Object> newParams, String variety) {
        if (!activeOrders.containsKey(orderId)) {
            System.err.println("OrderManager: Order ID " + orderId + " not found for modification.");
            return null;
        }
        OrderDetails currentDetails = activeOrders.get(orderId);
        if (!"PENDING".equals(currentDetails.status) && !"OPEN".equals(currentDetails.status) /* some brokers use OPEN for pending limit orders */) {
            System.err.println("OrderManager: Order " + orderId + " is not in a modifiable state (" + currentDetails.status + ").");
            return null;
        }

        System.out.println("OrderManager: Attempting to modify order ID: " + orderId);
        String modifiedOrderId = kiteService.modifyOrder(orderId, newParams); // Variety might be needed by kiteService.modifyOrder

        if (modifiedOrderId != null) {
            // Update internal tracking. If ID changes, remove old, add new.
            // For simplicity, assuming ID might change or details are updated under same ID.
            // This needs to align with actual broker API behavior.
            System.out.println("OrderManager: Order " + orderId + " modification request sent. New/Updated ID: " + modifiedOrderId);
            // TODO: Update OrderDetails in activeOrders based on newParams and response.
            // If modifiedOrderId is different, might need to move details to new key.
            // For now, just log. Actual update logic depends on Kite API response.
            currentDetails.status = "PENDING_MODIFICATION"; // Or similar temporary status
        } else {
            System.err.println("OrderManager: Failed to modify order " + orderId + " via KiteService.");
        }
        return modifiedOrderId;
    }

    /**
     * Cancels an existing pending order.
     *
     * @param orderId The ID of the order to cancel.
     * @param variety The order variety.
     * @return The order ID if cancellation is successful, otherwise null.
     */
    public String cancelOrder(String orderId, String variety) {
        if (!activeOrders.containsKey(orderId)) {
            System.err.println("OrderManager: Order ID " + orderId + " not found for cancellation.");
            return null;
        }
        OrderDetails currentDetails = activeOrders.get(orderId);
         if (!"PENDING".equals(currentDetails.status) && !"OPEN".equals(currentDetails.status)) {
            System.err.println("OrderManager: Order " + orderId + " is not in a cancellable state (" + currentDetails.status + ").");
            return null;
        }

        System.out.println("OrderManager: Attempting to cancel order ID: " + orderId);
        String cancelledOrderId = kiteService.cancelOrder(orderId, variety);

        if (cancelledOrderId != null) {
            // Update internal tracking
            System.out.println("OrderManager: Order " + orderId + " cancellation request sent.");
            // currentDetails.status = "CANCELLED"; // This should be updated upon confirmation from broker
            currentDetails.status = "PENDING_CANCELLATION";
        } else {
            System.err.println("OrderManager: Failed to cancel order " + orderId + " via KiteService.");
        }
        return cancelledOrderId;
    }

    /**
     * Updates the status of an order. This would typically be called by a callback
     * from KiteService when an order update is received (e.g., via WebSocket or polling).
     *
     * @param orderId      The ID of the order.
     * @param newStatus    The new status (e.g., "FILLED", "CANCELLED", "REJECTED").
     * @param filledQuantity The quantity filled for this update.
     * @param averagePrice The average price at which it was filled for this update.
     */
    public void updateOrderStatus(String orderId, String newStatus, int filledQuantity, double averagePrice) {
        OrderDetails details = activeOrders.get(orderId);
        if (details == null) {
            System.err.println("OrderManager: Received update for unknown order ID: " + orderId);
            return;
        }

        System.out.println("OrderManager: Updating status for order " + orderId + " to " + newStatus +
                           ", Filled Qty: " + filledQuantity + ", Avg Price: " + averagePrice);
        details.status = newStatus;

        if ("FILLED".equalsIgnoreCase(newStatus) || "COMPLETE".equalsIgnoreCase(newStatus) /* Kite uses COMPLETE for fully filled */) {
            // TODO: Update position
            updatePosition(details, filledQuantity, averagePrice);
            activeOrders.remove(orderId); // Or move to a separate list of completed orders
            System.out.println("OrderManager: Order " + orderId + " is FILLED. Position updated.");
        } else if ("CANCELLED".equalsIgnoreCase(newStatus) || "REJECTED".equalsIgnoreCase(newStatus)) {
            activeOrders.remove(orderId); // Or move to a history
            System.out.println("OrderManager: Order " + orderId + " is " + newStatus + ". Removed from active orders.");
        }
        // Other statuses like "OPEN" (for pending limit orders), "TRIGGER PENDING" etc. might just update the status.
    }

    /**
     * Updates the position based on a filled order.
     */
    private void updatePosition(OrderDetails filledOrder, int filledQuantity, double fillPrice) {
        PositionDetails position = openPositions.computeIfAbsent(filledOrder.instrumentToken, PositionDetails::new);

        // This is a simplified position update logic. Real logic can be more complex with partial fills, etc.
        if ("BUY".equalsIgnoreCase(filledOrder.transactionType)) {
            double oldTotalValue = position.averageBuyPrice * position.netQuantity;
            if (position.netQuantity < 0) { // Closing a short position
                position.realizedPnl += (position.averageSellPrice - fillPrice) * Math.min(Math.abs(position.netQuantity), filledQuantity);
            }
            position.averageBuyPrice = (oldTotalValue + (double)filledQuantity * fillPrice) / (Math.abs(position.netQuantity) + filledQuantity); // This needs refinement for avg price calc
            position.netQuantity += filledQuantity;
        } else if ("SELL".equalsIgnoreCase(filledOrder.transactionType)) {
            // Similar logic for sell: update averageSellPrice, netQuantity, realizedPnl if closing a long.
            if (position.netQuantity > 0) { // Closing a long position
                position.realizedPnl += (fillPrice - position.averageBuyPrice) * Math.min(position.netQuantity, filledQuantity);
            }
            // This averaging logic needs to be robust for short selling and averaging down/up.
            position.netQuantity -= filledQuantity;
        }
        System.out.println("OrderManager: Position for " + filledOrder.instrumentToken + " updated. Net Qty: " + position.netQuantity);
    }


    public OrderDetails getOrderDetails(String orderId) {
        return activeOrders.get(orderId);
    }

    public List<OrderDetails> getAllActiveOrders() {
        return new ArrayList<>(activeOrders.values());
    }

    public PositionDetails getPosition(String instrumentToken) {
        return openPositions.get(instrumentToken);
    }

    public Map<String, PositionDetails> getAllOpenPositions() {
        return Collections.unmodifiableMap(openPositions);
    }

    // TODO: Methods to get P&L, manage margin, etc.
    // TODO: Handling of order update callbacks from KiteService (e.g., if KiteService uses WebSocket for order updates).
}
