# Android Auto integration plan for xDrip-Watch

**Status:** Feasibility plan only; no Android Auto implementation or hardware validation has been performed.  
**Scope:** The xDrip-Watch fork on `garmin-bridge`; phone-provided CGM data, local-first, no new cloud dependency.  
**Platform target:** Android Auto phone projection. Android Automotive OS is a different platform and is not an interchangeable implementation target.

## Goal

Determine whether xDrip can safely expose a minimal, glanceable view of the current glucose value while the phone is projected to a vehicle display. The intended information is limited to:

- current glucose value and unit;
- trend only when supported by a valid current reading;
- age/time of the reading and clear stale/unavailable state;
- optionally, an urgent alert or spoken alert through an existing xDrip alert mechanism, subject to user settings and driver-distraction rules.

The phone remains the source of truth. The first version must not add a server, upload glucose data to a new service, introduce a new CGM collector, or depend on the experimental Tandem pump adapter. Do not show stale values as live, and do not include bolus entry, insulin dosing, treatment entry, pump commands, or other therapy-changing actions in a driving experience.

## Hard feasibility gate: Android Auto category and policy

**Do this review before writing a car UI.** Android Auto is not a generic projection surface for any Android activity. The public Android for Cars documentation lists eligible car-app categories such as media, messaging, calling, navigation, points of interest (POI), IoT and weather; it does not list a general health/CGM dashboard category. A templated app must qualify for a supported category and follow the applicable car-quality guidelines. Parked-app support is restricted to specified categories and must not be treated as a workaround for a live glucose dashboard.

Official references (re-check these before implementation because platform policy can change):

- [Android for Cars overview and supported categories](https://developer.android.com/training/cars)
- [Android Auto platform overview](https://developer.android.com/training/cars/platforms/android-auto)
- [Set up the Android for Cars App Library](https://developer.android.com/training/cars/apps/library/set-up-project)
- [Car app quality guidelines](https://developer.android.com/docs/quality-guidelines/car-app-quality)
- [Extend messaging notifications to Android Auto](https://developer.android.com/training/cars/communication/notification-messaging)

Consequences:

1. Do **not** disguise glucose display as weather, IoT, media, navigation, or another category that does not truthfully describe the app.
2. Do **not** use `MessagingStyle` or a fake conversation to force glucose values/alerts into Android Auto's messaging UI. The messaging integration is for actual messages/conversations and their message actions, not a generic health-data feed.
3. Do **not** assume arbitrary xDrip notifications will appear on the car display. Notification presentation and suppression depend on the Android Auto host/settings; this must be tested and treated as an unverified possibility, not promised functionality.
4. Do not confuse Android Auto with Android Automotive OS. AAOS-specific parked browser/video/game routes do not create a supported path for a CGM dashboard on Android Auto.
5. If current official categories/policy offer no legitimate path for a direct, live glucose screen, document that as the outcome and stop rather than shipping a non-compliant hidden category. Revisit only if Google documents or approves an eligible route.

## Phased work plan

### AA0 — policy and distribution feasibility

- Recheck the official Android for Cars category list, quality requirements, app-distribution restrictions, and current Android Auto notification behavior.
- Decide whether a minimal current-glucose display qualifies for any supported category without misrepresenting xDrip's purpose. Get clarification from the relevant Google Play/Android Auto program if the public documentation is ambiguous.
- Write down the result and the qualifying category (if any) before creating a `CarAppService`, adding car metadata, or changing the app manifest.

**Gate:** an explicit, defensible, policy-compatible route exists. If not, skip native templated UI and proceed only with the notification/audio feasibility test below.

### AA1 — notification and audible-alert feasibility spike

- Use an isolated experimental branch and existing xDrip glucose/alert sources. First prototype should not add Android Auto manifest declarations, a new app category, or a new service.
- With explicit user opt-in, test whether normal xDrip glucose-alert notifications and/or its existing spoken-alert feature behave usefully while Android Auto is connected. Evaluate urgent alerts separately from routine five-minute glucose updates; avoid generating a continuous stream of distracting notifications.
- Treat in-car visibility, alert sound, and voice playback as separate outcomes. A phone notification existing does not prove Android Auto displays it; an audible phone alert does not prove the car display shows glucose.
- No notification or message payload may expose sensor keys, authentication data, logs, or unrelated personal health information.

**Gate:** record what is actually surfaced on the target Android version and Android Auto host, with notification/alert settings and permissions. If nothing is surfaced, do not invent a MessagingStyle workaround.

### AA2 — data contract and freshness model

If AA0 identifies a legitimate supported route, define an Android Auto presentation model separate from the core xDrip database and UI. It should consume the existing selected CGM source and represent:

- value and unit;
- reading/source timestamp and phone receive timestamp where available;
- trend only when valid;
- `CURRENT`, `STALE`, `UNAVAILABLE`, or `ERROR` state;
- explicit formatting for missing, invalid, and stale values.

No value should be fabricated as zero. Do not infer a value from the Tandem prototype, the Garmin bridge, Nightscout, or a second collection path. Use the same source and freshness rules as the main app where feasible.

**Gate:** deterministic unit tests cover fresh readings, stale thresholds, missing data, duplicate readings, unit formatting, trend absence, and collector disconnect/recovery.

### AA3 — minimal compliant car UI (only if AA0 passes)

- Implement only the templates and actions permitted by the verified category and current Android for Cars App Library requirements.
- The driving view, if allowed, should be glanceable and read-only: current value, unit, trend when valid, and age/stale state. Use the host's native templates rather than a custom unrestricted activity.
- No treatment entry, bolus initiation, pump pairing, therapy settings, charts requiring scrolling, logs, or complex interaction while driving. Do not build a general in-car settings screen.
- Respect host lifecycle and driving/parked restrictions. The regular xDrip phone UI remains the route for settings, detailed history, and troubleshooting.

### AA4 — validation and privacy

- Add unit tests for the view model/formatter and lifecycle behavior before relying on device tests.
- Test the notification spike with Android Auto Desktop Head Unit and a real phone/compatible head unit while parked; test any accepted templated integration using the official car-app validation process.
- Validate connected/disconnected projection, user notification settings, Do Not Disturb, app backgrounding, stale data, and no-current-reading state. Do not test while driving.
- Verify data stays local and that no new permission, account login, server, or telemetry sink is introduced without a separate review.
- Record phone model, Android version, Android Auto version, app build, host/head-unit environment, settings, expected vs observed UI/audio, and logs sanitized of health data/secrets.

### AA5 — explicit go/no-go decision

Publish one of these outcomes in this file:

- **GO — notification/audio only:** describe the verified behavior and its limitations; do not claim a dashboard.
- **GO — approved templated UI:** document the supported category, review/distribution route, templates, and device evidence.
- **NO-GO — no eligible category/surface:** retain safe phone-based notifications/voice where useful, and leave native Android Auto UI unimplemented until platform policy offers a suitable route.

A successful APK build is not proof of Android Auto approval or actual head-unit presentation.

## Current project boundaries

- This is a plan, not a statement of current Android Auto support.
- Current Garmin Connect IQ bridge work remains on the `garmin-bridge` branch and is separate from Android Auto. Garmin phone/watch messaging does not require Nightscout REST.
- The experimental Tandem t:slim X2 adapter remains outside the first Android Auto slice. Any future pump status shown in a car needs a separately reviewed source/freshness model; unknown or absent pump data is not zero.
- Maintain xDrip's ordinary CGM collection and alerting behavior when Android Auto is unavailable or disconnected.
