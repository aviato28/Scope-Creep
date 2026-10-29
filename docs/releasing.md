# Releasing

1. Make sure `main` is green. Do not bump any version in the code: the tag is the version.
2. Tag and push:
   ```
   git tag v0.3.0 && git push origin v0.3.0
   ```
   Tags containing `-beta` (for example `v0.4.0-beta.1`) become GitHub pre-releases, served only to the in-app "Beta channel".
3. `.github/workflows/release.yml` runs the sim-core tests, builds and signs `assembleDirectRelease` (versionName = tag without `v`, versionCode = run number + 100), writes `checksums.txt` and publishes the GitHub Release with `scope-creep-<version>.apk` and `checksums.txt`.

## Required repository secrets

| Secret | Content |
|---|---|
| `SIGNING_KEYSTORE_B64` | the release keystore, base64 (`base64 -w0 release.jks`) |
| `SIGNING_STORE_PASSWORD` | keystore password |
| `SIGNING_KEY_ALIAS` | key alias |
| `SIGNING_KEY_PASSWORD` | key password |

The workflow fails early with a clear message if any is missing. Never commit the keystore.

## The key must never change

Android only installs an update signed with the same key as the installed app. Lose or replace the key and every existing user must uninstall and reinstall. Keep an offline backup of the keystore and its passwords.

## First-time install

The release-signed APK cannot update over the debug or preview builds (different signing key, and preview also has a different application ID). Install the first release fresh (uninstall a debug build if needed); from then on the in-app updater (About, Check for updates) handles upgrades. Local `assembleDirectRelease` without the secrets falls back to the debug key.

## Releasing without pushing a tag
Change the `VERSION` file (e.g. `0.3.1`) in a pull request and merge it into `main`: the Release workflow creates tag `v0.3.1` and the GitHub Release itself. A version that has already been released fails the run, so always bump it. Append `-beta.1` (e.g. `0.4.0-beta.1`) for a pre-release that only beta-channel users see.
