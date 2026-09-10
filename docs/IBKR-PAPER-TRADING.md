# IBKR US paper trading

The `IbkrMain paper` command submits automated orders to the selected **IBKR simulated account** through TWS/IB Gateway. The separate `demo` command uses artificial prices locally and never opens a socket. Neither command enables real-money IBKR trading.

The implementation has passed offline tests. The user has confirmed paper-account approval; account connectivity, entitlements and actual IBKR paper fills remain unverified. Use the first connected session under supervision. The original Kite commands and India configuration remain separate.

## Windows launcher

`run-ibkr.ps1` selects an installed Java 21+ and uses the generated classpath. From the repository root:

```powershell
.\run-ibkr.ps1 demo
.\run-ibkr.ps1 probe
.\run-ibkr.ps1 schedule -SessionDate 2026-09-11
.\run-ibkr.ps1 paper -ArmPaper
.\run-ibkr.ps1 status
.\run-ibkr.ps1 stop
.\run-ibkr.ps1 summary -From 2026-09-14 -To 2026-09-18
```

The defaults use `config/ibkr/paper.local.properties` and `config/ibkr/us-paper.properties`. Override with `-Connection` and `-Profile` if needed. The first probe can use `expectedAccount=UNSET`; paper execution requires your exact DU account. Follow the prerequisites below before arming orders.
## 1. Run an offline exercise today

From the repository root:

```powershell
Set-Location 'C:\Users\Admin\Documents\ChatGPT\KiteAlgoTrader\source-review'
.\build.ps1 test
.\build-ibkr.ps1 test
$IbkrClasspath = Get-Content build/ibkr-classpath.txt -Raw
java -cp $IbkrClasspath com.example.trading.ibkr.IbkrMain demo config/ibkr/us-paper.properties runs/ibkr-demo-01
Get-Content runs/ibkr-demo-01/status.txt
Import-Csv runs/ibkr-demo-01/orders.csv | Format-Table
Import-Csv runs/ibkr-demo-01/trades.csv | Format-Table
```

Choose a new output directory for each demo. This exercises an opening-range signal, limit buy, fill, protective stop, cancellation confirmation, target market sell and confirmed flatness. Its invented profitable prices are **not a backtest or profitability evidence**. Offline checks work on JDK 17; socket connections require Java 21+.

The optional build needs the official API 10.45 SDK and its bundled protobuf JAR. See [integration setup](IBKR-INTEGRATION.md). It does not download or redistribute that SDK.

## 2. Prepare the account after approval

1. Install a supported JDK 21+ and set `IBKR_JAVA_HOME` to its installation directory. On this computer, JDK 26.0.2.1 is installed and all offline checks pass on it. The launcher automatically detects it.
2. Log into **Paper Trading** in TWS or IB Gateway. Visually confirm the simulated environment and the `DU...` account. Never put your login password in this repository.
3. Use an otherwise idle paper account: this runtime requires no unmanaged positions or active orders. Do not trade manually or run another strategy in the same account during the session.
4. Enable socket clients, restrict connections to localhost, and verify the paper port: TWS normally uses 7497 and Gateway 4002. The program accepts only these two ports. Keep Read-Only API enabled for the initial probe.
5. Copy `config/ibkr/paper.properties` to `config/ibkr/paper.local.properties`. Set `expectedAccount` to the exact paper account ID and a unique nonzero `clientId`. Keep that ID unchanged for recovery. Local properties are Git-ignored.
6. Ensure the paper account has USD cash and supplies `SettledCash` in USD. This first connector requires a **USD-base paper account**; it does not convert GBP or reinterpret GBP balances as USD. If USD settled cash is unavailable, it halts entries. Resolve this in account setup before the first session.
7. Verify real-time API market-data entitlements for AAPL/MSFT. API access alone does not establish a market-data subscription. Frozen or delayed streams cannot authorize an entry.

```powershell
$env:IBKR_JAVA_HOME = 'C:\Program Files\Java\jdk-21' # Replace with the actual installed path
$IbkrJava = Join-Path $env:IBKR_JAVA_HOME 'bin/java.exe'
& $IbkrJava -version
$IbkrClasspath = Get-Content build/ibkr-classpath.txt -Raw
& $IbkrJava -cp $IbkrClasspath com.example.trading.ibkr.IbkrMain validate config/ibkr/paper.local.properties
& $IbkrJava -cp $IbkrClasspath com.example.trading.ibkr.IbkrMain probe config/ibkr/paper.local.properties
```

A successful probe must show the expected paper account matched. It is read-only and does not validate order execution or market-data entitlements. Before running `paper`, disable Read-Only API **in the verified paper session**, because that command must submit/cancel paper orders. The explicit `--arm-paper` argument and DU account check are also required. There is no IBKR `live` command.

## 3. Understand the initial experiment

All amounts in `config/ibkr/us-paper.properties` are **USD**:

| Setting | Default |
| --- | --- |
| Strategy capital allocation | $10,000 |
| Estimated risk per trade | $25 |
| Daily marked-loss exit threshold | $100 |
| Notional per position | $2,500 |
| Aggregate reserved risk | $50 |
| Maximum concurrent positions / trades per day | 2 / 3 |
| Target | 1.5 times filled-entry-to-stop distance |
| Universe | AAPL and MSFT; explicit primary exchanges in `us-stocks.csv` |

The allocation limits the application; it does **not** set or reset your IBKR paper balance. It is separate from the INR 500000 India experiment. Whole-share long entries only; no short selling, options, leverage allocation, currency conversion or AI decisions.

Copy the profile to `us-paper.local.properties` if changing amounts. Keep it beside the original so relative paths still resolve. Use the same profile for all commands and recovery; changing configuration with active journals is rejected. Prices below $1 are excluded and contract qualification must support penny increments. This initial universe is a configuration example, not a recommendation to invest in those stocks.

Sizing considers configured risk, open exposure, USD cash, initial settled cash, spread, stop distance and bar participation. Today's sale proceeds are not recycled into the initial settled-cash allowance. Fees use a conservative configurable-in-code allowance of max($1, $0.01/share) per filled order, **not the account's actual commission schedule**. The one-share allowance used for sizing is intentionally conservative and can produce very small quantities. Actual commission callbacks are exported separately.

## 4. Daily start and timing

Run about ten minutes before the US opening and keep TWS/Gateway and the Java process running until confirmed flat.

| New York time | Action |
| --- | --- |
| Before 09:30 | Connect, qualify contracts, subscribe and reconcile the account |
| 09:30–09:45 | Build the opening range from all 15 complete minute candles |
| 09:46 onward | First possible signal from a completed post-range minute |
| Before 10:15 | Allow new limit buys, subject to risk/data checks |
| 10:30 | Cancel outstanding entries and begin flattening positions |
| After final acknowledgements | Exit only when all tracked orders are terminal and broker positions are flat |

The usual London opening is 14:30, so start around 14:20 and expect flattening from 15:30. During US/UK daylight-saving transition mismatches, the opening is 13:30 London. Check the actual date using the calendar command; unknown dates/years fail closed.

```powershell
& $IbkrJava -cp $IbkrClasspath com.example.trading.ibkr.IbkrMain schedule config/ibkr/paper.local.properties 2026-09-10
& $IbkrJava -cp $IbkrClasspath com.example.trading.ibkr.IbkrMain paper config/ibkr/paper.local.properties config/ibkr/us-paper.properties --arm-paper
```

A fresh session started after 09:30 is rejected. An existing session can restart during market hours only to reconcile and flatten; it will not resume new entries. A missing opening bar means no valid opening range for that stock. A day with no trades can be the correct result.

## 5. Stop and inspect results

In another PowerShell window, initialize `$IbkrJava` and `$IbkrClasspath` as above, then:

```powershell
& $IbkrJava -cp $IbkrClasspath com.example.trading.ibkr.IbkrMain status config/ibkr/paper.local.properties config/ibkr/us-paper.properties
& $IbkrJava -cp $IbkrClasspath com.example.trading.ibkr.IbkrMain stop config/ibkr/paper.local.properties config/ibkr/us-paper.properties
```

`stop` writes a persistent STOP file and returns immediately. It does not itself cancel orders if the runtime is absent. The running process observes it and attempts to cancel entries and close positions. Wait for `confirmedFlat=true`, then verify positions and outstanding orders in TWS. Ctrl+C requests the same shutdown with a bounded grace period; force-killing Java or losing connectivity can leave orders/positions at IBKR.

After a disconnect there is no automatic reconnect or blind retry. Inspect TWS. Restart with the **same connection/profile/client ID/journals** to recover during market hours. If an exit is rejected, orders are uncertain, or broker positions disagree, follow the reported reason and reconcile in TWS. Do not delete journals to bypass an error. An accepted stop can remain at the broker while the application is offline; automatic target/time exits require a working application and connection. All generated orders are DAY/regular-hours orders.

Reports are under `runs/ibkr-paper/<DU-account>/<YYYY-MM-DD>/`:

| File | Meaning |
| --- | --- |
| `status.txt`, `session.properties` | Updated time, status, marked estimated net USD, remaining shares and confirmed-flat flag |
| `trades.csv` | Filled entry, remaining quantity, gross P&L, estimated costs/net and exit reason |
| `orders.csv` | Entry/protective/replacement orders, broker IDs, tags, quantities, cumulative fills and pending actions |
| `executions.csv` | Deduplicated execution callbacks and actual commission/currency when received; blank commission is unknown, not zero |
| `decisions.log` | Entry decisions, requests and halt reasons |
| `qualified-contracts.csv` | Broker conIds and selected price increment |
| `five-second-bars.csv` | Captured raw bars for troubleshooting; raw volume is not multiplied |
| `engine.journal`, `broker.journal` | Durable recovery state; do not edit |

Reports refresh approximately every five seconds while the loop is healthy. Always check the update timestamp. A stale report is not a current valuation. The application protects filled quantities with stop requests; there is a delay between entry fill and stop acknowledgement, and a gap while replacing a cancelled stop with a market exit. Configured loss amounts are not guaranteed execution prices.

The STOP file is at the account root. After verifying the previous session is flat, remove that exact file before the next new session:

```powershell
# Replace the example account with your own; do not remove journals.
Remove-Item -LiteralPath 'runs/ibkr-paper/DU123456/STOP'
```

Weekly estimated results, restricted to this account/profile:

```powershell
& $IbkrJava -cp $IbkrClasspath com.example.trading.ibkr.IbkrMain summary config/ibkr/paper.local.properties config/ibkr/us-paper.properties 2026-09-14 2026-09-18
```

The summary includes recorded IBKR paper sessions and labels unconfirmed positions. Missing days are not assumed zero. It excludes offline demos and rejects mixed risk profiles. Broker statements remain authoritative for actual realized profit, fees and cash; the weekly total is the application's **estimated** P&L.

## 6. First connected-session acceptance

Keep TWS alongside the application. Check that each entry tag maps to one broker order, partial fills match positions, protection covers the filled quantity, and any target/time close occurs only after stop cancellation confirmation. Compare `executions.csv` and final quantities with TWS. Test the STOP command and recovery with a small paper allocation. Capture numeric API errors and the local reports if any mismatch occurs.

Market data, socket connectivity and account-specific paper execution are the remaining integration checks. Passing an artificial demo does not prove that the strategy is profitable. A month of observation and a separate historical, cost-aware out-of-sample backtest are subsequent evaluation work.
