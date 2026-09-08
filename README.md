# Kite Algo Trader

A local Java application for **NSE cash-equity opening-range breakout trading with Kite Connect**. One process handles market data, sizing, execution, recovery and reports. It includes historical replay, live-data paper trading and explicitly armed live execution. It does not implement options or US trading yet.

## Run offline first

Requirements: JDK 11 or later and PowerShell. No cloud service, database server or Maven installation is required. The official Kite SDK is pinned in `lib/`.

```powershell
.\build.ps1 test
java -jar build/kite-algo-trader.jar --help
java -jar build/kite-algo-trader.jar validate examples/synthetic.properties
java -jar build/kite-algo-trader.jar backtest examples/synthetic.properties examples/synthetic-bars.csv runs
```

Open the printed run directory for `summary.txt`, `days.csv` and each session's `report.html`, `trades.csv`, `decisions.log` and durable journal. The included data and token are fictional. A completed synthetic run verifies mechanics, not an investment edge. `--demo` retains the earlier foundation demonstration.

## First version

- A maximum of 20 configured NSE equities; refreshed daily provider tokens and tick sizes.
- A 09:15-09:30 India opening range built from 15 complete minute bars. The first subsequent close beyond the range plus a tick buffer may signal. Long entries are the default; equity shorts require configuration.
- Entries before 10:00, limit orders with a price tolerance, risk/cash/notional/participation caps, and one attempted trade per instrument per day.
- Partial-fill accounting, cancellation of unfilled entry remainder, broker-side stop requests and conversion of the same exit order for target/time exits. Default target is 1.5 times entry-to-stop distance; flatten begins at 10:15.
- Journal-before-request persistence, broker reconciliation, a single-writer lock, restart into management-only mode, stale-feed handling and a file stop switch.
- Estimated dated intraday costs, replay slippage, incomplete-data flags, and local HTML/CSV reports.

Start with [the operating guide](docs/OPERATIONS.md) for Kite login, daily workflow and recovery. Read [the architecture](docs/ARCHITECTURE.md) for module boundaries and future US/options work, and [validation notes](docs/VALIDATION.md) for what was actually tested.

The live adapter is compiled and available but has not been tested against this user's broker account. No real historical performance validation has been completed. Orders can be rejected, market-protected exits can remain unfilled, and losses can exceed configured budgets. Keep the process running until broker flatness is confirmed.

## Source layout

| Package | Responsibility |
| --- | --- |
| `app` | Configuration, calendar, listings, fees, coordinator, live bars, runtime and reports |
| `broker` | Broker contract, official Kite adapter and simulator |
| `replay` | Chronological file replay, hashes and multi-session reports |
| `storage` | Checksummed, locked, durable journal |
| `strategy` | Pure opening-range signals shared by replay and runtime |
| `core`, `data`, `order`, `risk` | Existing foundation components and regression-tested demonstration |

The current runtime uses `TradingEngine` for account/execution coordination; the earlier `OrderManager` and `RiskManager` remain foundation code, not a second live execution path. Everything runs inside one JVM. No web server is exposed.

## Paper experiment with INR 5 lakh

Use [the month-long paper guide](docs/PAPER-MONTH-GUIDE.md) for the prepared profile, daily login/start/stop routine, order inspection, weekly summaries and this week's learning plan. The [AI roadmap](docs/AI-ROADMAP.md) separates proposed enhancements from today's rule-based execution.

## IBKR US integration foundation

The same repository now has an optional [IBKR integration foundation and staged plan](docs/IBKR-INTEGRATION.md): US session-aware ORB, calendar and a read-only TWS/Gateway diagnostic. Build it separately with build-ibkr.ps1. Automated IBKR paper orders are not implemented yet; the existing Kite commands remain available.
