# Translations

The apps' strings are keyed by their English text (the iOS keys).

- `keys.json`: every key, in order. `<lang>.py`: `T`, the translations of those keys in the same order (ja, zh-Hant, ko, es, fr, de, it, pt-BR, ru, vi, th, id).
- Simplified Chinese is kept directly in `iosApp/AENHT/Resources/zh-Hans.lproj/Localizable.strings`; English uses the keys.
- `android_extra.py`: Android-only strings.

To add a string: append its key to `keys.json`, its translation to the end of `T` in each `<lang>.py`, and the Chinese line to the zh-Hans file; then run, from the repository root:

```sh
python3 tools/i18n/gen.py iosApp/AENHT/Resources ja zh-Hant ko es fr de it pt-BR ru vi th id
python3 tools/i18n/gen_android.py iosApp/AENHT/Resources androidApp/src/main/res \
  androidApp/src/main/java/com/atlanyuey/aenht/core/L10nKeys.kt
```

`gen.py` checks that the format specifiers of each translation match its key.
