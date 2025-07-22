package com.example.trading.risk;

import com.example.trading.order.OrderManager;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.HashMap;
import java.util.Map;

import static org.mockito.Mockito.*;

class RiskManagerTest {

    private RiskManager riskManager;
    private OrderManager orderManager;

    @BeforeEach
    void setUp() {
        orderManager = mock(OrderManager.class);
        riskManager = new RiskManager(orderManager);
    }

    @Test
    void testStopLossForLongPosition() {
        String instrumentToken = "INFY";
        double stopLossPrice = 1485.0; // 1% stop-loss
        int quantity = 10;

        // Simulate a long position
        OrderManager.PositionDetails position = new OrderManager.PositionDetails(instrumentToken);
        position.netQuantity = quantity;
        when(orderManager.getPosition(instrumentToken)).thenReturn(position);

        riskManager.setStopLoss(instrumentToken, stopLossPrice);
        riskManager.checkPositions(instrumentToken, 1480.0); // Price drops below stop-loss

        // Verify that a sell order is placed to square off the position
        verify(orderManager, times(1)).placeOrder(anyMap(), eq("regular"));
    }

    @Test
    void testTakeProfitForLongPosition() {
        String instrumentToken = "INFY";
        double takeProfitPrice = 1530.0; // 2% take-profit
        int quantity = 10;

        // Simulate a long position
        OrderManager.PositionDetails position = new OrderManager.PositionDetails(instrumentToken);
        position.netQuantity = quantity;
        when(orderManager.getPosition(instrumentToken)).thenReturn(position);

        riskManager.setTakeProfit(instrumentToken, takeProfitPrice);
        riskManager.checkPositions(instrumentToken, 1535.0); // Price rises above take-profit

        // Verify that a sell order is placed to square off the position
        verify(orderManager, times(1)).placeOrder(anyMap(), eq("regular"));
    }

    @Test
    void testMaxDrawdownExceeded() {
        // This test is more complex and would require a more detailed simulation of portfolio value changes.
        // For now, we will just test the basic logic of the risk mitigation mode.

        // Simulate a large loss
        OrderManager.PositionDetails position = new OrderManager.PositionDetails("INFY");
        position.netQuantity = 10;
        position.averageBuyPrice = 1500;
        when(orderManager.getAllOpenPositions()).thenReturn(Map.of("INFY", position));

        riskManager.checkPositions("INFY", 1300); // Simulate a large price drop

        // Verify that the risk mitigation mode is triggered
        verify(orderManager, atLeastOnce()).placeOrder(anyMap(), eq("regular"));
    }
}
