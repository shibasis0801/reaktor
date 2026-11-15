# Reaktor work Coffer is waiting on

Coffer's gap list (`coffer/docs/gaps.md`) has seven features no reaktor module can give it today.
Each was checked against the modules that exist (`reaktor-module` skill: reuse, then extend, then
create), against what it would merge into Coffer's manifest, and against Coffer's promise: no
internet permission, nothing leaves the phone. Surveyed 15 November 2026.

## The short version

| # | Feature | Where it goes | New module? | Size | Waits on |
|---|---|---|---|---|---|
| 1 | Done key in forms; `validate()` naming | `reaktor-ui` forms | no | S | nothing |
| 2 | Excel export | `reaktor-io` archive: a zip writer and an xlsx writer | no | S | nothing |
| 3 | App lock | new `reaktor-unlock` | **yes** | S–M | nothing |
| 4 | Opt-in reminders | `reaktor-notification`, after moving sealed notices out to `reaktor-notification-sealed` | **yes** (a split) | M | BestBuds moving its dependency |
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

## 2. Excel export · `reaktor-io` · S

`reaktor-io` reads `.xlsx` (`Xlsx.firstSheet`, over `Zip` and `Inflate`), but can't write one.

- `ZipWriter`: stored entries (no compression; Excel accepts them) and a CRC-32, in common code.
- `Xlsx.write(sheets)`: text, number and date cells, inline strings, a frozen header row, column
  widths. Dates as Excel serial numbers with a date style, so they sort and add up in Excel.
- Pure common Kotlin: every target, no dependency, no permission.
- Check: write, then read back with `Xlsx.firstSheet` (JVM, JS, iOS simulator); open the file in
  LibreOffice on the JVM test machine if it's there.
- Coffer: "Export as Excel" beside CSV; payee, category and trip spreadsheets offered as `.xlsx`.

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
