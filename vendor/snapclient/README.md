# Vendored snapclient binaries

These binaries are extracted from the official Snapdroid APK released by
[badaix](https://github.com/badaix/snapdroid) — the same author as Snapcast.

They are licensed under the **GNU General Public License v3** (GPL-3.0).
Source: https://github.com/badaix/snapdroid

## Files

| File | ABI |
|---|---|
| `snapclient_arm64-v8a` | 64-bit ARM (modern Android phones, Chromecast with Google TV) |
| `snapclient_armeabi-v7a` | 32-bit ARM (older devices) |

## Current version

See `VERSION` for the upstream release this was extracted from.

## Updating

Updates are checked automatically by the `sync_snapdroid.yml` GitHub Actions
workflow (runs weekly). If a new release is found it opens a PR so you can
review before it merges.

To update manually, run `scripts/fetch_snapclient.sh` then commit the result.
