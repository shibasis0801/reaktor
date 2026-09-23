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
one example — and reaktor's slot machinery, which is looked up by type.

**Proving it works takes more than launching the app.** A serializer that has been stripped only
fails when something is *read back*. Install the release build, write data, force-stop, relaunch,
and confirm the data is still there.

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
- Release build with minification, installed over real data, then **read that data back**.
- Backup exported, install wiped, backup restored — hash-compare every document before and after.
- A deliberately corrupted database file on both platforms. Both should recover rather than
  throwing on every launch forever.
- System font at 1.5× and 2.0×.
- Every permission-gated flow, on the release build, with the permission granted and refused.
- Cold start time, recorded.

Drive the UI by reading the accessibility tree rather than tapping guessed coordinates —
`adb shell uiautomator dump` gives element bounds, and taps at remembered pixel positions land on
the wrong control the moment a layout shifts.
