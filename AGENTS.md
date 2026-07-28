# Fluence Android Release & Signing Policy (Permanent)

This repository has a permanently established Android production signing pipeline.

The Android signing infrastructure is FROZEN and must not be redesigned or replaced.

## Source of Truth

The ONLY supported release mechanism is GitHub Actions.

The ONLY supported signing credentials are GitHub Actions Secrets.

Never request, regenerate, replace, or redesign the Android signing key unless the user explicitly requests a signing-key migration.

---

## GitHub Secrets

The workflow MUST use ONLY these secrets:

RELEASE_KEYSTORE_BASE64
RELEASE_KEY_ALIAS
RELEASE_KEYSTORE_PASSWORD
RELEASE_KEY_PASSWORD

Never hardcode:

- passwords
- aliases
- keystore paths
- Base64 strings

Never commit any keystore.

Never commit Base64-encoded keystores.

---

## Production Certificate

Alias:

fluence-release

Expected SHA-256 certificate fingerprint:

8955bb6e81047ef452ac68763c47d16916b150a90a743a51ee92ea36b383ca3e

If any build produces a different signing certificate:

STOP.

Treat it as a release-blocking error.

Do not publish.

---

## Release Process

The release process is:

1. Update versionCode/versionName.
2. Commit changes.
3. Push to main.
4. Create and push a version tag.
5. GitHub Actions builds.
6. GitHub Actions reconstructs the keystore from GitHub Secrets.
7. GitHub Actions signs the APK.
8. GitHub Actions verifies the APK signature.
9. GitHub Actions generates release.json.
10. GitHub Actions publishes the GitHub Release.

Agents must never bypass this pipeline.

---

## Required Verification

Before considering a release successful, verify:

✓ APK signed successfully
✓ Production certificate matches expected SHA-256 fingerprint
✓ release.json generated
✓ APK SHA-256 computed correctly
✓ GitHub Release uploaded successfully
✓ No signing errors

---

## Never Do

Never generate a new keystore.

Never rename GitHub Secrets.

Never sign production APKs manually.

Never replace the production certificate.

Never hardcode signing credentials.

Never ask the user to expose keystore passwords unless absolutely required for recovery.

Never modify the release workflow unless fixing a verified bug.

---

## Allowed Changes

Agents MAY:

- update version numbers
- improve CI reliability
- improve release verification
- improve logging
- improve artifact validation

provided they preserve complete compatibility with the existing signing pipeline.

The signing architecture is considered frozen.
