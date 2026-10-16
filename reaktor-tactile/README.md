# reaktor-tactile

> **Stability: Early** - One small API, used by BestBuds.

`reaktor-tactile` plays haptic feedback through the platform's own haptics.

## What it has

- `Haptics` feature with `play(HapticPattern)`.
- Patterns: `Tap`, `Selection`, `Threshold`, `Success`, `Warning`, `Alert`, `Alarm`.
- Android: plays through the window's `performHapticFeedback`, so the user's touch-feedback setting is respected. `Alarm`, or no window, falls back to the vibrator.
- iOS: `UISelectionFeedbackGenerator` for `Selection`, a rigid impact for `Threshold`, and `UINotificationFeedbackGenerator` for success, warning and error.

## Use

```kotlin
Feature.Haptics?.play(HapticPattern.Selection)
```

Surface components send `FeedbackCue`s through `LocalCuePlayer`; an app maps those cues to patterns once, at its theme root.
