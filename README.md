# KidLock

![KidLock](docs/banner.svg)

A small Device Owner app for a child's phone. It does two things:

1. **Keeps airplane mode locked ON** so the child cannot turn the radios back on.
2. **Pins approved apps** (YouTube Kids, Netflix, Prime Video, Disney+) in lock task mode, so the child cannot leave the app. A PIN-protected **Done** button ends the session.

Kotlin, standard Views, `androidx.appcompat` only. No network, no analytics. minSdk 26, targetSdk 34.

## Build

```bash
./gradlew assembleDebug
```

The APK lands at `app/build/outputs/apk/debug/app-debug.apk`. Android Studio can also open the folder directly.

## Setup

Device Owner can only be set on a phone with **no accounts** on it. Either factory reset and skip Google sign-in, or remove every account under Settings > Accounts before step 2. You can add accounts back (for example inside YouTube Kids) afterwards.

1. Enable Developer options and USB debugging on the phone, then install the APK:
   ```bash
   adb install app/build/outputs/apk/debug/app-debug.apk
   ```
2. Make KidLock the Device Owner:
   ```bash
   adb shell dpm set-device-owner com.quasslabs.kidlock/.AdminReceiver
   ```
3. Turn airplane mode **ON** from quick settings. KidLock cannot switch it on by itself (Android does not let third-party apps change that setting), it can only lock the toggle in whatever position it is in. The app refuses to lock it while it is off.
4. Open KidLock, set a PIN, turn on **Lock airplane mode**, check the allowed apps, and tap **Apply**.
5. Pull the SIM card.
6. Optionally turn USB debugging back off, so the phone cannot be reconfigured over ADB.
7. Hand it to your child.

## Daily use

- Open KidLock: it shows a button for each allowed app that is installed.
- Tapping one starts a locked session. Home and Recents do nothing; the child stays in that app.
- Backing out of the app lands on the KidLock lock screen, with **Back to app** and **Done ✓**.
- **Done ✓** asks for the PIN, then ends the session.
- **Parent settings** on the main screen also asks for the PIN.

## How it works

| Piece | What it does |
|---|---|
| `AdminReceiver` | The `DeviceAdminReceiver` that becomes Device Owner. No logic. |
| `KidLockApp.kt` | Application class plus the `KidLock` helper: the app catalog, policy calls, and salted SHA-256 PIN storage in SharedPreferences. |
| `MainActivity` | Shows the ADB command if not Device Owner, otherwise the kid launcher or the PIN-gated parent settings. |
| `LockActivity` | Root of every locked session. Declared `lockTaskMode="if_whitelisted"` and `singleInstance`, so it enters lock task mode on launch, then starts the chosen app on top of itself. |
| `BootReceiver` | Re-applies the saved policy after a reboot. Device Owner policy already persists; this is a safety net. |

Policy calls:

- Airplane lock: `addUserRestriction(admin, UserManager.DISALLOW_AIRPLANE_MODE)`
- App allowlist: `setLockTaskPackages(admin, selected + KidLock's own package)`

## Limitations worth knowing

- **Android 9+ for the airplane lock.** `DISALLOW_AIRPLANE_MODE` was added in API 28. On Android 8.x the app still installs and app pinning still works, but the toggle stays disabled.
- **Emergency calls.** Android lets the emergency dialer bypass airplane mode on many devices, and emergency calls work without a SIM. Neither KidLock nor any other third-party app can disable that.
- **Outside a session the phone is a normal phone.** Lock task only applies while an app is pinned. The airplane restriction is always on, but the child can open other installed apps from the home screen. Uninstall or disable apps you do not want reachable.
- **No floating overlay.** The Done button lives on the KidLock lock screen behind the pinned app, reached with Back, instead of floating over it. A floating button would need the "Display over other apps" permission plus a foreground service, which Device Owner cannot grant automatically.
- **Adding apps.** The catalog is hardcoded in `KidLock.CATALOG` in `KidLockApp.kt`. A new entry also needs a `<package>` line in the manifest's `<queries>` block so Android 11+ lets KidLock see it.

## Removing Device Owner

A Device Owner app cannot be uninstalled until it gives up Device Owner.

1. Open KidLock, enter **Parent settings**, tap **Remove Device Owner**. This clears the airplane lock and the lock task allowlist, then calls `clearDeviceOwnerApp`.
2. Uninstall normally, or with:
   ```bash
   adb uninstall com.quasslabs.kidlock
   ```

`adb shell dpm remove-active-admin com.quasslabs.kidlock/.AdminReceiver` also works for clearing the remaining admin, but on most builds it refuses to remove a Device Owner unless the APK was installed as test-only. Use the in-app button first.

If you lose the PIN: factory reset the phone.

## License

MIT. See [LICENSE](LICENSE).
