# AI and application enhancement roadmap

This is a proposed sequence, not implemented AI functionality. Keep the first month's production experiment as a fixed rule-based ORB baseline. A useful AI feature should improve a measured outcome beyond simpler rules after costs, with reproducible evidence.

## Stage 1: make every decision inspectable

Before any predictive model, persist normalized quotes/bars, opening high/low, completed-bar availability time, spread, each sizing constraint, rejection reason and order/fill timestamps. Store the daily watchlist and a versioned run manifest with source revision, configuration and data hashes. Add a session-health report with quote ages, reconnects and missing bars. Extend the current cumulative order export into a fill-event ledger.

Add paper restart recovery, a persistent multi-day virtual cash ledger, clearer sanitized startup diagnostics and a local dashboard. Keep fixed daily allocation as an explicit research mode alongside future compounding. These changes improve both technical investigation and domain understanding. Current session summaries are accounting reports, not model-training datasets.

Acceptance: a single trade can be reconstructed from the input observation through each risk check to every fill, including what was knowable at the decision time. Interrupted and no-trade sessions stay visible.

## Stage 2: sector selection with transparent rules

Test the original sector idea using an explicitly defined decision time. For example, rank sectors at 09:30 using only observations through 09:30: return from open relative to the broad market, breadth of advancing members, completed opening volume relative to historical same-time volume, and spread/liquidity. Choose a bounded number of sectors and eligible stocks, and freeze the list for that session. This is a proposed rule filter, not an existing capability or a promise of improvement.

Avoid ranking sectors by their eventual end-of-day return and then claiming a profitable morning strategy. If membership and selected symbols are determined at 09:30, the data layer must have collected their opening bars already, or have an explicitly delayed and validated warm-up mechanism. The current runtime only subscribes to its configured symbols, so adding selection requires changing data collection; merely changing the list after 09:30 would lose the opening range. A wider reference universe also needs a separate subscription/data budget while keeping the actual traded universe small.

Acceptance: compare ORB alone against ORB plus sector filter over the same dates, costs and capital. Report missed winners, avoided losers, trade count, sector concentration and availability of point-in-time membership. Promote the filter only if holdout evidence justifies its complexity.

## Stage 3: read-only AI assistant for analysis

Start AI with post-session log explanations, incident grouping, report summaries and trading-journal questions. The assistant consumes validated local exports and cites exact records. It does not receive credentials or a broker order tool. Keep deterministic calculations in Java and provide the computed numbers to the model; do not ask an LLM to invent P&L, fill prices or missing data.

News summarization is optional later. Retain source URL, publication time, retrieval time and unedited text. Treat external news as untrusted data; embedded instructions must not control the trading program. Reject stale or unverified items. A model-generated summary is not a reliable fact merely because it sounds confident; NIST identifies confabulation as a generative-AI risk in its [GenAI profile](https://nvlpubs.nist.gov/nistpubs/ai/NIST.AI.600-1.pdf).

Acceptance: every reported number comes from deterministic exports and every claim has a traceable source; AI failure does not change or delay trading.

## Stage 4: predictive model in observation-only mode

Choose a narrow question: for an otherwise valid ORB candidate, estimate the chance of reaching the target before the stop/time exit, or estimate expected net return conditional on costs. Define how time exits, partial fills and unfilled entries are labelled. An unfilled order is not a profitable or losing filled trade. Use features available at the candidate timestamp: normalized opening range, same-time relative volume, spread, recent realized volatility, market/sector direction and gap from previous close.

Collect much more data than one month of up to three daily trades before trusting a model. Log all eligible candidates, including those the rules skip, where lawful data access permits it; otherwise selection bias can dominate. Begin with an interpretable simple model, compare with a rules-only filter, and save versioned features and model outputs. Model predictions must arrive within a bounded deadline; late/missing predictions must follow a predetermined fallback policy.

Acceptance: chronological development/validation/untouched test partitions, walk-forward assessment, no future information in feature calculations, and separation of overlapping outcome windows. Record every attempted model/parameter variant. Repeatedly selecting the best backtest can overfit; see the primary research on [the probability of backtest overfitting](https://www.davidhbailey.com/dhbpapers/backtest-prob.pdf).

## Stage 5: AI-filtered paper comparison

Run a separately labelled paper experiment alongside the unchanged baseline, with independent accounting and risk limits. Initially let the model only veto an entry; do not let it invent tickers, override position limits, widen stops or increase risk. The change from baseline should be explicit and bounded. Deterministic session, freshness, cash and risk checks always remain authoritative.

Logical sequence: completed market data -> versioned features -> model score -> fixed decision threshold -> existing deterministic risk checks -> simulated broker. Keep a score-only observation path even when the threshold rejects a candidate, so decisions remain auditable. Measure net expectancy, drawdown, calibration, fill rate, skipped opportunities, sector exposure, stability across regimes, latency and service cost.

Acceptance: improvement survives an untouched holdout period and stress assumptions, not only aggregate training P&L. Document rollback and freeze parameters before evaluation. A profitable month alone is insufficient evidence.

## Stage 6: optional later scope

Only after the India equity baseline and any AI filter are understood, add an options-specific contract/risk model or a US market adapter. Options need expiry, lot size, Greeks/exposure and liquidity controls; US execution needs a separate calendar, currency, session and broker/account policy. Reuse the broker boundary and test contracts while extracting existing India assumptions.

Keep the current one-process deployment. An initial tabular predictor can be small; an LLM need not run on every tick, and a GPU/cloud cluster is not a prerequisite for experiments. New SDKs, model runtimes or paid services should be chosen only after the data and acceptance criteria are clear. No external AI service is installed or invoked by this roadmap.
