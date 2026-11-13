# iOS signing in CI

The iOS workflow signs the IPA when these repository secrets exist
(Settings › Secrets and variables › Actions). Without them it builds an
unsigned IPA (`_unsigned` in its name) for sideloading tools.

| Secret | Value |
| --- | --- |
| `IOS_CERT_P12_BASE64` | the signing certificate with its private key (`.p12`), base64 |
| `IOS_CERT_PASSWORD` | the `.p12` password |
| `IOS_PROVISION_BASE64` | the provisioning profile (`.mobileprovision`), base64 |

On a Mac: `base64 -i cert.p12 | pbcopy`, `base64 -i profile.mobileprovision | pbcopy`.

- A profile for one app id signs the app with that bundle id; a wildcard profile keeps `com.atlanyuey.aenht`.
- The entitlements come from the profile. iCloud key-value sync works only with a profile that has the iCloud capability.
- The run summary shows the certificate, the profile (team, app id, expiry) and the signed bundle id, so a broken or expired secret shows up there.
- The newest build is always at the `dev-latest` pre-release as `AENHT.ipa` / `AENHT-debug.apk`.
