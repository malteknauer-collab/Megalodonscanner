# Polymarket Megalodontracker

An Android app that watches Polymarket for unusually large bets and notifies you about the biggest trades.

The app polls the public Polymarket Data API at regular intervals, sorts hits by size, and shows them in a clean list. No server, no account, no external database — everything runs locally on the device.

## Features

- **Automatic background polling** — roughly every 15 minutes, via WorkManager
- **Notifications for Megalodons only** — only the truly large trades alert you, no notification spam
- **Categories by trade size** — Dolphin, Whale, Megalodon, and "GIGALODON" above $1M
- **BUY and SELL separated** — the focus is on bets being placed, not on exits
- **Swipe between categories** — Megalodon → Whale → Dolphin → All
- **No data clutter** — entries older than 3 days are deleted automatically
- **Lightweight** — the API filters by amount server-side, so only large trades are ever downloaded

## Categories


The thresholds live as constants at the top of `WhaleScanner.kt` and can be changed by editing a single number. Dolphon, Whale and Megalodon thresholds are editable, a Megalodon above 1Mio automatically turns into a Gigalodon, but just in the card's description

## How the data is fetched

The app uses the public endpoint `GET https://data-api.polymarket.com/trades` — free and without authentication.

Two details keep traffic low while making sure no trade is missed:

**Server-side filtering.** With `filterType=CASH&filterAmount=10000` the API returns only trades above the threshold, instead of transferring thousands of records that would be discarded afterwards.

**A watermark instead of fixed time windows.** The app stores the timestamp of the most recently seen trade and pages backwards (`offset`) on each run until it finds it again. Whether five or five thousand large trades happened since the last poll makes no difference — even a delayed background run catches up. Completeness does not depend on the polling interval.

Deduplication uses `transactionHash` combined with the traded asset.

## Structure

| File | Purpose |
|---|---|
| `WhaleScanner.kt` | Data model, API client, local storage, background worker, notifications |
| `MainActivity.kt` | UI (Jetpack Compose): list, filters, swipe gesture, manual refresh |

## Building it yourself

Requires Android Studio.

1. Create a new project: **Empty Activity** (Compose) template, language **Kotlin**, package name `com.example.polyscan`
2. Copy `WhaleScanner.kt` and `MainActivity.kt` from this repository into the package folder
3. In `app/build.gradle.kts`, add under `dependencies`:

```kotlin
implementation("androidx.work:work-runtime-ktx:2.11.2")
```

4. In `AndroidManifest.xml`, add above `<application>`:

```xml
<uses-permission android:name="android.permission.INTERNET" />
<uses-permission android:name="android.permission.POST_NOTIFICATIONS" />
```

5. Build and run

For reliable background polling, exclude the app from battery optimization in the Android settings (Apps → Megalodontracker → Battery → "Unrestricted").

## Limitations

Android runs periodic background work at most about every 15 minutes, and may delay it further while the device is idle. That is fine for rare large trades; guaranteed real-time alerts would require a dedicated server and push infrastructure.

The `/trades` endpoint caps `offset` at roughly 10,000. In theory an extreme burst could reach that limit — in practice it will not, since the filter only ever counts large trades.

## What this app is not

A large trade is not proof of insider trading. Market makers, arbitrageurs, and simply wealthy traders move large sums constantly, and a sell is often just profit-taking. The app surfaces and sorts publicly available market data — interpreting it is up to the reader.

More telling than the raw amount are anomalies in context: a position that is large relative to a market's usual volume, a wallet with no history, or a one-sided entry into a cheap long-shot shortly before an event.

This is not financial advice.

## License

MIT
