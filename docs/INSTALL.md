# Installing iTVS Connect (sideload)

Google **Play Protect** often blocks APKs that are not from the Play Store, especially the first time. This is expected for an open-source sideload build — the app is not distributed through Play.

## Recommended APK

Use the **release** APK (not `*-debug.apk`):

- GitHub Releases → latest `itvs-connect-*-release.apk`

Package id: `com.itvs.connect`

## If Play Protect blocks install

### Option A — Install anyway (when shown)

1. Tap **More details** / **Learn more** on the Play Protect dialog.
2. Tap **Install anyway** / **Install without scanning**.

### Option B — Temporarily disable Play Protect scan

1. Open the **Play Store** app.
2. Tap your profile photo → **Play Protect**.
3. Tap the gear / **Settings**.
4. Turn **off** **Scan apps with Play Protect**.
5. Install the APK again from Files / Downloads.
6. Turn Play Protect scanning back **on** after install.

### Option C — Allow the installer source

1. Android **Settings → Apps → Special app access → Install unknown apps**.
2. Enable for **Files**, **Chrome**, or whichever app opens the APK.
3. Retry the install.

## After install

1. Open **iTVS Connect**.
2. Allow **Bluetooth**, **Location**, **Notifications**, and **Phone** when asked.
3. Optionally: **Settings → Apps → iTVS Connect → Battery → Unrestricted** (keeps BLE/ride tracking alive).
4. Tap **Scan & pair scooter**.
5. If pairing hangs: **Force stop TVS Connect**, keep scooter cluster ON, enable Location, then scan again and **tap your scooter** in Nearby devices.

## Notification access says “Controlled by Restricted setting”

Common on **Android 13+** for apps installed from an APK (not Play Store). Maps ETA / next-turn needs Notification access — unlock it once:

1. Open **Settings → Apps → iTVS Connect** (or **See all apps** if it is hidden).
2. Tap the **⋮** menu (top-right) → **Allow restricted settings**.
3. Confirm with PIN / fingerprint if asked.
4. Go back to **Settings → Notifications → Notification access** (or open **Notification access** from iTVS Connect → More).
5. Turn **on** **iTVS Connect**.

If **Allow restricted settings** is missing: first try enabling Notification access (so Android shows the block), then return to the app info page and open the ⋮ menu again.

## Pairing tips

- Official TVS Connect must not hold the BLE link — Force stop it (removing the vehicle is optional).
- iTVS Connect no longer filters BLE ads by service UUID (that caused forever-scanning).
- After scan, tap a **Likely scooter** row if auto-connect does not happen.
- Phone **Settings → Location** must be ON for BLE scanning on many Androids.

- Uninstall any older `iTVS Connect` / `*.debug` build first.
- Re-download the **release** APK (not the debug one).
- Reboot once, then retry Option B.
