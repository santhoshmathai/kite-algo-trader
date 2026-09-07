# Validation record - 2026-09-07

Environment: Windows, OpenJDK 17.0.12, sources compiled with `--release 11`. The official bundled Kite SDK checksum is checked by the build. Maven is not installed in this environment; its POM was parsed, but Maven execution was not tested.

## Executed offline

- `./build.ps1 test`: 46 foundation checks and 28 application checks, all passing.
- Packaged-JAR `validate examples/synthetic.properties`: passes, live disabled.
- Packaged-JAR `--demo`: completes the preserved fictional foundation roundtrip.
- Packaged-JAR `backtest examples/synthetic.properties examples/synthetic-bars.csv runs`: produces two observed sessions with confirmed flatness and complete configured morning coverage.
- Whitespace and staged-file review before commit; generated state, SDK checkout and credentials excluded.

Application assertions cover dated charges, configuration validation, calendar exclusions, tick rounding, journal locking/checksums/recovery, snapshot gaps and volume semantics, sizing, partial-entry cancellation and protection, entry-bar stop execution, target conversion, order expiry, stale quotes, time exits, daily losses, restart reconciliation, context mismatch, rejected exits, account exposure, order tampering and shared portfolio limits. Both lost acknowledgements and absent uncertain submissions are tested without blind resubmission. A truncated dataset must remain explicitly incomplete when an exit has not filled.

The two-day synthetic fixture deliberately includes a target observation followed by a lower next-open fill and a losing stop. Its prices, listing and token are invented. It tests execution semantics and charges; its net result is not a measure of strategy quality.

## Not validated

No user credentials were supplied, no real Kite login/data session was opened, and no real order was placed. The live adapter compiles against the pinned official SDK, but account-specific MIS eligibility, static IP, market protection, order modifications, fill timing and reconnect behaviour need supervised account testing. The automated application checks use simulated/scripted brokers, not an exchange sandbox.

No multi-month real-data research, walk-forward study or profitability claim is included. Historical results depend on point-in-time watchlists, fee coverage and fill assumptions. Options, automatic sector ranking, a US market profile, multi-account orchestration and automatic recovery of manually altered exposure remain later work. Power loss or an unavailable broker can prevent liquidation; configured loss limits are not guaranteed maximum losses.

## Next acceptance steps

1. Configure daily Kite access, refresh eligible equities, and verify instrument/calendar/fee inputs.
2. Download real data, preserve the input hashes and timestamped watchlist, and run separate development/holdout evaluations with stressed costs.
3. Observe full paper sessions and verify report quantities, generated bars and exit decisions.
4. Validate the live order lifecycle with the account's supported capabilities and a deliberately small allocation before broader use.

## Paper-month setup extension

The paper reporting extension passes 79 checks (46 foundation, 33 application). Added checks cover the INR 500000 profile and disabled live gate, order exports and reconciled period totals, missing/incomplete sessions, mixed-profile/live report exclusion, and prevention of paper modifications filling against older queued ticks. Packaged --help exposes paper-summary. No credentialed market-data session was run; daily authentication is still required locally.
