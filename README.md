# xDrip-Watch — personal xDrip fork
> Experimental personal fork of [NightscoutFoundation/xDrip](https://github.com/NightscoutFoundation/xDrip), with separate phone-to-Garmin development work.

This repository is not an official xDrip/Nightscout release. The upstream xDrip resources and download page linked below refer to upstream releases, **not** builds made from this fork.

 <img align="right" src="Documentation/images/download-xdrip-plus-qr-code.png">
 Upstream xDrip resources and downloads: https://jamorham.github.io/#xdrip-plus

<img align="right" src="https://travis-ci.org/jamorham/xDrip-plus.svg?branch=master"><a align="right" title="Crowdin" target="_blank" href="https://crowdin.com/project/xdrip"><img align="right" src="https://badges.crowdin.net/xdrip/localized.svg"></a>

## Features
* Voice, Keypad or Watch input of Treatments (Insulin/Carbs/Notes)
* Visualization of Insulin and Carb action curves + Undo/Redo
* Improved alerts and predictive low forecasting feature
* Instant data synchronization between phones and tablets
* Support for many different data sources
* Published by the Nightscout Foundation

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

This fork's `garmin-bridge` branch contains experimental phone-to-Garmin Connect IQ bridge work. It is separate from upstream xDrip support. The Tandem t:slim X2 integration now has an **opt-in PumpX2 BLE adapter prototype** and synthetic response-mapper tests, but it is not wired into a UI/Garmin message path and has not been tested against a physical pump.

- [Working agreement for contributors and coding assistants](AGENTS.md)
- [Tandem t:slim X2 integration design](Documentation/technical/Tandem_X2_Integration.md)
- [Tandem implementation status](Documentation/technical/Tandem_X2_Integration_Status.md)
- [Tandem integration test plan](Documentation/technical/Tandem_X2_Integration_Test_Plan.md)

The design is local-first and intended to be read-only at the therapy level. PumpX2 is experimental and not an official Tandem SDK; the wrapper allowlists authentication and selected status requests only. Fresh pairing is deliberately blocked because PumpX2 v1.9.3 logs and persists pairing codes. Do not start a live pump session before pairing-secret handling and official-app coexistence/recovery are reviewed. Never treat modeled insulin estimates as pump-reported values.

- [Tandem vs. Nightscout integration options](Documentation/technical/Tandem_Nightscout_Integration_Options.md)
- xDrip already contains general Nightscout upload/download components. The current Tandem prototype does not yet send pump data through those existing paths, and it does not implement a Nightscout uploader of its own.

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
