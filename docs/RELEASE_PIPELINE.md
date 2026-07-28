# Android Release Pipeline

## Production Signing Policy

This repository has a permanently established Android production signing pipeline.

The Android signing infrastructure is **FROZEN** and must not be redesigned or replaced.

### Source of Truth

The ONLY supported release mechanism is GitHub Actions.

The ONLY supported signing credentials are GitHub Actions Secrets.

Never request, regenerate, replace, or redesign the Android signing key unless the user explicitly requests a signing-key migration.

---

## GitHub Secrets

The workflow MUST use ONLY these secrets:

- `RELEASE_KEYSTORE_BASE64`
- `RELEASE_KEY_ALIAS`
- `RELEASE_KEYSTORE_PASSWORD`
- `RELEASE_KEY_PASSWORD`

### Never hardcode

- passwords
- aliases
- keystore paths
- Base64 strings

### Never commit

- any keystore file
- Base64-encoded keystores

---

## Production Certificate

| Property | Value |
|---|---|
| **Alias** | `fluence-release` |
| **Subject DN** | `CN=Kulkarni, OU=Fluence, O=Fluence, L=Pune, ST=Maharashtra, C=IN` |
| **SHA-256 Fingerprint** | `8955bb6e81047ef452ac68763c47d16916b150a90a743a51ee92ea36b383ca3e` |

If any build produces a different signing certificate:

**STOP.**

Treat it as a release-blocking error. Do not publish.

---

## Release Process

1. Update `versionCode` / `versionName` in `app/build.gradle.kts`.
2. Commit changes.
3. Push to `main`.
4. Create and push an annotated version tag (e.g. `v1.6.1`).
5. GitHub Actions builds the APK.
6. GitHub Actions reconstructs the keystore from GitHub Secrets.
7. GitHub Actions signs the APK.
8. GitHub Actions verifies the APK signature.
9. GitHub Actions generates `release.json`.
10. GitHub Actions publishes the GitHub Release.

Agents must never bypass this pipeline.

---

## Release Verification Checklist

Before considering a release successful, verify:

- [ ] APK signed successfully
- [ ] Production certificate matches expected SHA-256 fingerprint
- [ ] `release.json` generated
- [ ] APK SHA-256 matches `release.json`
- [ ] GitHub Release uploaded successfully
- [ ] No signing errors

---

## Signing Verification

```sh
# Verify APK signature and print certificate info
apksigner verify --print-certs app-release.apk

# Expected output:
#   Signer: certificate DN: CN=Kulkarni, OU=Fluence, O=Fluence, L=Pune, ST=Maharashtra, C=IN
#   Signer: certificate SHA-256 digest: 8955bb6e81047ef452ac68763c47d16916b150a90a743a51ee92ea36b383ca3e

# Verify APK content integrity
sha256sum app-release.apk
# Compare with the sha256 field in release.json
```

---

## Release Troubleshooting

| Symptom | Likely Cause |
|---|---|
| Workflow fails on "Sign Release APK" step | One or more GitHub Secrets are missing or incorrect |
| `keytool` validation fails | `RELEASE_KEYSTORE_BASE64` contains invalid data or password is wrong |
| APK signed with wrong certificate | `RELEASE_KEY_ALIAS` does not match the keystore's alias |
| `release.json` mismatch | APK was replaced after metadata generation |
| GitHub Release not created | `GITHUB_TOKEN` permissions missing `contents: write` |

---

## Things That Must Never Be Changed

- Never generate a new keystore.
- Never rename GitHub Secrets.
- Never sign production APKs manually.
- Never replace the production certificate.
- Never hardcode signing credentials.
- Never ask the user to expose keystore passwords unless absolutely required for recovery.
- Never modify the release workflow unless fixing a verified bug.

### Allowed Changes

- update version numbers
- improve CI reliability
- improve release verification
- improve logging
- improve artifact validation

provided they preserve complete compatibility with the existing signing pipeline.

The signing architecture is considered **frozen**.
