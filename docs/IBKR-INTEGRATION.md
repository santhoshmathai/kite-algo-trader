# IBKR integration architecture and setup

The same standalone Java application now supports an optional US **IBKR paper execution** runtime. Start with [the paper operating guide](IBKR-PAPER-TRADING.md) for commands, approval prerequisites, USD configuration, timing, stopping and reports.

## Boundaries

- The India CLI, Kite SDK adapter, India defaults and INR reports stay unchanged.
- `TradingEngine` accepts an explicit trading session and cost estimator; its original India constructor retains the original behavior. `Equity` supports an explicit venue, preserving its NSE default.
- `IbkrPaperBroker` uses the official TWS socket Java SDK, isolates asynchronous callbacks, qualifies US contracts, allocates durable order IDs/references, reconciles orders/executions/positions/cash, and binds all mutations to an exact DU account.
- `IbkrPaperRuntime` owns the event loop. It aggregates complete five-second bars, passes completed minutes to shared ORB logic, polls confirmations, handles STOP/time exits and writes US reports.
- IBKR exits use cancel-confirm-replace instead of the Kite adapter's stop-to-market modification. Reconciliation computes remaining quantity before replacement, including a stop fill racing cancellation.
- An optional adapter, a shared strategy/risk engine, local journals and CSV/properties reports remain sufficient. No second repository, web server, cloud host or database service is needed.

No real-money IBKR command exists. US shorts, options, sector selection, AI, historical data download/backtesting and automatic reconnect remain later scope.

## Official SDK installation

Use the official [TWS API distribution](https://interactivebrokers.github.io/) and a compatible TWS/IB Gateway. API 10.45 is the pinned build layout:

```text
IBJts/source/JavaClient/TwsApi.jar
IBJts/source/JavaClient/jars/protobuf-java-4.29.5.jar
```

Set `IBKR_API_HOME` to your extracted `IBJts` directory, or use the ignored local default `.deps/ibkr/api-1045/IBJts`. The SDK and protobuf JAR must both be present. Do not copy SDK source/JARs into tracked files; this repository does not redistribute them. The locally reviewed archive was `twsapi_macunix.1045.01.zip`, SHA-256 `56EA048911052E86D6621AB712957C790FCE6D547BC2A55900136AE4F6835941`.

[IBKR's current requirements](https://www.interactivebrokers.com/docs/tws-api/doc/notes-limitations/requirements) specify Java 21. Set `IBKR_JAVA_HOME` accordingly for connections. The SDK classes and optional source compile on Java 17 for offline checks, but the probe and paper connector explicitly reject a socket connection below Java 21.

```powershell
.\build.ps1 test
.\build-ibkr.ps1 test
$IbkrClasspath = Get-Content build/ibkr-classpath.txt -Raw
$IbkrJava = Join-Path $env:IBKR_JAVA_HOME 'bin/java.exe'
& $IbkrJava -cp $IbkrClasspath com.example.trading.ibkr.IbkrMain --help
```

The artifact keeps the existing name `build/ibkr-foundation.jar` for command compatibility. Its contents now include paper execution. Runtime classpath paths are machine-local; rebuild after moving the checkout. The normal India build never needs the IBKR SDK.

## Market and broker constraints

US hours come from `UsCalendar`, an explicit dated allowlist interpreted in `America/New_York`, with London conversion through IANA timezone rules. The supplied calendar covers 2026, including holidays and early closes; unknown years are blocked. Validate updates against [NYSE's hours/calendar](https://www.nyse.com/trade/hours-calendars).

[Real-time bars](https://interactivebrokers.github.io/tws-api/realtime_bars.html) are five seconds, and US stock volume can use a multiplier of 100. This version deliberately leaves raw volume unscaled for participation sizing: it can under-size when the server reports lots, rather than incorrectly inflate participation when it reports shares. Confirm units with your TWS configuration before refining this.

[Account summary](https://interactivebrokers.github.io/tws-api/account_summary.html) exposes settled cash and currency values. The first implementation requires USD settled cash and USD cash balance, allocates no leverage and does not recycle same-day proceeds. It fails closed when the required currency data is absent. Actual [commission callbacks](https://www.interactivebrokers.com/docs/tws-api/ref/commission-and-fees-report) are retained per execution; displayed net P&L still uses a labeled conservative allowance.

Paper execution has [broker simulation limitations](https://www.interactivebrokers.com/docs/tws-api/doc/notes-limitations/limitations/paper-trading). Offline tests and paper fills cannot establish live-market profitability or guaranteed stop execution.

## Acceptance status

Implemented: US ORB, live-bar input, long-only paper buy/stop/sell lifecycle, USD allocation, durable identity/recovery, account guards, daily/period estimated P&L, raw data and execution exports, offline demo and failure-path tests.

The user reports paper approval, and Java 26.0.2.1 has passed the offline checks. Pending: supervised socket connection/data/order checks. No IBKR account was connected and no broker order was sent during development. See [validation notes](VALIDATION.md) for executed checks.

After account acceptance: observe daily reconciliation for a month, implement reusable captured-data/IBKR-history replay with proper volume and fee calibration, then assess the strategy on held-out data. Only after that should separate work consider options, shorting, live trading or AI-assisted filtering.
