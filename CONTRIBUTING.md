# Contributing

Contributions are welcome. Please open an issue before substantial changes so the scope and privacy implications can be discussed.

## Development

1. Install Android Studio, JDK 17+, Android SDK 36, and Build Tools 36.0.0.
2. Build and run the existing tests with `.\gradlew.bat :domain:test :policy:testDebugUnitTest :client-app:assembleDebug :admin-app:assembleDebug`.
3. Keep platform-independent rules in `domain`; Android APIs belong in `data`, `services`, or `app`.
4. Add tests for domain behavior and document permission, storage, and privacy changes.

Do not add hidden data collection, camera behavior, navigation interception, or a dependency on Google Play Services. Any permission must have a user-visible purpose and disclosure.

By submitting a contribution, you agree to license it under the repository's GNU AGPLv3-or-later license.
