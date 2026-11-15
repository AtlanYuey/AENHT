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

- A profile for one app id signs the app with that bundle id; a wildcard profile keeps `com.atlanyuey.ENHentai`.
- The entitlements come from the profile. iCloud key-value sync works only with a profile that has the iCloud capability.
- The run summary shows the profile (app id, expiry, device count) and the signed bundle id, so a broken or expired secret shows up there. The certificate name and team are masked in the public logs.
- The newest build is always at the `dev-latest` pre-release as `AENHT.ipa` (unsigned) / `AENHT.apk`. The signed IPA is only a workflow artifact (`aenht-ios-signed`, kept 3 days): it carries the owner's provisioning profile.

# Android signing in CI

The release APK is signed with the project's key when these secrets exist;
otherwise the debug APK is published. Every release must use the same key,
or it won't install over the previous version.

| Secret | Value |
| --- | --- |
| `ANDROID_KEYSTORE_BASE64` | the keystore (`.jks`), base64 |
| `ANDROID_KEYSTORE_PASSWORD` | the keystore password |
| `ANDROID_KEY_ALIAS` | the key's alias |
| `ANDROID_KEY_PASSWORD` | the key's password (the keystore password for PKCS12 keystores) |
