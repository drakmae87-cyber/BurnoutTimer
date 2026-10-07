# Burnout Timer

Burnout Timer is an Android-native, open-source focus timer. It is built with Kotlin, Jetpack Compose, Clean Architecture, and UDF. Its current implementation provides a user-started foreground timer, optional dismissible overlay at completion, persistent session state, encrypted local Room storage, and a native Haversine geofence engine.

> The overlay is optional and dismissible. The app does not block Android system navigation, capture images, or collect location without an explicit, user-facing feature and permission flow.

## Architecture

```text
app       Compose UI, Activity, Hilt application, release configuration
domain    Platform-independent models, repository contracts, geofence math
data      Room/SQLCipher, DataStore, Android Keystore-managed DB passphrase
services  Foreground timer/overlay, boot receiver, native location provider
```

`domain` has no Android dependency. Android modules depend inward on its models and contracts. DataStore keeps the small active-session state; Room is prepared for safe-zone, custom-message, and session-log records. The SQLCipher database passphrase is randomly generated and wrapped by an AES-GCM key held in Android Keystore.

## Build

Requirements: Android Studio with JDK 17 or newer, Android SDK Platform 36, and Android Build Tools 36.0.0.

```powershell
.\gradlew.bat :domain:test :app:assembleDebug
```

Open the project root in Android Studio or install the debug APK from `app\build\outputs\apk\debug\app-debug.apk`.

The app targets Android 15 (API 35) and compiles against API 36. The user starts the 25-minute foreground timer from the app. Granting notification permission makes its persistent notification visible. Granting “Display over other apps” is optional and only permits a dismissible completion overlay.

## Security and privacy choices

- No Google Play Services dependency is used.
- Location uses Android `LocationManager`; `NativeGeofenceEngine` emits local out-of-bounds booleans as a Kotlin Flow. A user-facing location/geofence setup and permission flow is still required before this engine is activated.
- Custom messages, safe-zone records, and session-log entities are provisioned in the encrypted Room database; their full editing/history screens are not yet wired.
- No photo or telemetry capture is implemented. In particular, there is no hidden or background camera capture.
- No `AccessibilityService` is used to intercept Home, Back, or Recents. Android system controls remain available.
- Session expiry is persisted as wall-clock time, so a reboot can restore only an unexpired session. Android may refuse background service starts; failures are logged, not silently treated as success.
- The app does not request background location, battery-optimization exemptions, or high-rate sensor access until an explicit feature justifies them.

## Roadmap by phase

### Stage 1 — Foundation

- **1.1 Environment and structure:** Kotlin/Compose, `app`, `data`, `domain`, and `services` modules; Hilt; Room with SQLCipher; DataStore session preferences.
- **1.2 Timer engine:** user-started foreground service, persistent notification, opt-in completion overlay through `WindowManager`, and Compose overlay UI.
- **1.3 Restore:** boot receiver resumes an unexpired session. Accessibility-based navigation interception is intentionally excluded.

### Stage 2 — Native security and geofencing

- **2.1 Geofence:** Haversine calculation and native `LocationManager` flow are implemented. Permission UX, zone editing, and service wiring remain to be completed.
- **2.2 Camera:** not implemented. Silent/discreet capture is excluded; any future camera feature must be explicitly initiated, consent-based, and visibly indicated.
- **2.3 Telemetry:** no telemetry is transmitted or captured. Local encrypted logging remains a follow-up feature.

### Stage 3 — User experience

- Onboarding/disclosure, user-controlled permission checks, custom-message editing, map selection, and session history remain follow-up work. The initial Compose screen currently manages the timer only.

### Stage 4 — FOSS publication

- **4.1:** Haversine unit tests are included; broader audit and dependency checks remain required.
- **4.2:** community documents and issue templates are included below/in the repository; funding account URLs must be replaced with the maintainer's real accounts.
- **4.3:** GitHub Actions builds debug APKs and signs release APKs only when repository signing secrets are configured. F-Droid metadata is a starter recipe and must be submitted/reviewed upstream; F-Droid builds and signs its own APK.

## Release signing

Create a dedicated upload keystore outside the repository. Configure these GitHub Actions secrets:

- `ANDROID_KEYSTORE_BASE64`: Base64-encoded keystore file
- `ANDROID_KEYSTORE_PASSWORD`
- `ANDROID_KEY_ALIAS`
- `ANDROID_KEY_PASSWORD`

Pushing a `v*` tag builds and attaches the signed APK. Never commit keystores or passwords. F-Droid builds from source and uses its own signing keys; the GitHub APK is not the F-Droid artifact.

## APK download site

`.github/workflows/pages.yml` runs the domain tests, builds a debug APK, calculates its SHA-256 checksum, and publishes both with the static page in `site/` to GitHub Pages on pushes to `main`/`master` or via manual dispatch. GitHub Pages must be enabled for the repository and configured to deploy with GitHub Actions. This is a development APK signed with the debug key, not a production release.

Current download page: https://drakmae87-cyber.github.io/BurnoutTimer/

## Funding

- [GitHub Sponsors](https://github.com/sponsors/REPLACE_WITH_GITHUB_USERNAME)
- [Open Collective](https://opencollective.com/REPLACE_WITH_OPEN_COLLECTIVE_SLUG)
- [Ko-fi](https://ko-fi.com/REPLACE_WITH_KOFI_USERNAME)

Replace the placeholder account names before publishing.

## License

GNU Affero General Public License v3.0 or later. See [LICENSE](./LICENSE).
