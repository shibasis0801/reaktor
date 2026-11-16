# Reaktor work Coffer is waiting on

Coffer's gap list (`coffer/docs/gaps.md`) has seven features no reaktor module can give it today.
Each was checked against the modules that exist (`reaktor-module` skill: reuse, then extend, then
create), against what it would merge into Coffer's manifest, and against Coffer's promise: no
internet permission, nothing leaves the phone. Surveyed 15 November 2026.

## The short version

| # | Feature | Where it goes | New module? | Size | Waits on |
|---|---|---|---|---|---|
| 1 | Done key in forms; `validate()` naming | `reaktor-ui` forms | no | S | **done, 15 Nov** |
| 2 | Excel export | `reaktor-io` archive: a zip writer and an xlsx writer | no | S | **done, 15 Nov** |
| 3 | App lock | new `reaktor-unlock` | **yes** | S–M | **done, 15 Nov** (iOS not run against Face ID) |
| 4 | Opt-in reminders | `reaktor-notification`, after moving sealed notices out to `reaktor-notification-sealed` | **yes** (a split) | M | **done, 16 Nov** (iOS delivery not seen) |
| 5 | Receipt photos | `reaktor-io`: photo picker, system camera, downscale | no | M | nothing |
| 6 | Home-screen widget | new `reaktor-widget`; an iOS widget extension per app | **yes** | M–L | an Xcode target per app |
| 7 | PDF bank statements | `reaktor-pdf`: passwords, positions, an iOS reader; old Androids in `reaktor-pdf-box` | one optional | L | real statements from the owner |

Suggested order: 1, 2, 3, 4, 5, 6, 7. The first three are small and stand alone; 4 also makes
GymBuddy about 2.6 MB smaller on every phone; 7 is the largest and can't be finished without real statements.

## 1. Forms: a Done key, and two `validate()`s · `reaktor-ui` · S

- `FormTextField` takes no keyboard action, so "Done" can't do anything (Coffer's tag field has
  an Add button for that). Add `onDone: (() -> Unit)? = null`, setting `ImeAction.Done` when given.
- `FormField.validate()` returns `Boolean`; `Form.validate()` returns the first bad field or null.
  Coffer compared the first with null and its budget dialog didn't save for weeks. Add
  `Form.firstInvalid()` and deprecate `Form.validate()` with `ReplaceWith`. One caller in all six
  apps (Coffer's Edit screen).
- Check: the forms tests on JVM and JS; Coffer's tag field adds on Done.

**Done (d8b025ad).** As planned. Coffer's tag, name and budget dialogs use `onDone`. One more fix
came out of using it: `PlatformChoice`'s Material segments now cap their label at 1.3× text, as the
iOS control does (6e91cd39); "Summary" was being cut to "Summ…" at 2×.

## 2. Excel export · `reaktor-io` · S

`reaktor-io` reads `.xlsx` (`Xlsx.firstSheet`, over `Zip` and `Inflate`), but can't write one.

- `ZipWriter`: stored entries (no compression; Excel accepts them) and a CRC-32, in common code.
- `Xlsx.write(sheets)`: text, number and date cells, inline strings, a frozen header row, column
  widths. Dates as Excel serial numbers with a date style, so they sort and add up in Excel.
- Pure common Kotlin: every target, no dependency, no permission.
- Check: write, then read back with `Xlsx.firstSheet` (JVM, JS, iOS simulator); open the file in
  LibreOffice on the JVM test machine if it's there.
- Coffer: "Export as Excel" beside CSV; payee, category and trip spreadsheets offered as `.xlsx`.

**Done (8c9fa6c8, 130cd110).** One change from the plan: stored entries weren't enough. Coffer's
five-year export came to 4.7 MB, so `reaktor-io` gained `Deflate` (one fixed-Huffman block, greedy
back-references, common code) and `ZipWriter` keeps whichever of the deflated and plain bytes is
smaller: 729 KB for the same file. Numbers are written from an unscaled value and a scale, never
through a float; repeated text goes in once (shared strings). Read back by `Zip`/`Xlsx.firstSheet`
on JVM and JS, by the JDK's zlib and zip reader, and opened by macOS's Office importer. Not opened
in Excel itself or in Google Sheets: neither is on the build machine.

## 3. App lock · new `reaktor-unlock` · S–M

Nothing in reaktor asks for the phone's own unlock. The modules near it are wrong homes:
`reaktor-security` builds BoringSSL and MLS++ in C++ for every Android ABI, and `reaktor-auth` is
accounts and sign-in. A new module, because what it needs shouldn't reach apps that don't lock:
`androidx.biometric`, the `USE_BIOMETRIC` permission it merges, and on iOS a Face ID usage line.

- `interface DeviceUnlock { val kind: UnlockKind; suspend fun ask(reason: String): Unlock }`, in a
  slot (`Feature.Unlock`), null where there is none (desktop, browser).
- Android: `androidx.biometric`'s `BiometricPrompt`, allowing the device credential (PIN, pattern,
  password) so a phone without a fingerprint can still lock. The library covers older Androids
  differently from 30+; check on an API 26–29 emulator as well as a current one.
- iOS: `LAContext.evaluatePolicy(.deviceOwnerAuthentication)`; the app adds
  `NSFaceIDUsageDescription` to its Info.plist (the module says so; it can't add it).
- In common: `AppLock`, the policy every app would otherwise rewrite: lock after N minutes in the
  background, a lock screen with one Unlock button, and hiding the app's contents in the app
  switcher (`FLAG_SECURE` on Android; a cover view on iOS).
- Check: unit tests for the policy; on the emulator with a PIN set; on the iOS simulator with
  enrolled Face ID (Features → Face ID → Matching / Non-matching).
- Coffer: Settings → "Lock Coffer". Off by default.

**Done (ad178c2e).** Three changes from the plan:
- **No `androidx.biometric`.** Its prompt needs a `FragmentActivity`; a Compose app's
  `ComponentActivity` isn't one. The module uses the platform's `BiometricPrompt` with the device
  credential allowed on API 29+, and the keyguard's confirm screen on 26 to 28. It adds no library.
- **`AppLock` draws nothing.** A lock screen in the module would have made it a Compose module
  with its own look. It holds the policy (locked at start, again after a minute away, not by
  turning it on, open on a phone with no screen lock) and the app draws its own screen.
- **Android 13+ hides only the app switcher's picture** (`setRecentsScreenshotEnabled`), leaving
  screenshots to the person; below 13 it is `FLAG_SECURE`, which stops both.

Checked: six policy tests on the JVM; on an API 36 emulator with a PIN, from Coffer: turning on
and off, a cold start, backing out of the prompt, 20 seconds away (stays open) and 66 (asks). Not
checked: an API 26 to 29 emulator, a real fingerprint, and the iOS half beyond compiling for device
and simulator (Face ID on the simulator needs a person at its menu).

## 4. Opt-in reminders · `reaktor-notification`, split · M

`reaktor-notification` already schedules local notifications (`scheduleLocal`, exact or windowed,
re-armed after a reboot) on Android and iOS. Coffer would use it as it is, except for one thing:

**It `api`-depends on `reaktor-security`** for sealed notices (end-to-end encrypted previews). That
compiles BoringSSL and MLS++ into every app that only wants a reminder. GymBuddy's release APK
carries `libReaktorSecurity.so` at 2.6–3.0 MB for each of three ABIs today, for rest-timer
notifications. Only BestBuds uses sealed notices (9 files).

- Move `SealedNotice`, `SealedNoticePreviews` and the Android and iOS preview classes into
  `reaktor-notification-sealed`, depending on `reaktor-notification` and `reaktor-security`. This
  is the same split `reaktor-notification-fcm` made for Firebase. `NotificationsClient.sealedPreviews`
  stays as a nullable hook so the interface doesn't change.
- BestBuds adds `reaktor-notification-sealed`. GymBuddy changes nothing and gets smaller: about
  2.6 MB on a phone installing from Play (one ABI), 8 MB in an APK that carries all three.
- Coffer then merges `POST_NOTIFICATIONS` and `RECEIVE_BOOT_COMPLETED`. Neither touches the
  network; the store listing says "can post notifications".
- Exact alarms need `SCHEDULE_EXACT_ALARM` on Android 14 and later. A bill reminder doesn't need
  minute precision, so Coffer uses the windowed kind and asks for nothing more.
- Check: BestBuds, GymBuddy and Coffer build; a sealed-notice test passes from its new module;
  GymBuddy's APK no longer contains `libReaktorSecurity.so`; a Coffer reminder fires on the
  emulator after a reboot.
- Coffer: on a regular payment's page, "Remind me the day before", per payment. Nothing is on
  unless chosen. Coffer's quiet rule (no nagging) holds.

**Done (88283570; BestBuds 4d2157f77; Coffer 631d9d2).** How it came out against the plan:
- **Less moved than planned.** `SealedNotice` stayed: it is the wire shape, has no cryptography in
  it, and the server-side models size and filter by it. Only `SealedNoticePreviews`, its Android
  and iOS stores and its test moved. They kept their package, as the FCM module's files did.
- **The hook changed type, not name.** `NotificationsClient.sealedPreviews` is a new interface,
  `NoticePreviews` (open, hidden, setHidden, clear). BestBuds needed one dependency and one cast
  (where `ChatSecurity` hands over session keys); nothing else in it changed.
- **`reaktor-notification-service` depends on the new module.** The iOS service extension is what
  opens a notice as it arrives, so it still carries `reaktor-security`. An app that wants that
  extension without sealed notices would need it split too; none does today.
- **The new module has a web target**, because BestBuds' common code names `SealedNoticePreviews`
  and compiles to JS. That added two packages to the pnpm workspace and lockfile (`./gradlew pnpmLock`).
- **GymBuddy:** `libReaktorSecurity.so` (2.6, 2.6 and 3.0 MB for arm64, x86 and x86_64) is gone
  from its debug APK. No source change.
- **Timing, measured:** a `NotificationPrecision.Approximate` request becomes an alarm with a
  one-hour window. On an API 36 emulator it had not fired two minutes after its time and had by the
  end of the window. Coffer says "around 9".

Checked: both modules' JVM tests; Android, iOS-simulator and JS compiles of notification, sealed,
service and fcm; BestBuds for Android, JS and iOS with its notification and architecture tests;
GymBuddy's APK; and in Coffer on the emulator the permission prompt, the scheduled alarm, the
notification and a tap on it. Not checked: a reminder arriving on iOS; re-arming after a reboot in
Coffer; BestBuds running on a device with a real sealed notice.

## 5. Receipt photos · `reaktor-io` · M

`reaktor-media` has a photo picker and a camera, but taking it would bring `RECORD_AUDIO` (speech),
CameraX, WorkManager, a PDF viewer and Coil's network loader. Coffer can't explain a microphone,
and has no internet permission. The system's own pickers need no permission at all, so they
belong beside `DocumentPicker` in `reaktor-io`:

- `PhotoPicker`: Android Photo Picker (`PickVisualMedia`), iOS `PHPickerViewController`, a browser
  file input with `accept="image/*"`. No permission on any of them.
- `CameraCapture`: the system camera app (`ACTION_IMAGE_CAPTURE` into `reaktor-io`'s existing
  `FileProvider`; `UIImagePickerController` with `.camera`). No `CAMERA` permission on Android
  while the app doesn't declare one; the app adds `NSCameraUsageDescription` on iOS.
- `downscale(bytes, maxSide, quality)` to a JPEG, so a receipt is ~200 KB rather than 4 MB:
  `Bitmap` on Android, `UIImage` on iOS, ImageIO on the JVM. `reaktor-io` has an Android-only
  `Compressor`; this gives it a common face.
- Then `reaktor-media`'s `GalleryAdapter` uses `PhotoPicker` underneath, so there is one picker.
- Related, outside Coffer's needs: speech (the only reason for `RECORD_AUDIO`) should leave
  `reaktor-media` for a module of its own, as the `reaktor-module` skill already notes.
- Check: pick and capture on the emulator and the iOS simulator; Coffer's merged manifest gains
  no permission.
- Coffer: "Add a photo" on a payment; kept in the ledger, so backups carry it (and grow: the
  backup screen says the size).

## 6. Home-screen widget · new `reaktor-widget` · M–L

Nothing exists. A new module, because Glance is a dependency no other app needs.

- Android: a `GlanceAppWidget` drawing a small snapshot (a title, a figure, two or three lines, one
  or two actions as deep links), refreshed when the app writes a new snapshot. The receiver and the
  `appwidget-provider` XML name the app's own widget, so they stay in the app; the module gives a
  base class and a one-screen guide.
- iOS: WidgetKit is Swift-only and runs as an extension, so each app needs a widget extension
  target in its Xcode project and an App Group. The module writes the snapshot to the group's
  shared defaults from Kotlin and reloads the timeline through a five-line Swift shim; the
  extension's SwiftUI reads the snapshot. `reaktor-cli new` should be able to add the target.
- Check: the widget shows and updates on the emulator and the iOS simulator; tapping "Paid in
  cash" opens Coffer on that screen.
- Coffer: "Spent this month", against last month by the same day, and a "Paid in cash" button.

## 7. PDF bank statements · `reaktor-pdf` · L

Most Indian banks email statements as password-protected PDFs. `reaktor-pdf` reads text from a
PDF, but:

| Gap | Today | Plan |
|---|---|---|
| Passwords | returns null, the same as a broken file | `text(bytes, password)`, and a result that says which: `Text`, `NeedsPassword`, `WrongPassword`, `Unreadable`. The password is typed, used and dropped, never stored |
| Tables | text in each reader's own order | words with their positions on the page, so rows can be rebuilt the same way on every platform |
| iOS | "not written" | PDFKit: `PDFDocument`, `unlock(withPassword:)`, page text and selection bounds. No dependency |
| Android below 15 | null (the platform reader is API 35+; Coffer supports 26+) | `reaktor-pdf-box` with PdfBox-Android, a few MB, so only apps that need old phones pay for it |

Coffer's side is a statement reader per bank (HDFC, SBI, ICICI, Axis, Kotak), the way it has an
alert rule per bank, previewed through the import screen it already has. That part needs a real
statement from each bank, with the numbers blanked: formats from the web would be guesses.

## Not planned here

- Sync between the owner's own devices: gate G1–G4 (`plans/coffer.md` §8).
- Anything that needs the network. Coffer has no internet permission, and none of the above adds
  one; each item's check includes Coffer's merged manifest.
