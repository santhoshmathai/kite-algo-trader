package com.example.trading.app;
import java.time.LocalDate;
/** Estimated costs in the profile currency; broker statements remain authoritative. */
@FunctionalInterface
public interface TradeCosts {
    double estimate(LocalDate date,String side,int quantity,double price);
}
