---
name: reaktor-ship
description: Getting a Kotlin Multiplatform app onto the Play Store and App Store — R8 rules, permissions you can defend, signing, screenshot sizes, listing copy limits, and what to verify before submitting. Use when preparing a release, writing store listing copy, configuring minification or signing, answering Play's data safety form or Apple's privacy labels, or handing a test build to someone.
---

# Shipping a KMP app

The build works; that is not the same as being shippable. This is the list of things that fail
between a working app and an accepted listing — most of them silently, and several of them only
in the release build.

Keep a `RELEASE.md` in the repo as you go, and write it so **every claim has a command beside
it**. A listing that describes a build you no longer ship is how apps get pulled. Treat the facts
as verified and any reading of store policy as a starting point to confirm in the console.

## The one that will catch you: R8 and serialization

Minification strips or renames what it cannot see used. `kotlinx.serialization` resolves a
generated serializer reflectively through the class's `Companion`, so a stripped Companion is a
**`SerializationException` at read time, not a build error** — the release build compiles,
installs, runs, and then cannot read data the debug build wrote.

```proguard
-keepattributes *Annotation*, InnerClasses
-if @kotlinx.serialization.Serializable class **
-keepclassmembers class <1> {
    static <1>$Companion Companion;
    static **$* *;
}
-keepclassmembers class **$* extends kotlinx.serialization.KSerializer {
    public static ** INSTANCE;
}
```

Keep line numbers and hide the source file name, so a store crash report points at a real line
without shipping the original filenames:

```proguard
-keepattributes SourceFile, LineNumberTable
-renamesourcefileattribute SourceFile
```

Also keep anything else constructed by name or by reflection — Health Connect record classes are
one example.

## The other one: autoWire matches on a property *name*

`autoWire()` pairs a consumer with a provider by type **and by the name of the property holding
it**. R8 renames fields, so in a minified build the names stop agreeing and `requireFullyWired()`
throws — on the first launch of the release build and nowhere else, as a bare
`IllegalStateException` with an obfuscated stack. Nothing about the debug build predicts it.

```proguard
-keepclassmembers class * extends dev.shibasis.reaktor.graph.core.node.BasicNode {
    <fields>;
}
-keepclassmembers class * extends dev.shibasis.reaktor.graph.core.node.ComposeNode {
    <fields>;
}
```

Ktor resolves its engines by service loader and by name, and wants its own:

```proguard
-keep class io.ktor.client.engine.okhttp.** { *; }
-keepclassmembers class io.ktor.** { volatile <fields>; }
```

Then `-dontwarn` the optional back ends your libraries reference and you do not ship
(`org.slf4j.**`, `java.lang.management.**`, `javax.naming.**`, `kotlinx.coroutines.debug.**`), or
R8 fails the build on classes nothing ever calls.

**Reading a minified crash.** The stack names classes like `e2.c`, which tells you nothing until
you map it back. `mapping.txt` is written beside the APK and lists the obfuscated name on the
right of each arrow, so the lookup is a grep — no extra tooling, and it works from a logcat line
pasted out of a tester's phone:

```bash
M=app/build/outputs/mapping/release/mapping.txt
# Class lines start at column 0 and end in a colon; members are indented under them.
grep -n " -> e2\.c:$" "$M"
# Then read that class's block to find the method the frame named (say `d`):
awk '/ -> e2\.c:$/{on=1} on&&/^[^ ]/&&!/ -> e2\.c:$/{exit} on' "$M" | grep -- "-> d$"
```

Keep that `mapping.txt` with the build that produced it. Regenerating it later gives different
names, and a crash report you cannot read is a crash you cannot fix.

**Proving any of this works takes more than launching the app.** A serializer that has been
stripped only fails when something is *read back*. Install the release build, write data,
force-stop, relaunch, and confirm the data is still there.

## Permissions you can defend

List every permission in the manifest and, beside each, the feature that needs it and the screen
that asks. A permission you cannot justify in one sentence is one to remove, because a reviewer
will ask and a user will read it on the listing.

```bash
$ANDROID_HOME/build-tools/<version>/aapt2 dump badging app-release.apk | head -2
$ANDROID_HOME/build-tools/<version>/aapt2 dump permissions app-release.apk
```

Watch for permissions arriving through **manifest merge** from a library you added for something
else — this is why a push-notification transport belongs in a separate module from local
notifications. Check the merged manifest, not the one you wrote:

```
app/build/outputs/logs/manifest-merger-release-report.txt
```

A module earns its permissions for the app that uses *all* of it. `reaktor-notification` declares
`RECEIVE_BOOT_COMPLETED` and a boot receiver so scheduled notifications survive a restart; an app
that schedules nothing inherits both and has to explain them to a reviewer anyway. Subtract them in
the app's own manifest — the same works for a `<receiver>`, `<service>` or `<provider>`:

```xml
<manifest xmlns:tools="http://schemas.android.com/tools">
    <uses-permission
        android:name="android.permission.RECEIVE_BOOT_COMPLETED"
        tools:node="remove" />

    <application>
        <receiver
            android:name="dev.shibasis.reaktor.notification.ReaktorNotificationBootReceiver"
            tools:node="remove" />
    </application>
</manifest>
```

Remove the component as well as the permission. A receiver left behind without the permission that
feeds it is dead weight the merger still registers.

Two designs that remove a permission rather than justify it:

- An **ongoing notification** instead of a foreground service, for a live session. The system
  draws a countdown from `setUsesChronometer` + `setWhen`, so nothing needs to keep running, the
  notification survives process death, and the foreground-service permission and its Play
  declaration both disappear.
- A **photo picker** (`PickVisualMedia`) instead of gallery access. No storage permission at all.

## The data safety form is a document, not a checkbox

Play's data safety section and Apple's privacy labels are enforceable statements. Answer them from
what the build actually does, and record the answers in `RELEASE.md` so the next release does not
re-guess.

- "Collected" means leaves the device. An app with no back end collects nothing — but say so
  precisely, and name what it stores locally.
- **Photos and health data are the questions to read carefully.** Taking a photo the app keeps in
  its own private storage is not collection; both stores still ask about it, and both ask a
  separate question about whether it is shared.
- Health data has extra process on both sides: Google wants a **Health Connect declaration**,
  reviewed more closely than an ordinary submission, and Apple wants App Review notes explaining
  the HealthKit usage. Start these early — they gate a release rather than delaying it.

## Signing

Generate the upload key yourself and keep the password out of the repository:

```bash
keytool -genkeypair -v -keystore ~/<app>-upload.jks -keyalg RSA -keysize 4096 \
  -validity 10000 -alias <app>
```

Read it from a gitignored properties file with environment-variable fallback for CI, and let the
build produce an **unsigned** release when neither exists — useful for size checks, and it fails
loudly rather than silently signing with the wrong key.

Losing this key means never updating the listing again under the same id; Play's key reset is a
support request, not a setting.

Derive `versionCode` from `versionName` rather than maintaining both. Play rejects a versionCode
it has seen before, and a hand-maintained second number is exactly how that happens.

## Handing a build to a tester

**Android:** a release build signed with the debug key installs on anyone's phone today, no
account needed. Firebase App Distribution takes the APK and mails a link; it needs no Firebase
SDK in the app, and adding the App Distribution SDK would pull in the INTERNET permission.

State the cost up front: **Android refuses an update signed by a different key**, so every tester
must uninstall before installing the store build, and uninstalling takes their data with it.

**iOS:** there is no debug-key equivalent. Ad-hoc, App Distribution and TestFlight all sign with
a certificate only the paid Apple Developer Program issues. Apple does not care *whose* membership
it is, so a colleague's team works — on a borrowed team prefer TestFlight, which needs no device
UDIDs, over an ad-hoc IPA that carries its tester list inside it against a 100-device ceiling.

Registering an app on someone else's team claims the bundle ID globally; they must release it
before it can be registered elsewhere. Free while nothing has been published, but not skippable.

## Store assets

Sizes are enforced on upload and the rules are not symmetrical.

| | Constraint |
|---|---|
| Play screenshots | Aspect ratio **must not exceed 2:1**. A raw 1080×2400 capture is 2.22:1 and is rejected — compose it into a 9:16 frame rather than uploading the capture. |
| Play feature graphic | Exactly 1024×500. |
| App Store 6.9" | Exactly 1320×2868. Capture on a device that size so nothing is scaled. |

Generate framed screenshots with a script from raw captures kept beside them, so a re-run costs
nothing and never needs the device again. Trim the simulator's black letterbox before framing.

Capture with a clean status bar:

```bash
adb shell settings put global sysui_demo_allowed 1
adb shell am broadcast -a com.android.systemui.demo -e command enter
adb shell am broadcast -a com.android.systemui.demo -e command clock -e hhmm 0930
adb shell am broadcast -a com.android.systemui.demo -e command notifications -e visible false

xcrun simctl status_bar <udid> override --time "9:41" --batteryState charged
```

Shoot a seeded month of real-looking use. An empty app makes a weak listing.

Keep the app's name out of the screenshots themselves — put it only on the feature graphic, so a
rename costs one re-run instead of a reshoot.

## Listing copy, counted not guessed

Neither store warns before it truncates, and a description cut at 4000 characters is one whose
last paragraph nobody reads. Keep the copy in the repo and check it with a script.

| Field | Limit |
|---|---|
| Play app name / Apple name | 30 |
| Play short description | 80 |
| Play full description | 4000 |
| Apple subtitle | 30 |
| Apple keywords | 100, comma separated, **no spaces after commas** |
| Apple promotional text | 170, changes without review |
| Apple description | 4000 |

Play indexes the full description for search and shows roughly three lines before "read more", so
the opening must work as the whole pitch. Apple does not index its description at all — search
comes from name, subtitle and keywords — so write Apple's to be read and put the keywords in the
keyword field. Do not repeat words there that already appear in the name or subtitle; they are
indexed anyway.

Lead with what the app refuses to do, if that is the honest differentiator. An absence is easier
to believe and harder to copy than a feature list.

## Verify on hardware, and write down what you verified

Keep a "what has actually been run" section in `RELEASE.md`. Not a substitute for the checks
above — a record, so the next release knows what was covered and what was assumed.

The checks worth running at least once per app:

- Fresh install from wiped state, on a device and on a simulator.
- **Fresh install with no network, then use it.** First launch offline is a state every app has
  and few are run in. Cairn's sealed with a key it is only given on reaching the server; the send
  button closed the app.
- Release build with minification, installed over real data, then **read that data back**.
- Backup exported, install wiped, backup restored — hash-compare every document before and after.
  If the app deliberately has no backup (keys in the database, `allowBackup="false"`), write that
  down with the reason and say what stands in for one, rather than leaving the line unticked.
- A deliberately corrupted database file on every platform — see below; "recovers" is two things.
- System font at 1.5× and 2.0×: `adb shell settings put system font_scale 2.0`, relaunch, and put
  it back afterwards.
- **For each kind of thing the app can receive, do what a person would do next with it.** Cairn
  moved files between devices correctly for months and had no way to open or save one: text and
  links had *copy*, and a file had its name. Every transfer test passed, because each ended when
  the bytes arrived. And compose something of every kind from *inside* the app — if the only
  composer is a text field, the app sends text, whatever its share sheet accepts.
- **Every shell, opened and used.** The web shell draws with the one font it ships. Anything typed
  outside Latin-1 — `←`, `▸`, `✕`, `☐` — is a missing-glyph box there and fine everywhere else,
  so a back button can be an empty rectangle on one target of three. Draw such marks instead of
  typing them, and size them in `sp` so they grow with the text.
- Every permission-gated flow, on the release build, with the permission granted and refused.
- Cold start time, recorded: `adb shell am start -W -n <pkg>/<activity>` after a `force-stop`,
  several times, and say what it was measured on. The first launch after an install is its own
  number.

### A corrupted database: survive it, then explain it

Overwrite the file with random bytes and relaunch. On Android that needs `run-as`, so a **debug**
build: `adb shell "run-as <pkg> sh -c 'head -c 16384 /dev/urandom > databases/reaktor.db'"`.

What the platforms do is not the same, and until Cairn checked, one of them did nothing:

| Platform | What happens to an unreadable file |
| --- | --- |
| Android | SQLite's default handler deletes it and opens a new one. Silently |
| iOS | `DarwinSqlAdapter` does the same by hand |
| JVM desktop | `createSqliteObjectDatabase` moves it aside as `name.db.unreadable` and opens a new one. Before that existed it threw from the factory on every launch — and a desktop opens storage before it opens a window |

Launching is half of it. If identity lived in that file the app comes back as a stranger, which
from the inside looks exactly like a first install. Take the callback — `onUnreadable` on both
`createSqliteObjectDatabase` and `AndroidSqlAdapter` — and tell the person what happened and what
gets their data back. Persist that notice until it is dismissed: on Android a permission prompt is
covering the screen on exactly that launch, and the process may not outlive it.

Only real damage should trigger recovery. A locked file (`SQLITE_BUSY`, a second copy of the app)
or an unreadable one says nothing about the bytes, and setting a healthy database aside is the
loss the mechanism exists to prevent. Test that case too.

### Look at every screen, including the ones with nothing wrong

A green suite does not mean the screen is usable. Cairn shipped a library whose second and third
tabs were a few pixels wide — labels wrapped one letter per line down the edge — on every device,
with every test passing, because a label that narrow is still "displayed".

Compose can draw the real composition into a bitmap with no device and no display:

```kotlin
val scene = ImageComposeScene(width, height, Density(2f, fontScale = 2f)) {
    AppTheme { GraphApplication(graph) }
}
scene.render(0)                       // composes
val image = scene.render(16_000_000)  // state collected from flows lands on the frame after
File("build/renders/library-2x.png").writeBytes(image.encodeToData(EncodedImageFormat.PNG)!!.bytes)
```

It needs `compose.desktop.currentOs` on the JVM test classpath (tests only — a library should not
pick a rendering backend for whoever links it). Build the graph the way a shell does, put content
in it, set each surface, and write one picture per surface at 1× and 2×. Then read them. In one
sitting that found the tabs, a notice that gave its paragraph six tenths of the width, a crash on
first launch offline, and an empty state whose only way forward was drawn only when it was not
empty.

Two assertions are worth keeping beside the pictures, because pictures do not fail a build:

- **Wider than tall.** For a one-line label, `size.width > size.height`. A wrapped label fails it
  and `assertIsDisplayed` does not.
- **Click through from the empty state.** Start with nothing, tap what the empty state offers, and
  assert where you land. Entry points that live under a list do not exist when the list is empty.

This is the shared layout, not Android's font metrics. It replaces staring at a phone for most of
it and not for the last check.

### Things that are right for development and wrong in a release

Both of these were in Cairn's release build and in nobody's notes:

- **`android:usesCleartextTraffic="true"`**, added so the emulator can reach a dev server over
  http. It is in the main manifest, so it is in the release too, and a bearer token then crosses
  any wifi in the clear. Use `android:networkSecurityConfig` instead: the file in `src/main`
  refuses cleartext except to `10.0.2.2`, `localhost` and `127.0.0.1`, and a file of the same name
  in `src/debug/res/xml` permits it for LAN testing. OkHttp enforces it. Anything the user can
  type an address into should ask `NetworkSecurityPolicy.isCleartextTrafficPermitted(host)` first
  and refuse with a sentence — otherwise the refusal looks like the site being down, for ever.
- **The server address.** A constant like `10.0.2.2:8787` survives into a release that can then
  reach nothing on a real phone. Check what the release build is pointed at, in the built APK's
  behaviour rather than in the source.

To prove a policy rather than read it, put a plain listener on the host port
(`python3 -m http.server 8787 --bind 127.0.0.1`) and watch the release build's requests arrive.
"Unreachable" in the UI proves nothing: an emulator without DNS produces the same word.

Drive the UI by reading the accessibility tree rather than tapping guessed coordinates —
`adb shell uiautomator dump` gives element bounds, and taps at remembered pixel positions land on
the wrong control the moment a layout shifts.
