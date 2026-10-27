# xDrip-Watch — personal xDrip fork
> Experimental personal fork of [NightscoutFoundation/xDrip](https://github.com/NightscoutFoundation/xDrip), with separate phone-to-Garmin development work.

This repository is not an official xDrip/Nightscout release. The upstream xDrip resources and download page linked below refer to upstream releases, **not** builds made from this fork.

 <img align="right" src="Documentation/images/download-xdrip-plus-qr-code.png">
 Upstream xDrip resources and downloads: https://jamorham.github.io/#xdrip-plus

**Upstream project badges (not this fork's build status):**

<img align="right" src="https://travis-ci.org/jamorham/xDrip-plus.svg?branch=master"><a align="right" title="Crowdin" target="_blank" href="https://crowdin.com/project/xdrip"><img align="right" src="https://badges.crowdin.net/xdrip/localized.svg"></a>

## Features
* Voice, Keypad or Watch input of Treatments (Insulin/Carbs/Notes)
* Visualization of Insulin and Carb action curves + Undo/Redo
* Improved alerts and predictive low forecasting feature
* Instant data synchronization between phones and tablets
* Support for many different data sources

 <img align="middle" src="https://jamorham.github.io/images/jamorham-natural-language-treatments-two-web.png">

## What does it do?

xDrip is an unofficial and independent Android app which works as data hub and processor between many different devices.

It supports wireless connections to G6, G7, Medtrum A6, Libre via NFC and Bluetooth, 630G, 640G, 670G pumps, CareSens Air and Eversense CGM via companion apps. Bluetooth Glucose Meters such as the Contour Next One, AccuChek Guide, Verio Flex & Diamond Mini as well as devices like the Pendiq 2.0 Insulin Pen.

Heart-rate and step counter data is processed from Android Wear, Garmin, Fitbit and Pebble smart-watches and watch-faces for those that show glucose values and graphs.

On some Android Wear watches, it is possible for the G6 to talk directly to the watch so it can display values even when out of range of the phone.

The app contains sophisticated charting, customization and data entry features as well as a predictive simulation model.

Instant two-way synchronization is possible by linking follower handsets, data can also be uploaded and downloaded to a Nightscout web service or uploaded directly to Tidepool, MongoDB or InfluxDB.

Customization allows for different options to configure alarms, vocalize readings, change the display preferences etc. International users can update translations from within the app too.

Your data is yours and can be exported in many different ways. xDrip also intercommunicates with other apps, for example sending and receiving live data with AndroidAPS.

## xDrip-Watch fork development (experimental)

This fork's `garmin-bridge` branch contains experimental phone-to-Garmin Connect IQ bridge work. It is separate from upstream xDrip support. The Tandem t:slim X2 integration has an **opt-in PumpX2 BLE adapter prototype** and synthetic response-mapper tests. It is reachable only from a development test screen, is not wired to app startup or the Garmin message path, and has not been tested against a physical pump.

- [Working agreement for contributors and coding assistants](AGENTS.md)
- [Tandem t:slim X2 integration design](Documentation/technical/Tandem_X2_Integration.md)
- [Tandem implementation status](Documentation/technical/Tandem_X2_Integration_Status.md)
- [Tandem integration test plan](Documentation/technical/Tandem_X2_Integration_Test_Plan.md)
- [Android Auto integration plan](Documentation/technical/ANDROID_AUTO_INTEGRATION_PLAN.md) — feasibility-first; verifies a supported, policy-compliant in-car surface before adding any car UI.

The pump status/history path is local-first and read-only at the therapy level. Separately, this branch contains an owner-approved, experimental **phone-initiated bolus prototype** behind the explicit gates in `AGENTS.md`; it is not part of the Garmin bridge and has never been sent to a pump. PumpX2 is experimental and not an official Tandem SDK. `TandemX2PumpClient` overrides PumpX2's pairing method to avoid logging the pairing code and derived secret, permits pairing only in `OWN_PAIRING` mode, and refuses to start if any Timber tree is planted because PumpX2 also logs parsed pump messages. PumpX2 still stores the pairing code and derived secret in app-private preferences; clearing that state is not implemented. Pairing, official-app coexistence, and recovery have not been validated on a physical pump. Never treat modeled insulin estimates as pump-reported values.

- [Why Nightscout REST is not the local exchange protocol](Documentation/technical/Tandem_Nightscout_Integration_Options.md)
- [Local Tandem ↔ xDrip ↔ Garmin data-exchange contract](Documentation/technical/LOCAL_DATA_EXCHANGE_ARCHITECTURE.md)
- xDrip already contains general Nightscout upload/download components. No Nightscout cloud upload is required for the intended local system. The cross-repository contract documents local Tandem↔xDrip status/history and bidirectional xDrip↔Garmin messages. The watch-to-phone `glucose` v1 sender/parser/receiver path exists in source and has host-test coverage, but end-to-end physical delivery is unverified. The phone-side `pump_status` v1 builder and manual test action exist; the watch receiver/display and `pump_history` are not implemented, and pump status is not sent automatically.

## Ethos
* Developed using Rapid Prototyping methodology
* Immediate results favoured to prove concepts
* Designed to support my personal research goals
* User Choice always a high priority
* No registration or Internet access required
* Community testing and collaboration appreciated!

## Roadmap
* Calibration improvements
* Supporting the large family of devices
* Increasing automation and data backup and sync options
* More Nightscout and APS integration

## Collaboration
We are very happy if people want to collaborate with this project. Please contact us at [Discussions](https://github.com/NightscoutFoundation/xDrip/discussions) if you want to get involved and study the [collaboration guidelines](CONTRIBUTING.md) before submitting any patches or pull requests.

## Thanks
None of this would be possible without all the hard work of the xDrip and Nightscout communities who have developed such excellent software and allowed us to build upon it.
