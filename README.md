# Burnout Timer

Burnout Timer is an Android-native, open-source, local-first project with two apps: **Client** for a dedicated device and **Admin** for its local administrator. Admin configures timed controls, pre-session alerts, and start/end messages. Signed policies transfer by QR; there is no central server, account, telemetry upload, or live remote control.

> Client can temporarily enter Android Lock Task kiosk and suspend explicitly selected apps only after local Device Owner provisioning. Emergency-call and key Android system packages are protected. The schedule restores access at expiry; it does not power the handset off or wake a fully powered-off handset.

## Architecture

```text
app       Spanish Client UI, calendar, timer, Device Owner policy receiver
admin-app Admin Compose UI, QR scanner, local policy signing
policy    Shared, validated policy model and ECDSA QR codec
domain    Platform-independent models, repository contracts, geofence math
data      Room/SQLCipher, DataStore, Android Keystore-managed DB passphrase
services  Foreground timer, boot receiver, native location provider
```

`domain` has no Android dependency. Android modules depend inward on its models and contracts. DataStore keeps active-session state and language compatibility data; the Client interface is Spanish. Room stores scheduled events in the SQLCipher database; migration 1-to-2 preserves existing safe-zone, custom-message, and session-log data. The database passphrase is randomly generated and wrapped by an AES-GCM key held in Android Keystore.

## Build

Requirements: Android Studio with JDK 17 or newer, Android SDK Platform 36, and Android Build Tools 36.0.0.

```powershell
.\gradlew.bat :domain:test :policy:testDebugUnitTest :client-app:assembleDebug :admin-app:assembleDebug
```

Open the project root in Android Studio. Debug APKs:

- Client: `app\build\outputs\apk\debug\client-app-debug.apk`
- Admin: `admin-app\build\outputs\apk\debug\admin-app-debug.apk`

GitHub Actions uses Node.js 24 via `actions/setup-node@v5` and Java 17 via `actions/setup-java@v5`.

Client targets Android 15 (API 35) and compiles against API 36. The Spanish-only Client provides a configurable 1–1,440 minute timer and a local calendar of one-time events with overlap checks and reminders. Admin policies separately schedule when kiosk starts and ends.

Grant notification permission to receive calendar reminders and Admin alerts/messages. Client uses no overlay, accessibility interception, photo capture, or location monitoring; camera access is used only to scan the pairing/policy QR codes.

## Admin and Client setup

1. Install Client on the dedicated Android device and Admin on the guardian's phone. The two apps exchange only QR codes; no account, C&C server, or network connection is used for policy delivery.
2. To enable app suspension and kiosk, provision **Client** as Device Owner. During initial device setup (or after a factory reset), enable USB debugging, connect ADB, install Client, and run:

   ```powershell
   adb shell dpm set-device-owner com.burnouttimer.client/com.burnouttimer.client.admin.ClientAdminReceiver
   ```

   Android rejects this if the device already has an account/owner or is not eligible for provisioning. This command must be run locally; Admin cannot provision a device remotely. A factory reset erases existing data.
3. In Client, enable exact alarms when prompted. Display the Client invitation QR. In Admin, scan it, then have Client scan the Admin public-key QR. The Client pins that key and rejects policies signed by another key.
4. In Admin, enter the Client ID, start date/time, duration (1–1,440 minutes), selected Android package IDs, optional pre-start alert, start/end messages, and whether to enable kiosk. Show the signed policy QR and scan it in Client.
5. At the scheduled start, Client suspends the selected installed packages and enters Android Lock Task when kiosk is enabled. Home resolves to Client while the policy is active; the previous launcher is restored at expiry. Essential system information, notifications, global actions, lock screen, and emergency-call apps remain available. At expiry Client unsuspends the selected apps and restores normal navigation. Policies are finite and limited to 24 hours; grant exact-alarm access for precise timing.

Keep Admin installed and its app data intact: its ECDSA signing key is stored in Android Keystore and is not recoverable if the app data is cleared or the app is removed. The package list is entered manually by Android package ID; Client requests `QUERY_ALL_PACKAGES` so it can check installation and restore selected apps. Alerts/messages display locally on Client; QR delivery is one-way and there is no acknowledgement channel to Admin.

**Automatic power and recovery limits:** the schedule starts kiosk and restores app/navigation access at specified times; Android apps cannot electrically shut down a standard handset or power it on after shutdown. Use the physical power controls for actual shutdown/wake. Android may delay a fallback alarm if exact-alarm access is revoked or prevent an activity from surfacing while backgrounded; Client persists the signed policy, restores alarms/kiosk configuration after reboot and unlock, and restores the previous launcher at expiry. Understand local ADB/factory-reset recovery before enabling kiosk. Device Owner is not hidden surveillance or remote management.

## Security and privacy choices

- No Google Play Services dependency is used.
- This is a local, QR-mediated system, not a cloud parental-control service. Admin has no live view of Client and receives no data from it.
- Location uses Android `LocationManager`; `NativeGeofenceEngine` emits local out-of-bounds booleans as a Kotlin Flow. A user-facing location/geofence setup and permission flow is still required before this engine is activated.
- Scheduled calendar events are persisted in encrypted Room; active timer state is stored in DataStore. The Room schema migrates existing version 1 databases without destructive recreation.
- Custom messages, safe-zone records, and session-log entities remain provisioned in the encrypted Room database; their editing/history screens are not yet wired.
- No photo or telemetry capture is implemented. In particular, there is no hidden or background camera capture.
- Managed kiosk temporarily uses Android Device Owner, Lock Task, and preferred-Home APIs; it does not use an `AccessibilityService` to intercept Home, Back, or Recents. Emergency calling and essential system components are excluded from package suspension.
- Session expiry is persisted as wall-clock time, so a reboot can restore only an unexpired session. Android may refuse background service starts; failures are logged, not silently treated as success.
- The app does not request background location, battery-optimization exemptions, or high-rate sensor access until an explicit feature justifies them.

## Roadmap by phase

### Stage 1 — Foundation

- **1.1 Environment and structure:** Kotlin/Compose, `app`, `data`, `domain`, and `services` modules; Hilt; Room with SQLCipher; DataStore session preferences.
- **1.2 Timer engine:** user-started foreground service, custom 1-1,440 minute timer, and persistent notification. No overlay permission is requested.
- **1.3 Restore:** boot receiver resumes an unexpired session. Accessibility-based navigation interception is intentionally excluded.

### Stage 2 — Native security and geofencing

- **2.1 Geofence:** Haversine calculation and native `LocationManager` flow are implemented. Permission UX, zone editing, and service wiring remain to be completed.
- **2.2 Camera:** not implemented. Silent/discreet capture is excluded; any future camera feature must be explicitly initiated, consent-based, and visibly indicated.
- **2.3 Telemetry:** no telemetry is transmitted or captured. Local encrypted logging remains a follow-up feature.

### Stage 3 — User experience

- Spanish Client UI, custom timer, scheduled one-off events, overlap validation, and reminders are implemented. Admin/Client QR pairing, signed timed policies, local alerts/messages, selected-package suspension, scheduled kiosk start/expiry, and boot restoration are implemented. Onboarding/disclosure, map selection, and session history remain follow-up work.

### Stage 4 — FOSS publication

- **4.1:** Haversine unit tests are included; broader audit and dependency checks remain required.
- **4.2:** community documents and issue templates are included below/in the repository; funding account URLs must be replaced with the maintainer's real accounts.
- **4.3:** GitHub Actions builds both debug APKs and signs release APKs only when repository signing secrets are configured. F-Droid metadata is a starter recipe and must be submitted/reviewed upstream; F-Droid builds and signs its own APK.

## Release signing

Create a dedicated upload keystore outside the repository. Configure these GitHub Actions secrets:

- `ANDROID_KEYSTORE_BASE64`: Base64-encoded keystore file
- `ANDROID_KEYSTORE_PASSWORD`
- `ANDROID_KEY_ALIAS`
- `ANDROID_KEY_PASSWORD`

Pushing a `v*` tag builds and attaches signed Client and Admin APKs. Never commit keystores or passwords. F-Droid builds from source and uses its own signing keys; the GitHub APKs are not the F-Droid artifacts.

## APK download site

`.github/workflows/pages.yml` runs the domain tests, builds both debug APKs, calculates their SHA-256 checksums, and publishes them with the static page in `site/` to GitHub Pages on pushes to `main`/`master` or via manual dispatch. GitHub Pages must be enabled for the repository and configured to deploy with GitHub Actions. These are development APKs signed with the debug key, not production releases.

Current download page: https://drakmae87-cyber.github.io/BurnoutTimer/

## Funding

- [GitHub Sponsors](https://github.com/sponsors/REPLACE_WITH_GITHUB_USERNAME)
- [Open Collective](https://opencollective.com/REPLACE_WITH_OPEN_COLLECTIVE_SLUG)
- [Ko-fi](https://ko-fi.com/REPLACE_WITH_KOFI_USERNAME)

Replace the placeholder account names before publishing.

## License

GNU Affero General Public License v3.0 or later. See [LICENSE](./LICENSE).
