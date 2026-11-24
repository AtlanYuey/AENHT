# Notes for AI assistants working on AENHT

## Version numbers

- The version shown to users (Android `versionName`, iOS `MARKETING_VERSION`)
  is the **next version after the last published release** (the last `v*` tag
  on GitHub). Until the owner publishes that release, every change goes into
  the same version: never bump it again on your own.
- Build numbers (Android `versionCode`, iOS `CURRENT_PROJECT_VERSION`) may go
  up for each build sent to the owner (a device only installs over a build
  that isn't newer), but they are not version numbers.
- One release note file per version: `docs/releases/v<version>.md`, holding
  everything since the last published release.
- The owner publishes releases (tags) on the web; don't create tags or releases.
