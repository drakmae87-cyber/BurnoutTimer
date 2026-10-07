# Security policy

## Reporting a vulnerability

Please do not disclose exploitable vulnerabilities in public issues. Use GitHub's private vulnerability reporting for this repository, or contact the maintainers privately after replacing the placeholder contact below.

**Security contact:** replace this line with a maintained private contact before publishing.

## Scope and data

The app is intended to keep session state and user-created settings on-device. Room is encrypted with SQLCipher; the database key is wrapped by Android Keystore. Do not include personal data, database contents, or credentials in reports.

The initial release does not implement camera capture or network telemetry. Permission and data-flow changes require explicit disclosure and review.
