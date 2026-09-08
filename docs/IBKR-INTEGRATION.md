# IBKR / US integration: feasibility and implementation plan

Status: 2026-09-08. Same repository recommended. First implementation milestone is complete: shared ORB session policy, explicit US candle timezone, US calendar, optional IBKR SDK build, read-only connection diagnostic, and 14 IBKR checks. Automated orders in the IBKR paper account are NOT implemented yet. The existing India runtime remains available with all 79 regressions passing.

## Repository decision

Keep one repository with broker-specific adapters and market-specific configuration. A second repository would duplicate the strategy, accounting, persistence, testing and future fixes while doing little to remove the actual complexities: currencies, calendars, market data, order states and account restrictions.

Use separate runnable entrypoints and independent process/state directories for India and the US. One JVM per account/market is sufficient initially; they can run at different hours on the same computer. TWS or IB Gateway remains an additional local process for IBKR. No hosted server or distributed services are required for this scope.

The repository was not ready to trade US instruments merely by swapping KiteBroker. Concrete India assumptions found during review:

| Existing component | India assumption | Migration treatment |
| --- | --- | --- |
| Candle | Normalizes every timestamp into Asia/Kolkata | Added an explicit-zone constructor; existing constructor keeps India behaviour |
| TradingSession / ORBStrategy | Static India opening/closing times | Added session-policy overload; ORB now uses the supplied policy, with original defaults |
| TradingEngine | India session dates, cutoffs, Equity, Fees, INR accounting | Extract market/listing/cost policies incrementally before US runtime wiring |
| Equity | NSE identity and numeric Kite token | Future US contract catalog uses qualified IBKR conId, currency, primary exchange and market rules |
| Broker | Useful order boundary, but cash is unlabelled and SL-M is a Kite-style name | Add explicit account/currency capability and broker-specific order normalization |
| KiteBroker | MIS, SDK authentication and synchronous snapshots | Keep isolated; IBKR gets a separate asynchronous adapter |
| Reports / PaperSummary | India dates, INR and fixed daily allocation | Add explicit broker/account/currency/mode metadata and separate US outputs |
| Calendar / fees | India calendar and resident NSE charges | Separate US calendar and versioned account-specific IBKR cost inputs |

Do not create a second independent ORB implementation. Share the tested decision logic and selected account/state components; isolate the external protocols. Separate source/build artifacts in one repository are enough to manage SDK versions.

## Code added in this milestone

- `integrations/ibkr/`: optional Java integration, isolated from the normal India source build.
- `build-ibkr.ps1`: compiles against the official local TWS Java SDK and its bundled protobuf JAR. It does not add either dependency to the India build or publish them.
- `config/ibkr/paper.properties`: separate loopback-only read-only configuration. TWS paper port 7497 or Gateway paper port 4002; client ID 71; bounded timeout; optional explicit expected DU account.
- `config/ibkr/us-equities-2026.csv`: dated regular-session allowlist with US holidays and 13:00 early closes. Missing dates fail closed.
- `IbkrMain validate`, `schedule` and `probe`: configuration checks, zone-aware opening times, and a read-only SDK handshake. There is no IBKR paper-trading or live-order command.
- Shared core overloads retain the legacy India constructor behaviour. `ORBStrategy` can now build a 09:30-09:45 New York range and signal from the 09:45 candle once it closes at 09:46.

The probe only establishes the API connection, receives managed accounts/next-valid-ID callbacks, asks for server time and disconnects. It never calls order placement, cancellation, account-update subscription or position retrieval. Receiving an order-ID callback is part of the normal API handshake; it does not place an order.

The probe does not prove that a connection is paper simply because its port or account prefix looks like paper. It prints whether the configured account matches the broker-reported managed accounts. You must verify the TWS/Gateway login mode, and a future execution adapter will require explicit paper selection and account binding before any orders. Keep TWS API Read-Only enabled during this milestone.

## API choice and environment

You confirmed TWS / IB Gateway socket API. This fits the Java application and avoids a parallel Web API authentication implementation. Authentication remains in TWS/Gateway; the application does not need your IBKR password or 2FA codes. [IBKR architecture and setup](https://www.interactivebrokers.com/docs/tws-api/doc/introduction)

The official documentation currently specifies Java 21+, a current Stable/Latest TWS or Gateway, and a current API. [IBKR requirements](https://www.interactivebrokers.com/docs/tws-api/doc/notes-limitations/requirements)

Local inspection found JDK 17 and a TWS installation under `C:\Jts\1037`; no listener was observed on 7497/4002 during the check. That does not prove which TWS session you may have running on a custom port. The optional connector compiles and its offline SDK tests run with the downloaded stable JAR on JDK 17, but the connection command deliberately requires Java 21 to follow the currently supported runtime. India compilation continues to target Java 11.

The official stable API 10.45 archive was downloaded into ignored `.deps/ibkr/api-1045/IBJts`. It contains `source/JavaClient/TwsApi.jar` and `source/JavaClient/jars/protobuf-java-4.29.5.jar`. Archive URL: https://interactivebrokers.github.io/downloads/twsapi_macunix.1045.01.zip

Archive SHA256: `56EA048911052E86D6621AB712957C790FCE6D547BC2A55900136AE4F6835941`.

Use the official API distribution under its terms; do not commit its source/JARs to the public repository. The SDK is local-only because its download license restricts redistribution. [Official SDK download and terms](https://interactivebrokers.github.io/)

## First connection setup

1. Use a Java 21+ JDK for IBKR. Set `IBKR_JAVA_HOME` to its actual installed directory. Keep your normal India JDK configuration if desired.
2. Use a current compatible TWS or IB Gateway. Log in explicitly to the paper environment. Do not provide the password to this program or in chat.
3. In TWS API settings enable socket clients, keep Read-Only API enabled and restrict access to localhost. Verify the configured paper port; this milestone deliberately accepts only 7497 or 4002. Do not use the default live ports 7496/4001.
4. Copy `config/ibkr/paper.properties` to `config/ibkr/paper.local.properties`. Set port/client ID appropriately. Use a nonzero client ID not used by another application. Set `expectedAccount` to your paper account ID from TWS, or keep UNSET for discovery-only diagnostics. Local properties are Git-ignored.
5. Set `IBKR_API_HOME` to the official distribution's IBJts directory if it is outside the prepared `.deps` location. This build is pinned to the tested API 10.45 layout; review newer SDK upgrades deliberately.
6. Build and check:

```powershell
Set-Location 'C:\Users\Admin\Documents\ChatGPT\KiteAlgoTrader\source-review'
.\build.ps1 test
.\build-ibkr.ps1 test
$IbkrClasspath = Get-Content build/ibkr-classpath.txt -Raw
$IbkrJava = Join-Path $env:IBKR_JAVA_HOME 'bin/java.exe'
& $IbkrJava -cp $IbkrClasspath com.example.trading.ibkr.IbkrMain validate config/ibkr/paper.local.properties
& $IbkrJava -cp $IbkrClasspath com.example.trading.ibkr.IbkrMain schedule config/ibkr/paper.local.properties 2026-09-08
& $IbkrJava -cp $IbkrClasspath com.example.trading.ibkr.IbkrMain probe config/ibkr/paper.local.properties
```

A successful probe prints READ-ONLY SDK HANDSHAKE OK, server time and whether the expected account matched, then exits. It does not subscribe to market data or run ORB trading. A timeout indicates that login, socket settings, port, compatibility or the connection handshake needs investigation. Error 326 means investigate client-ID conflicts. Numeric error codes are retained while raw broker error content is suppressed.

## Planned milestones after the foundation

| Stage | Deliverable | Acceptance criteria |
| --- | --- | --- |
| 1: completed locally | Session boundary, optional SDK diagnostic, calendar, initial tests | All 79 India checks and 14 new checks pass; supported-JDK account handshake still needs local setup |
| 2: US data and local simulation | Qualified contracts, live bid/ask/trades or completed bars, capture/replay, USD costs and state, generalized coordinator | Correct opening bars, stale/delayed data classification, no future information, deterministic US sizing/exits; original India outputs unchanged |
| 3: IBKR-hosted paper execution | Adapter submits actual API requests only to the selected simulated IBKR account | Order IDs, partial fills, commissions, cancellations, protective orders, stop switch and restart reconcile against TWS paper state |
| 4: supervised paper evaluation | Daily reports, period P&L, incident review and fixed experiment profile | No unexplained positions/duplicate orders, all failures visible, matched trade/account totals over observed sessions |
| 5: optional future scope | US live execution, shorts, options, sector filter or AI | Separate explicit authorization and evidence; not part of the current milestone |

Do not claim stage 1 is already an automated IBKR trading application. It establishes the integration boundary and verifies that the original strategy can use US sessions.

### Stage 2 detail

Resolve contracts through `reqContractDetails`, using security type STK, USD currency, SMART routing and an explicit primary exchange where needed. Persist broker `conId` and actual tick-size/market-rule information. A ticker string alone is not a globally unique identifier. Start with whole-share, long-only US stocks; exclude options and leveraged/multi-leg instruments.

Verify actual API market-data entitlements. Having API access enabled does not guarantee real-time market data for each exchange. Record the broker's reported market-data type and exclude delayed/frozen streams from real-time strategy evaluation. Normalize timestamps using America/New_York, verify volume units, preserve completed bars and handle reconnects without inventing opening bars. TWS historical bars and streaming ticks require an explicit reconciliation policy.

Make capital explicitly USD. Do not reinterpret the previous INR 500000 as USD 500000 or silently apply an exchange rate. Choose a separate USD paper allocation and clarify cash versus margin-account behaviour before order integration. The simulator must account for the chosen commission schedule and appropriate fees; the existing NSE fee table is invalid for US stocks. Cash-account sizing needs settled-cash treatment; do not equate aggregate margin buying power with spendable USD cash.

### Stage 3 detail

IBKR order events are asynchronous. Persist next-valid-ID allocation and the originating client ID, `orderRef`, `permId`, execution IDs and commission updates. Deduplicate callbacks and reconcile cumulative fills with executions. Translate broker statuses deliberately: an inactive/held order is not automatically a confirmed rejection. Reconcile open/completed orders, positions and executions before allowing new entries after restart. Do not automatically bind or cancel manual orders.

Prove the protection lifecycle in paper: partial entry fills, rejected protection, stop/target competition, cancellation/fill races and modification failures. Do not assume Kite's stop-to-market modification behaviour transfers directly. Evaluate broker-native attached/bracket exits versus a tested coordinated exit sequence. Never send two independent close orders that can over-exit the position. Session closure must be based on confirmed broker position state.

Separate modes explicitly: IBKR data with the local simulator is different from orders sent to IBKR's own paper account. In the latter, simulated trades appear in TWS paper reports; this is the user's intended eventual test environment. Neither mode sends real orders, but their fill models differ. IBKR documents that its paper environment has execution limitations and differs from live trading. [Paper environment](https://www.interactivebrokers.com/docs/tws-api/doc/notes-limitations/limitations/paper-trading)

## Proposed US schedule and UK convenience

US equities regular hours are 09:30-16:00 New York time, with calendar exceptions. Proposed strategy settings: opening range 09:30-09:45, entries before 10:15, flatten from 10:30; connect around 09:20. These settings are a plan, not yet an active US runtime. [NYSE hours/calendar](https://www.nyse.com/trade/hours-calendars)

On September 8, 2026, the open is 14:30 London time; connecting at 14:20 gives a ten-minute buffer. Most of the year this conversion holds, but the UK/US daylight-saving transition weeks differ: opening is 13:30 London on dates such as March 9 and October 26, 2026. Always convert named timezones instead of hardcoding a UK offset. Tests cover these dates and US early closes.

## How India is protected

The existing command line, India property files, Kite SDK bundle, journal schema and execution path are unchanged. The only shared functional changes add explicit timezone/session policy support while preserving old constructors. The ordinary build has no IBKR dependency. Optional SDK classes live in a separate JAR, and no IBKR command invokes Kite's runtime.

Run the full India suite with every shared-core change and retain synthetic fixtures as regression evidence. Add broker contract tests before sharing more of the coordinator. Eventually generalize reporting and fees behind explicit policies, rather than scattering `if broker == IBKR` checks throughout the code. Keep independent currency-labelled outputs and account locks for each market.

## Validation performed

On 2026-09-08, the original 79 checks passed after shared-core changes. All 14 optional IBKR checks passed against official API 10.45 and bundled protobuf. Checks include US ORB timing, India defaults, daylight-saving shifts, holidays/early closes, configuration separation, account matching, rejected live ports/remote hosts, callback handling and bounded timeouts. The probe was not connected to a user account: supported Java 21 and a logged-in/listening paper TWS/Gateway session are still required. No market-data subscription or order request was sent.
