# Reaktor ecosystem

Mobile crash reporting uses [Crashlytics with Reaktor client evidence](https://reaktor.build/docs/operations-crashlytics). Live device inspection uses Reaktor's own DevTools agent.

The Measure SDK integration, dashboard client, Hangar tab, local launcher, Compose configuration and vendor submodule have been removed. No hosted Measure service is required.

Existing local recordings and ignored installation files remain preserved. Retired Docker containers were removed without deleting their volumes. The clean vendor checkout is archived outside the repository at `~/.local/share/reaktor/retired-measure/20261007/checkout`.
