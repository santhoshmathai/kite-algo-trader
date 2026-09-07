# Bundled Kite Java SDK

Official upstream: https://github.com/zerodha/javakiteconnect

Source snapshot: `4efe96982740bd1d110073e865e73030ea4eb635` (version 4.0.1), retrieved 2026-09-07.
`kiteconnect.jar` is the upstream bundled distribution, including the runtime libraries distributed by Zerodha. No separate application library was added.

SHA-256: `EDB5AAAC2E675964EF22D8990224C4B9EFE45BAAE3F85CB17FDAB733C64FDB22`

The upstream MIT license is in `KITE-LICENSE.txt`. Preserve this directory when distributing the application. The PowerShell build verifies the checksum and copies the JAR into `build/lib`. Maven uses the pinned local JAR, so an unverified Maven artifact is not substituted silently. Review SDK and bundled dependency updates deliberately before changing the checksum.
