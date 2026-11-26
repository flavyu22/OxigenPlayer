# OxigenPlayer Pro 2.1 — Ultra Timeline

The bottom progress control is called a **timeline**, **seek bar** or **playback progress bar**.

## Interaction

- Tap anywhere to jump to the corresponding time.
- Drag for precise preview; the player seeks once when the gesture finishes.
- Android TV D-pad left/right seeks in 5-second steps and accelerates on repeated presses.
- Home and End jump to the beginning and end.
- The focus preview shows the target timestamp without flushing the decoder.
- TalkBack/Accessibility exposes a real progress range and supports set-progress actions.

## Performance

- Playback position is sampled at 250 ms only while controls are visible.
- During active scrubbing the local preview is sampled at 33 ms; no decoder seek is sent until commit.
- Buffered position is sampled at 500 ms.
- Track, buffered region, progress, ticks and thumb are drawn in the draw phase with cached geometry.
- Text recomposes only when the displayed second or status changes.
- A 250 ms Media3 seek tolerance balances fast recovery and accurate landing.
- Compact screens omit the secondary buffer label to prevent overlap.

## Files

- `PlayerComponents.kt`: custom bottom bar and drawing/gesture implementation.
- `TimelineMath.kt`: pure clamped timeline calculations.
- `TimelineMathTest.kt`: mapping, invalid input and bounds tests.
- `MainActivity.kt`: one final seek commit.
- `PlayerViewModel.kt`: touch preview and debounced remote seek state.
- `ExoPlayerHelper.kt`: seek increments and tolerance.
