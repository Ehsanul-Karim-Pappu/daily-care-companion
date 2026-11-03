# Daily Care Companion

An Android app that fits care routines around desk work with as little daily input as possible. It handles four things:

- **Walking breaks:** it notices when you've been at work a long time without walking and reminds you to move. A walk with the phone counts automatically, prompted or not.
- **Therapy visits:** it recognises likely visits to the therapy place. A morning visit stops the evening reminder.
- **Exercises:** it counts the physiotherapist's exercise rounds from a one-tap confirmation.
- **Medicines:** it reminds you of doses and records each one only when you confirm it.

The log keeps what **you confirmed** separate from what **the phone inferred**.

> This app tracks whether a plan is followed. It does not give medical advice. Break intervals, walk lengths, exercises and medicines come from your clinicians and are entered in the app's settings.

## What is automatic and what needs a tap

| | Automatic | Your input |
|---|---|---|
| Where you are (home / work / therapy place) | Yes, from Wi-Fi and location | Save each place once |
| Walking breaks at work | Yes, from the step counter | Only "walked without my phone" or snooze |
| Therapy visit | Inferred as *likely* | Optional yes/no. Ignoring it is fine |
| Exercise rounds | No | One tap per round (notification, Today screen or quick-settings tile) |
| Medicine doses | **Never inferred** | One tap per dose: Taken or Skipped |

## How it decides

- **Place.** These signals are checked in order of strength:
  1. connected Wi-Fi
  2. Wi-Fi networks visible nearby (no connection needed)
  3. a location fix
  4. geofences

  A place always needs *positive* evidence. Losing home Wi-Fi during load-shedding never puts you at the therapy place. Arrivals and departures must persist for a few minutes before they count.
- **Walking break.** Steps are binned per minute. A walk counts only if it has enough walking minutes **and** steps. Short pauses are allowed inside a walk. Carrying the phone briefly is logged as a "short movement", not a break. Phones without a step counter fall back to Android activity recognition, which is less precise.
- **Break reminder.** After *N* minutes at work without a qualifying walk, you get one reminder. If you ignore it, one follow-up comes, then it stays quiet until your next break. Reminders wait while you're walking.
- **Therapy.** A visit is *likely* when you spend at least the minimum time at the therapy place. A session starting by the morning cut-off counts as morning; one after the evening start counts as evening.
  - Low-confidence visits show as *possible*, and they still allow the evening reminder.
  - Visits running past midnight count for the evening they started. The day boundary defaults to 03:00.
- **Medicines.** Doses come from patterns like `1+0+1` or `½+0+1`, with before/after-meal timing, "every N days" (e.g. weekly), and course start and length.
  - A dose with no tap shows as *not confirmed*, never as taken or missed.
  - When an entered course ends, reminders stop and the app asks whether the doctor extended it.
- **Gaps.** If Android stopped the app, the day shows "no data hh:mm–hh:mm" rather than looking like inactivity.

## Install

1. Open the latest successful run under the repository's **Actions** tab.
2. Download the `daily-care-debug-apk` artifact and unzip it.
3. Install the APK on the phone. Android will ask you to allow installing from that source.

Later builds install over earlier ones and keep your data, because every build is signed with the same key (`app/debug.keystore`). That key is public in this repository. Before you share the app beyond your own phone, switch to a private signing key.

## First-time setup (once)

1. **Setup → Permissions:** allow each of these:
   - precise location, then **location all the time**
   - physical activity
   - notifications
   - nearby Wi-Fi
   - exact alarms
   - no battery restriction
2. **Setup → phone background settings.** Vivo and Xiaomi/Poco phones stop background apps unless you allow them. The app lists the steps for your phone and opens the right screen where it can:
   - **Vivo (Funtouch OS / OriginOS):** allow background power use, turn on Autostart, and lock the app in recent apps.
   - **Xiaomi / Poco (MIUI / HyperOS):** turn on Autostart, set battery saver to "No restrictions", and lock the app in recent apps.
3. **Setup → Places:** stand at home, at your desk, and at the therapy place, and tap *I'm here — save* at each.
   - At the therapy place, re-save once inside and once at the entrance. This records more of its Wi-Fi networks.
   - The *Home vs therapy place* card then shows how far apart the two are and whether their Wi-Fi differs. That tells you how reliable visit detection can be.
4. **Observe week** is on by default. The app detects and logs everything, but sends no break or therapy reminders yet. Check the Today screen and timeline for a few days.
   - When detection matches what you actually did, turn observe week off in Settings.
   - Use what you saw to set the break interval and minimum walk.

## Fill in when you have the information

- **Settings → Walking breaks:** the interval and minimum walk agreed with your physiotherapist.
- **Settings → Exercises:** each movement, with its name, reps, hold and per-side flag, plus rounds per day. Then turn exercise tracking on.
- **Settings → Medicines:** only the confirmed regimen. Older prescriptions stay out of it.
- **Settings → Reminders:** quiet hours, and vibrate-only at work.

## Honest limits

- Being at the therapy place is not proof of treatment, so it is shown as a *likely visit* unless you confirm it.
- If the therapy place is very close to home and has no distinct Wi-Fi, some days will be ambiguous. The app marks those as *possible*, not *likely*.
- A phone left on the desk can't see a walk. Use "I walked without my phone".
- Monitoring depends on Android letting the app run. On Vivo and Xiaomi phones the background settings above matter more than anything else. A small ongoing notification shows while monitoring is active.

## Privacy

Everything stays on the phone: the log, places, settings and medicines. The app has no account and no server. Android backup is disabled for the app, so use **History → Export full log (CSV)** to keep a copy or take it to an appointment.

Do not commit personal health information to this repository. It is public.

## Smartwatch later

Step data is stored per minute **per source**, and sources are merged by taking the maximum, so a watch can be added as a second source without changing the logic. The planned route is reading steps from Health Connect, which most Wear OS and fitness watches write to. With a watch, walks without the phone would also be recognised.

## Development

```
core/   Pure Kotlin decision logic (no Android). All rules live here and are unit-tested.
app/    Android app: sensors, storage (SQLite), notifications, Compose UI.
```

- Core tests run anywhere with a JDK: `./gradlew -p core test`
- The APK needs the Android SDK: `./gradlew :app:assembleDebug`. CI runs both on every push.
- Requirements: minSdk 29 (Android 10), targetSdk 35. Google Play services is required for geofences and activity recognition.
