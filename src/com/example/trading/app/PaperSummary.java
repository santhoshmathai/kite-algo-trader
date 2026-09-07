package com.example.trading.app;

import java.io.*;
import java.math.BigDecimal;
import java.nio.file.*;
import java.time.*;
import java.util.*;

/** Read-only accounting aggregation over explicitly requested paper session dates. */
public final class PaperSummary {
    private PaperSummary() { }

    public static Path run(Settings cfg, String account, LocalDate from, LocalDate to) throws IOException {
        if (!account.matches("[A-Za-z0-9]+") || to.isBefore(from) || from.plusYears(1).isBefore(to))
            throw new IllegalArgumentException("Invalid account or report date range");
        SessionCalendar calendar = new SessionCalendar(cfg.path("calendar"));
        Path root = cfg.path("state").resolve("paper").resolve(account);
        List<String> rows = new ArrayList<>();
        rows.add("date,classification,filled_trades,net_pnl,reason");
        BigDecimal total = BigDecimal.ZERO;
        int included = 0, excluded = 0, filledTrades = 0, operationalWarnings = 0;
        LocalDate today = LocalDate.now(SessionCalendar.ZONE);
        for (LocalDate day = from; !day.isAfter(to); day = day.plusDays(1)) {
            // A missing allowlist date is not assumed to be a validated exchange holiday.
            if (!calendar.isOpenDate(day)) {
                rows.add(day + ",NOT_IN_CALENDAR,,,Verify calendar coverage");
                continue;
            }
            Path directory = root.resolve(day.toString());
            if (!Files.exists(directory.resolve("session.properties"))) {
                rows.add(day + "," + (day.isAfter(today) ? "FUTURE" : "MISSING") + ",,,No session report");
                excluded++;
                continue;
            }
            try {
                Properties p = new Properties();
                try (Reader reader = Files.newBufferedReader(directory.resolve("session.properties"))) { p.load(reader); }
                if (!"1".equals(p.getProperty("schema")) || !"PAPER".equals(p.getProperty("mode"))
                        || !day.toString().equals(p.getProperty("date"))
                        || Double.parseDouble(p.getProperty("capital")) != cfg.capital
                        || !cfg.fingerprint().equals(p.getProperty("configFingerprint")))
                    throw new IllegalArgumentException("Session metadata/configuration mismatch");
                if (!"FINISHED".equals(p.getProperty("state")) || !"true".equals(p.getProperty("flat"))) {
                    rows.add(day + ",INCOMPLETE,,,Not finished and confirmed flat"); excluded++; continue;
                }
                BigDecimal net = new BigDecimal(p.getProperty("netPnl"));
                BigDecimal tradeTotal = BigDecimal.ZERO;
                int trades = 0;
                for (String[] r : Csv.read(directory.resolve("trades.csv"),
                        "instrument,side,entry_quantity,entry_average,remaining,gross_pnl,estimated_costs,net_pnl,exit_reason")) {
                    if (r.length != 9 || Integer.parseInt(r[2]) < 0 || Integer.parseInt(r[4]) != 0)
                        throw new IllegalArgumentException("Invalid or open trade");
                    if (Integer.parseInt(r[2]) > 0) trades++;
                    tradeTotal = tradeTotal.add(new BigDecimal(r[7]));
                }
                if (tradeTotal.subtract(net).abs().compareTo(new BigDecimal("0.01")) > 0)
                    throw new IllegalArgumentException("Trade total differs from session total");
                String reason = p.getProperty("reason", "");
                boolean operational = !reason.equals("Morning time exit") && !reason.equals("Daily loss limit");
                if (operational) operationalWarnings++;
                rows.add(day + "," + (operational ? "FINISHED_WITH_WARNING" : "FINISHED") + "," + trades + "," + net + "," + Csv.cell(reason));
                total = total.add(net); filledTrades += trades; included++;
            } catch (IOException | IllegalArgumentException | NullPointerException e) {
                // Do not silently count unreadable, mixed-profile or partially written reports as zero P&L.
                rows.add(day + ",INVALID,,,Check session metadata and trade report"); excluded++;
            }
        }
        Path output = root.resolve("summaries").resolve(from + "_" + to + "_" + UUID.randomUUID());
        Files.createDirectories(output);
        Files.write(output.resolve("days.csv"), rows);
        String text = "PAPER ACCOUNTING SUMMARY: " + from + " through " + to + "\n"
                + "Fixed daily paper allocation: INR " + cfg.capital + " (capital resets each session; no compounding)\n"
                + "Included finished sessions: " + included + "\nMissing/future/incomplete/invalid sessions: " + excluded + "\n"
                + "Finished sessions with operational warnings: " + operationalWarnings + "\nFilled trades: " + filledTrades + "\n"
                + (included == 0 ? "No completed paper results yet. Profit/loss is unavailable.\n"
                   : "Net P&L of included sessions: INR " + total.toPlainString() + "\n"
                     + String.format(Locale.ROOT, "Return on fixed allocation: %.4f%%\n", total.doubleValue() / cfg.capital * 100)
                     + "Reference balance (allocation + included net P&L): INR " + BigDecimal.valueOf(cfg.capital).add(total).toPlainString() + "\n")
                + "Any excluded session means this is a partial period. Keep operational-warning sessions visible when evaluating reliability.\n"
                + "NOT_IN_CALENDAR dates need calendar review; absent dates are not proof of exchange holidays.\n"
                + "Estimated trading costs are included; API/computer/internet costs are excluded. This is not a forecast.\n";
        Files.writeString(output.resolve("summary.txt"), text);
        return output;
    }
}
