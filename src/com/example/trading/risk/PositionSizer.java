package com.example.trading.risk;

/** Cash-equity sizing without leverage. Cost allowance is a research input, not a fee model. */
public final class PositionSizer {
    private PositionSizer() { }
    public static int quantity(double entry, double stop, double riskBudget, double availableCash,
                               double maximumNotional, int liquidityCap, double costPerShare) {
        for (double value : new double[]{entry, stop, riskBudget, availableCash, maximumNotional, costPerShare})
            if (!Double.isFinite(value) || value < 0) throw new IllegalArgumentException("Invalid sizing input");
        if (entry == 0 || stop == 0 || entry == stop || liquidityCap < 0)
            throw new IllegalArgumentException("Invalid entry/stop/liquidity");
        double loss = Math.abs(entry - stop) + costPerShare;
        return (int) Math.floor(Math.min(liquidityCap, Math.min(riskBudget / loss,
                Math.min(availableCash / (entry + costPerShare), maximumNotional / entry))));
    }
}
