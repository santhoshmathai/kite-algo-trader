# Kite Algo Trader: first milestone

This build is an **offline Java foundation** for an equity opening-range breakout application. It cannot connect to Kite or place real orders. It has a runnable synthetic demonstration and deterministic regression checks, not a historical profitability backtest.

The proposed [India and US application architecture](docs/ARCHITECTURE.md) defines a standalone modular Java application, market/broker boundaries, recovery design and an incremental migration plan. It describes planned work, not features already implemented.

## Run on Windows

Requires a JDK version 11 or newer and PowerShell. No Maven, account credentials, network access, or additional libraries are required for these commands. Set `JAVA_HOME` to your JDK if it is not discoverable from `java`.

From this repository directory (`source-review` in the current workspace):

```powershell
.\build.ps1 test
.\build.ps1 demo
```

The script creates `build/kite-algo-trader.jar`. You can subsequently run:

```powershell
java -jar .\build\kite-algo-trader.jar --demo
```

`--help` describes supported usage; `--live` is rejected. The demo uses an explicitly fictional `DEMO_EQUITY`, synthetic prices and simulated fill confirmations. Its profit figure excludes charges and is not a performance result. Build outputs use fresh directories to prevent stale classes being reused.

For environments with Maven, `mvn test package` uses the existing JUnit dependencies to invoke the same checks. Maven execution has not been verified in this workspace because Maven is unavailable. The PowerShell build is verified independently.

## What changed

- Immutable, validated OHLCV candles normalized to `Asia/Kolkata`.
- Completed one-minute bars only; 5- and 15-minute bars require all consecutive constituent minutes.
- Tick volume uses differences in cumulative day volume. Repeated last-trade quantities are not summed. Duplicate snapshots, late ticks, day rollover, gaps and late startup have explicit handling.
- An instrument registry separates numerical/data identifiers from exchange-qualified order symbols. Non-tradable instruments cannot be ordered.
- The offline order ledger handles cumulative partial fills, duplicate and stale notifications, cancelled orders with fills, long/short averaging, partial closes, side reversals, and realized/unrealized gross P&L.
- Order and position getters return snapshots. Submitting an order never implies execution. Simulation IDs are monotonic within one ledger instance.
- Risk exits retain protection until flat and avoid duplicate outstanding exits. A rejected or cancelled exit can be retried for remaining exposure. Loss limits block further entries.
- A small sizing function applies risk, cash, notional and liquidity caps. Its optional cost allowance is not a brokerage fee calculator.
- ORB now generates signals separately from execution. It uses a complete opening range from the current session, one signal per day, an entry cutoff based on observation time, and explicit short-side opt-in. The previous unvalidated gap, fixed-volume and momentum filters were removed from the baseline.
- Removed credential prompts and the inert background loop from the entry point. The demo terminates after its simulated exit is confirmed.

## Contracts for the next implementation stage

`TimeSeriesManager.addTick` consumes ordered cumulative-volume snapshots. Call `advanceTime` at boundaries to close a forming minute even without a new tick. Getters exclude forming candles. A snapshot feed cannot reconstruct trades missed between snapshots; compare recorded bars with historical broker candles before using them for research or live signals.

The first minute is accepted only when observation starts exactly at the regular session open. A late-start or gap-recovery minute is omitted because its OHLCV is incomplete. Missing minutes are not filled with invented prices. Same-session cumulative-volume decreases and late ticks are rejected for the future feed adapter to log/reconcile.

For historical replay, call `addClosedCandle` in chronological order and pass each completed bar to the strategy as it becomes available. `loadHistoricalCandles` is warm-up only, requires an empty instrument and one-minute input, and validates the batch before loading. Do not mix historical input and tick replay for the same instrument in this version. Arrays returned by a data source must not be treated as all available before their timestamps.

`ORBStrategy.onClosedCandle` requires an observation timestamp at or after candle close. It accepts a maximum five-second observation delay; older bars can warm up the range but cannot trigger fresh entries. A signal is consumed even if execution subsequently declines it. Defaults in the demo: 09:15-09:30 range, entries strictly before 10:00, long only, 0.05 absolute price buffer. Buffer/tick-size alignment must be added with real instrument metadata before broker integration.

`OrderManager.updateOrderStatus` expects cumulative filled quantity and cumulative average fill price, not per-fill increments. It derives incremental value, including when another order has already reduced a partially filled position. Terminal order records remain available. Corrections to already-accounted fill prices require reconciliation and are rejected. Cancellation is synchronous only because this is a simulator. Modification is explicitly unsupported. All orders are MIS; no product mixing, broker adapter, fees, persistence or restart recovery exists yet.

`RiskManager` marks a supplied price and measures loss against the capital supplied to its constructor. Provide fresh marks before checking the loss limit. Create fresh strategy/ledger/risk state for a new offline session after confirming the previous session is flat. It does not fetch account balances or guarantee maximum realized loss. Exit rejection/non-fill remains exposure until a subsequent confirmation. The future execution adapter must coordinate stop and target orders and cancellation/fill races against actual broker state.

`TradingSession` supports regular weekdays and a caller-supplied holiday set. Its empty default holiday set is suitable for these fixtures only. Exchange holidays and special sessions require a maintained calendar before real data or live operation.

## Validation

The dependency-free suite tests exact candle OHLCV and timestamp results, volume semantics, session boundaries, historical/live equivalence, incomplete data rejection, instrument identity, accounting, fill idempotency, risk exits, session-reset ORB signals and sizing. It includes the three defects reproduced during review: malformed 15-minute bars, lost partial-fill exposure and incorrect short P&L.

## Still to build

1. Historical CSV/Kite-SDK data ingestion, portfolio replay and a trade-by-trade ledger with dated fee/slippage assumptions.
2. A clock-driven morning coordinator: no new entries after 10:00, flatten from 10:15 and keep running until all positions and pending orders are resolved.
3. Cross-stock risk reservations, maximum concurrent positions, sector shortlist research, and out-of-sample comparisons.
4. Persistent journal, recovery, daily login, maintained instruments/calendar, Kite SDK integration, paper monitoring, then controlled live execution.
5. Options as a separately validated later stage.

This milestone verifies software behaviour. It does not establish an edge or complete the trading application.
