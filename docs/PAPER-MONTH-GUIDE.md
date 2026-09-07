# One-month paper test with INR 5 lakh

Prepared 2026-09-07. Intended first session: Tuesday 2026-09-08. This guide uses the current local code, including `paper-summary` and order exports added with this setup. Rebuild before using those commands. No paper market-data session has been started by preparing this guide.

## What is automated?

Yes: completed-bar breakout detection, eligibility checks, quantity sizing, simulated entry submission, partial-fill handling, stop/target management and time exits are automated. The first month uses `paper`, which creates `SimBroker` and constructs `KiteBroker` with execution disabled. The Kite connection supplies data/authentication only. Your virtual capital does not need to exist as cash in the real brokerage account, and virtual orders will not appear in Kite's order book or contract notes.

The program does not currently choose sectors, rotate a daily watchlist, trade options, train an AI model or compound the paper balance across days. It uses the explicit symbols in configuration. Shorts are disabled for this experiment. It can make one attempted entry per symbol per day, not repeatedly buy/sell/re-enter the same symbol after every profit.

## Paper profile and experimental rules

Use `config/paper-500k.properties` with these settings:

| Setting | Value | Meaning |
| --- | --- | --- |
| capital | 500000 | Fixed virtual allocation each session |
| tradeRisk | 500 | Planned loss budget per entry including estimated costs; 0.1% of allocation |
| dailyLoss | 2500 | Marked daily loss threshold that halts entries and requests exit; 0.5% |
| maxOpenRisk | 1000 | Combined planned risk reserved for pending/open entries |
| maxNotional | 100000 | Maximum intended position value per symbol |
| maxPositions / maxTrades | 2 / 3 | Concurrent positions / daily entry attempts |
| reward | 1.5 | Target distance relative to actual entry-to-stop distance |
| slippageBps | 5 | Simulated adverse fill adjustment; 5 basis points = 0.05% |
| maxParticipation | 0.01 | Fill/sizing volume approximation, not a real liquidity guarantee |
| shorts / liveEnabled | false / false | Long-only simulated experiment |

These are controlled experiment parameters, not a return forecast. The program may use much less than INR 5 lakh: with two INR 1 lakh positions, intended exposure is at most about INR 2 lakh, and risk/liquidity limits may reduce it further. Do not increase risk merely to force full allocation or more trades.

Keep this profile unchanged during the baseline month. The existing three symbols are inherited setup examples, not a researched recommendation. A fixed small watchlist is sufficient to learn the machinery. If you choose a different watchlist, change it before the first session and retain that choice. A later parameter change is a new experiment: copy the configuration to a new filename, select a new state root, and preserve the old configuration for its summaries. Daily instrument refresh is expected; overwriting a study's strategy/risk settings is not.

This profile writes under `runs/paper-month-500k/paper/ACCOUNT/YYYY-MM-DD/`. The initial allocation resets to 500000 each day. Period P&L is the sum of included completed sessions. `500000 + cumulative P&L` is a reference balance, not tomorrow's automatically compounded sizing capital. A rolling cash account is an explicit future enhancement.

## Today: build and rehearse offline

Open PowerShell in the actual repository, not its parent workspace:

```powershell
Set-Location 'C:\Users\Admin\Documents\ChatGPT\KiteAlgoTrader\source-review'
java -version
.\build.ps1 test
java -jar build/kite-algo-trader.jar --help
java -jar build/kite-algo-trader.jar backtest examples/synthetic.properties examples/synthetic-bars.csv runs
```

The synthetic profile uses fictional data and a different allocation. It is only a smoke test; exclude it from the INR 5 lakh paper results. Open the printed run's `summary.txt`, then a day's `report.html`, `trades.csv` and `orders.csv`. The new build includes 79 checks: 46 foundation and 33 application checks.

Your API app and market-data entitlement are already ready. The needed remaining step is a fresh daily login on the machine that runs Java. Kite's current Connect tier includes live WebSocket/history data at INR 500/month; this is a real service cost even when orders are simulated. Daily access tokens expire at 06:00 the following day and can be invalidated earlier. Sources: [API offering](https://zerodha.com/products/api/), [authentication](https://kite.trade/docs/connect/v3/user/).

## Every trading morning: login and start

All schedule times below are Asia/Kolkata. Start setup at 08:55 and have the process connected by 09:05-09:10, strictly before 09:15. In London during September 2026, 09:05 IST is 04:35 BST. India is 4.5 hours ahead of London during BST and 5.5 during GMT; use the India schedule after the UK clock change.

1. Keep the computer awake, plugged in and connected. Check the clock and the trading calendar. Use one process for this experiment. Open the same PowerShell window used for the remaining commands.

2. Enter your public API key locally and obtain the login URL:

```powershell
$env:KITE_API_KEY = Read-Host 'Kite API key'
java -jar build/kite-algo-trader.jar login-url
```

3. Open the printed URL, finish Zerodha's interactive login and copy only the `request_token` value from your registered redirect URL. Exchange it promptly; it is short-lived. Enter the API secret and request token in masked local prompts:

```powershell
$secretInput = Read-Host 'Kite API secret' -AsSecureString
$env:KITE_API_SECRET = [System.Net.NetworkCredential]::new('', $secretInput).Password
$secretInput.Dispose()
$secretInput = Read-Host 'Fresh request token' -AsSecureString
$env:KITE_REQUEST_TOKEN = [System.Net.NetworkCredential]::new('', $secretInput).Password
$secretInput.Dispose()
Remove-Variable secretInput
java -jar build/kite-algo-trader.jar login config/paper-500k.properties
```

4. Only if login reports success, clear the temporary secrets and download today's mappings:

```powershell
Remove-Item Env:KITE_API_SECRET, Env:KITE_REQUEST_TOKEN
java -jar build/kite-algo-trader.jar instruments config/paper-500k.properties
java -jar build/kite-algo-trader.jar validate config/paper-500k.properties
```

Stop and investigate if any command fails; do not continue past a failed login or instrument download. The session file under `secrets/` is private local plaintext, Git-ignored. Never share it or paste tokens into chat. Keep `KITE_API_KEY` set in the process environment. A newly opened terminal does not automatically inherit variables set in another terminal.

5. If a prior session was deliberately stopped, check its final reports and ensure its process is gone. A persistent `STOP` file prevents tomorrow's entries. Only after that check, remove that exact paper account's STOP file:

```powershell
$Account = 'YOUR_KITE_USER_ID'
$PaperAccount = "runs/paper-month-500k/paper/$Account"
if (Test-Path "$PaperAccount/STOP") { Remove-Item -LiteralPath "$PaperAccount/STOP" }
```

Replace `YOUR_KITE_USER_ID` with the account ID shown by successful login. It is not the API key.

6. Start the session:

```powershell
java -jar build/kite-algo-trader.jar paper config/paper-500k.properties
```

The console must say `PAPER`. Leave this terminal open. There are no real buy/sell requests in this path. Do not change the command to `live`, do not enable the live flag, and do not add `--arm-live` during the first month.

## What happens during the hour?

| India time | Behaviour | What to observe |
| --- | --- | --- |
| 09:05-09:14 | Process waits for regular-session observations | PAPER mode, output path, healthy connection; no entries expected |
| 09:15-09:30 | Builds 15 completed one-minute opening bars | No ORB entry during range construction |
| About 09:31 onward | Earliest eligible completed breakout bar | A signal can be skipped by spread, stop-width, freshness, capital or liquidity checks |
| Before 10:00 | New entry attempts allowed | Submitted quantity and filled quantity can differ |
| 10:00 onward | No new entries | Existing positions still managed |
| 10:15 onward | Requests closing remaining positions | Keep running until actual simulated fills complete and state is FINISHED / flat=true |

A no-trade day can be legitimate. The strategy requires a completed close above the opening high plus its buffer, not merely a brief intraminute touch. With shorts disabled, downside breakouts are ignored. A skipped or unfilled attempt is not retried for that symbol that day. There is no promised number of daily trades.

## Stop the application correctly

In a second PowerShell window, set the repository directory and the same account ID, then:

```powershell
Set-Location 'C:\Users\Admin\Documents\ChatGPT\KiteAlgoTrader\source-review'
$Account = 'YOUR_KITE_USER_ID'
$PaperAccount = "runs/paper-month-500k/paper/$Account"
java -jar build/kite-algo-trader.jar stop $PaperAccount
```

This writes a STOP request; it is not an immediate guarantee of flatness. The active process stops new entries, cancels remaining entry orders and submits simulated exits for filled positions. With a feed outage, exits may not receive a new price/volume observation and may stay unfilled. Do not fabricate a fill to make the report look complete.

Ctrl+C is the fallback, with a bounded shutdown wait. If the process stops before `FINISHED` and `flat=true`, record the day as incomplete. There are no real positions to close for this paper run. Preserve the journal and reports rather than deleting them to hide an error.

Paper state cannot currently resume intraday after a process restart. An existing same-day journal refuses another paper start. For a first-month baseline, retain the failed day and start fresh on the next permitted session. A separate state root is suitable for a clearly labelled diagnostic run, but never combine two copies of one date as independent daily returns.

## See daily P&L and transactions

The process prints its exact output directory. In your second terminal:

```powershell
$Day = [TimeZoneInfo]::ConvertTimeBySystemTimeZoneId([DateTime]::UtcNow, 'India Standard Time').ToString('yyyy-MM-dd')
$DayDir = "$PaperAccount/$Day"
java -jar build/kite-algo-trader.jar status $DayDir
Start-Process -FilePath (Join-Path $PWD "$DayDir/report.html")
Import-Csv "$DayDir/trades.csv" | Format-Table -AutoSize
Import-Csv "$DayDir/orders.csv" | Format-Table -AutoSize
Get-Content "$DayDir/decisions.log" -Tail 30
```

Reports refresh about every 30 seconds. Refresh the browser manually; this is a local HTML report, not a streaming dashboard. `reportWrittenAt` in status reveals an old report. Large CSV tables may be easier to open in an editor.

| File | Meaning |
| --- | --- |
| report.html | Human-readable current daily state and net marked P&L |
| status.txt | State, flatness, marked net P&L and report timestamp |
| trades.csv | Per-symbol trade totals, filled entry quantity, remaining shares, gross/cost/net and exit reason |
| orders.csv | One row per order: entry/exit, side, type, requested and cumulative filled quantity, average fill, status and pending action |
| decisions.log | Recent intents, modifications, cancellations, skips and operational reasons |
| session.properties | Non-secret mode/date/capital/configuration metadata used to validate summaries |
| events.journal | Binary checksummed snapshots for technical investigation; do not edit |

`orders.csv` is the latest cumulative order state, not an exchange fill-by-fill tape. Modification history is in the journal/decision log; exact per-fill timestamps are not currently exported. `trades.csv` is an aggregate roundtrip, not a list of broker contract notes. Pending actions can briefly appear between request and confirmation. `CANCELLED` can still have a positive filled quantity; cancellation affects only the unfilled remainder.

Net marked P&L = realized gross P&L + open marked P&L - estimated charges already incurred. While open, it is not final and does not yet include all future exit costs. Use only finished flat sessions for realized daily accounting. Slippage is reflected in simulated fill prices. Paper fee amounts are estimates, not money debited from your account.

## Weekly and monthly profit/loss

For the first partial week, after Friday's session:

```powershell
java -jar build/kite-algo-trader.jar paper-summary config/paper-500k.properties $Account 2026-09-08 2026-09-11
```

Open the printed directory's `summary.txt` and `days.csv`. For the proposed month, after October 7:

```powershell
java -jar build/kite-algo-trader.jar paper-summary config/paper-500k.properties $Account 2026-09-08 2026-10-07
```

The command sums completed, confirmed-flat PAPER sessions with matching capital and configuration, cross-checks the trade totals, reports return on the fixed allocation and preserves missing/incomplete/invalid dates. Completed early-stop or operational-fault sessions remain visible with warnings so failures are not hidden. A warning-labelled day can have valid accounting while being an invalid full strategy observation. Review those days before drawing performance conclusions. Old reports without the new metadata are excluded, not guessed.

A future day is not a zero-return day. `NOT_IN_CALENDAR` means the allowlist does not permit that date; verify it is a holiday/weekend rather than absent reference data. For this period the calendar excludes September 14 and October 2. Do not trade September 14 simply because it is a Monday. Confirm updates against the [published trading calendar](https://zerodha.com/marketintel/holiday-calendar/).

Hypothetical arithmetic only: net daily results of +600, -400, +900, -500 and +200 total +800, or +0.16% on 500000. The reference balance is 500800. This is not a forecast or an observed result. We cannot estimate your weekly profit or loss before collecting sessions. Trading results exclude fixed API, electricity, internet and computer costs; account for those separately when judging economic profitability.

## This week's learning and execution plan

| Date | Run task | Trading-domain lesson | Technical exercise and evidence |
| --- | --- | --- | --- |
| Mon Sep 7, today | Build, synthetic replay, prepare credentials workflow | OHLCV candles, intraday trading, opening range, long versus short | Trace `TradingSystemMain -> Backtest -> TradingEngine -> ORBStrategy -> SimBroker`; inspect one entry and exit |
| Tue Sep 8 | First supervised paper session, connected by 09:05 IST | Signal versus order versus fill; limit orders, spreads and partial fills | Preserve console start/end, profile, reports; explain one trade or why there were none |
| Wed Sep 9 | Same settings, second paper session | Risk amount versus position value; stop distance and quantity; 1R | Recalculate one quantity bound and gross/net P&L; compare intended/filled shares and average price |
| Thu Sep 10 | Same settings, third session; run stop test only after normal session in offline fixtures | Stops, targets, gaps, false breakouts; why a stop does not guarantee a price | Trace stop/target modification, cancellation and journal intent/confirmation; inspect failure-path tests |
| Fri Sep 11 | Fourth paper session, then first period summary | Win rate, average win/loss, expectancy, drawdown and sample size | Reconcile period net with included daily reports; classify missing/failed sessions, open issues and next week's fixed baseline |
| Weekend Sep 12-13 | No regular-session run; review and backup | Why four days cannot establish an edge; selection bias and overfitting | Read AI roadmap, prioritize instrumentation, preserve anonymized reports/config and record next steps |

Allow 20 minutes before opening, the supervised trading hour, and 30-45 minutes after closure for study. Monday September 14 is excluded in the current calendar; resume on the next permitted date. Weeks 2-4 retain the baseline and focus on operational reliability and growing an honest sample. Do not tune after every losing day. The first month ends with an assessment, not automatic permission to enable real orders.

A useful risk example: intended entry 1000, stop 995 and risk budget 500 gives a raw bound of 100 shares before fees. The INR 100000 notional cap also bounds quantity to about 100. Estimated fees, slippage, available allocation, open risk and participation can reduce the final number. Target at 1.5R is approximately 1007.50 if the actual average entry is 1000. The actual code uses the filled average and tick rounding. Gross profit is quantity times exit-minus-entry; costs determine net profit.

Expectancy = win probability times average win minus loss probability times average loss, using net results consistently. At exactly +1.5R/-1R and no costs, breakeven win rate is 40%; real variable fills and costs alter it. Drawdown is the decline from a previous equity high, not simply the largest losing trade. Four sessions and at most twelve attempts are an operational learning sample, not evidence of stable expectancy.

## Troubleshooting checklist

| Symptom | Likely explanation | Action |
| --- | --- | --- |
| No API key / login failure | Variable absent in this terminal, token expired, incorrect API app/redirect | Repeat local daily login; never share tokens |
| Instrument validation failure | No file, wrong symbols, stale metadata | Run today's instruments command using the paper profile |
| No entries after late start | Process did not observe the full opening range | Use offline replay today; start before open tomorrow |
| Zero trades despite healthy process | No qualifying close, short signal disabled, filters, caps or unfilled limit | Read decisions/orders; compare chart closes, not only wicks; do not force entries |
| Stale quote / feed fault | Clock, internet, delayed feed, missing bars, reconnect or overflow | Preserve logs; check local connectivity/time; mark interrupted session honestly |
| Session already exists | Paper does not implement intraday restart | Preserve the day; do not delete its journal; continue next session |
| Immediate flatten next morning | Persistent STOP file | Confirm previous process stopped, then remove that exact paper STOP file |
| Paper exit remains open | No fresh simulated liquidity or price update | Keep process running if feed can recover; otherwise record incomplete result |
| P&L differs from simple chart arithmetic | Actual average fills, partial quantity, slippage and estimated costs | Reconcile orders.csv with trades.csv; do not use requested quantity as filled quantity |
| Summary says INVALID | Different profile/capital, old metadata, edited/truncated reports or inconsistent totals | Use the original experiment config; preserve originals and investigate |
| Summary has no results | No completed paper sessions yet | It means unavailable, not zero expected profit |
| Generic command failure | CLI deliberately suppresses raw SDK errors that could contain secrets | Check prerequisites, command/paths, exit code, console reason and last report; richer sanitized diagnostics are a planned improvement |

For troubleshooting with me, provide mode, India date/time, exact command with no secrets, exception class, last non-secret console messages, `status.txt`, and relevant rows from `orders.csv`, `trades.csv` and decisions.log. Redact account IDs if desired. Never send the session file, API secret, request token or access token.
