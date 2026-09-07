# Operating the India application

## Deployment and boundaries

Run the packaged application and `build/lib/kiteconnect.jar` together on a local computer with JDK 11+, reliable power, a synchronized clock and a stable connection. Disable sleep during the session. A paid cloud machine is not required. Use one process, one state root and a dedicated account session without concurrent manual intraday activity; the local lock cannot coordinate another computer or a second state root.

Kite market-data/history access and an eligible broker account are external requirements. Verify your account's current API permissions and registered static IP before live use. This release intentionally does not automate broker login or bypass daily authentication. See [Kite authentication](https://kite.trade/docs/connect/v3/user/) and [Zerodha's API trading requirements](https://kite.trade/forum/discussion/15912/preparing-to-comply-with-sebis-retail-algo-rules-static-ip-ratelimits-order-types).

## Configuration

Copy `config/india.properties` to `config/india.local.properties`. Keep the same directory so its relative paths resolve correctly. Adjust the explicit watchlist and capital/risk limits. Example symbols are configuration examples, not selections based on research. `liveEnabled=false` is the default.

All paths resolve relative to the properties file. `instruments`, `calendar`, `fees`, `state` and `sessionFile` are required for the full workflow. Secrets and generated data/state are Git-ignored. Keep the session file private to your OS account; it contains the access token in plaintext. Do not put credentials in configuration or commit them.

The defaults allocate INR 10,000, INR 25 per trade, INR 100 daily loss, INR 50 total planned open risk, INR 5,000 per instrument, two simultaneous positions and three trades per day. These are software defaults. Position size includes estimated roundtrip fees and slippage, uses no intended leverage, and may be zero. Actual losses can exceed these limits when prices gap, fills slip or exits fail.

## Daily login and market data

Set `KITE_API_KEY` in your local environment, then:

```powershell
java -jar build/kite-algo-trader.jar login-url
```

Open the URL and log in through Zerodha. Obtain the returned request token from your configured redirect, and set `KITE_REQUEST_TOKEN` and `KITE_API_SECRET` locally. Do not paste them into chat. Then:

```powershell
java -jar build/kite-algo-trader.jar login config/india.local.properties
Remove-Item Env:KITE_API_SECRET, Env:KITE_REQUEST_TOKEN
java -jar build/kite-algo-trader.jar instruments config/india.local.properties
java -jar build/kite-algo-trader.jar validate config/india.local.properties
```

Keep `KITE_API_KEY` available while running. The session command saves the account identity and token; it does not print the token. Instruments are restricted by the downloader to configured NSE EQ listings with lot size one. Runtime requires today's instrument metadata. The SDK uses the JVM timezone for history formatting, so the CLI sets Asia/Kolkata regardless of the computer's local zone.

## Historical research

```powershell
java -jar build/kite-algo-trader.jar download config/india.local.properties 2026-09-07 2026-09-07 data/bars-2026-09-07.csv
java -jar build/kite-algo-trader.jar backtest config/india.local.properties data/bars-2026-09-07.csv runs
```

Use a completed historical session. The downloader refuses to overwrite an existing output file and batches requests through the official SDK. To research older periods, supply verified dated fee schedules and calendars covering those dates first. The included fee snapshot deliberately does not apply to dates before 2026-09-07.

The input CSV has `timestamp,instrument,open,high,low,close,volume`, with offset-bearing minute-start timestamps, stable identities such as `NSE:RELIANCE`, and per-minute volume. Sort by timestamp then instrument. Duplicate, unknown-instrument, malformed and out-of-session rows fail validation. A session needs consecutive bars for every selected instrument from 09:15 through at least the flatten minute. Inspect `data_complete` and `flat` in `days.csv`; an entirely missing day must be detected against the intended date range separately.

Replay uses completed bars for signals and subsequent opens for entry fills. Stop processing occurs within the entry bar after entry acknowledgements. Targets are observed at minute closes and filled at a later open, so a target signal does not guarantee a profitable exit. Participation is a bar-level approximation using that bar's eventual volume; it is not a historical order-book reconstruction. Stop-before-target handling is conservative when a bar touches both. Slippage and fees are estimates; exchange price protection, actual queue position, network delay, auctions and contract-note rounding are not reproduced. Cash allocation resets each session rather than compounding silently.

Maintain a timestamped watchlist, point-in-time instrument metadata and untouched input files. A current watchlist applied to old data introduces survivorship/selection bias. Current broker tokens alone do not establish historical listing identity. Evaluate development and untouched holdout periods separately, across trends and sideways markets, then stress costs, gaps, partial fills and slippage. Backtesting can reveal errors and estimate historical behaviour; it cannot prove future profit.

## Live-data paper session

Start before 09:15 India time with a fresh daily session:

```powershell
java -jar build/kite-algo-trader.jar paper config/india.local.properties
```

Paper mode receives real Kite data but its broker cannot submit real orders. It writes to `runs/paper/<account>/<date>/`. Paper fills are approximate, and the simulated broker has no restart reconstruction: an existing paper session is preserved and cannot be silently reused. Use another explicitly configured paper state root for a new simulation.

The process builds the opening range from observed snapshots. Late startup prevents new entries. Gaps, disconnects, reconnects after open and queue overflow halt entries and request flattening. Stale position marks trigger an exit. Snapshot candles can differ from exchange historical candles because intermediate trades are not all transmitted.

## Armed live session

Only after reviewing real historical results, observing paper behaviour and validating the account's order capabilities, set `liveEnabled=true` in the local configuration and start before 09:15:

```powershell
java -jar build/kite-algo-trader.jar live config/india.local.properties --arm-live
```

Both settings are required. No command in the automated tests enables live mode. The app submits NSE MIS DAY orders; MARKET and SL-M requests use Kite's automatic `market_protection=-1`. Protective stops are submitted after the first confirmed fill, leaving a nonzero interval of exposure. Target and time exits modify the same protective order into MARKET to avoid racing an independent close order. Market protection or broker restrictions may leave an exit unfilled. See [official order semantics](https://kite.trade/docs/connect/v3/orders/).

Unfilled entries expire after 15 seconds by default. Partial entry fills cause a cancellation request for the remainder and a stop for the confirmed filled quantity; further confirmed fills resize protection. New entries stop before 10:00, and liquidation begins at 10:15. Keep running until all orders are terminal and the broker position snapshot agrees that the account is flat. The process cannot guarantee closure during a network or broker outage.

## Status, stop and recovery

```powershell
java -jar build/kite-algo-trader.jar status runs/live/ACCOUNT/2026-09-07
java -jar build/kite-algo-trader.jar stop runs/live/ACCOUNT
```

Replace the paths with the exact directory printed by runtime. Reports refresh about every 30 seconds. `STOP` is persistent: remove it manually only after checking the account and before deliberately enabling another session. Ctrl+C requests shutdown, but its wait is bounded; verify broker positions directly if it cannot confirm flatness. Do not terminate the process simply because the first trading hour ended.

Every order intent is forced to disk before submission. Acknowledgements are reconciled from broker snapshots; they are not treated as fills. An uncertain submit is never blindly repeated. If confirmation never appears, the application stays halted for operator reconciliation. A rejected exit disables automatic retries and requires you to inspect/close in Kite. Unexpected or unmanaged orders, position mismatches, duplicate tags and changed terminal states also halt automation.

Restart live mode with the original state root and identical configuration, instrument file, fee file and calendar. Recovery restores order identifiers and confirmed quantities, disables new entries, then reconciles and requests liquidation. Preserve these files throughout the session. A changed context or corrupt complete journal record refuses startup. An incomplete trailing record is truncated to the last checksummed snapshot; persisted intents still require broker reconciliation. Do not delete journals to bypass a fault. If manual intervention changes exposure, manage the account in Kite and retain the journal for review; there is no automatic manual-position adoption feature.

## Maintained reference data

Calendar: regular 2026 weekdays with published trading holidays excluded, including the additional January 15 closure. Exceptional openings and Muhurat trading are not enabled. Refresh against [NSE's 2026 capital-market circular](https://nsearchives.nseindia.com/content/circulars/CMTR71775.pdf) and [Zerodha's updated trading calendar](https://zerodha.com/marketintel/holiday-calendar/). Settlement-only holidays must not be treated as equity trading closures. Missing dates fail closed.

Fees: resident standard NSE intraday rates observed on 2026-09-07 from [Zerodha charges](https://zerodha.com/charges/), configured through year end as an assumption requiring maintenance. Includes brokerage cap, sell STT, exchange/SEBI/IPFT, buy stamp duty and GST. NRI, debit-balance and other account-specific schedules differ. Historical effective dates are not inferred. API subscription, computer/internet, extraordinary broker square-off charges and account-level fees are outside strategy P&L.
