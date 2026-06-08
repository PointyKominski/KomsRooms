# Stable (pinned) snapclient binaries

This folder holds the **last known-good** snapclient binaries.
It does NOT update automatically — it only changes when you deliberately run:

    scripts/promote_stable.sh

That script copies the current vendor/snapclient/ binaries here and commits them.
Useful if the sync workflow pulled in an upstream update that broke something.

## How to roll back in the app

1. Open KomsRooms on your device
2. On the Connect screen, tap the small version label **7 times**
3. The developer panel appears — tap **"Switch to stable"**
4. Reconnect — the app will use the binaries from this folder

## Current pinned version

See `VERSION` for details.
