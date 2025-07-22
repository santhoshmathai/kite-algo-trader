package com.example.trading.risk;

import com.example.trading.util.LoggingUtil;

public class RiskManagerThread extends Thread {

    private final RiskManager riskManager;
    private volatile boolean running = true;

    public RiskManagerThread(RiskManager riskManager) {
        this.riskManager = riskManager;
    }

    @Override
    public void run() {
        while (running) {
            try {
                // Check the risk of the portfolio every second
                riskManager.checkDrawdown();
                Thread.sleep(1000);
            } catch (InterruptedException e) {
                LoggingUtil.error("RiskManagerThread interrupted.", e);
                running = false;
            }
        }
    }

    public void stopThread() {
        running = false;
        interrupt();
    }
}
