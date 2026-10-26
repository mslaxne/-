# Read-screen port: first slice

The phone-independent parts of `src/adapters/android.py` now have Kotlin
equivalents under `app/src/main/java/com/local/wxbotprobe/chat/`.

## Ported

- Noise regexes, minimum OCR score, two-character minimum and chat-area bounds.
- Grey-row separation through an injected ink test.
- Wrapped-line merge: same side, 90 px gap, and 25 px same-line exception.
- Side attribution from the left margin only (`<= 0.30 * width`).
- Incoming/outgoing stub removal at `0.14` and `0.70` screen width.
- Group detection from a grey nickname row within 130 px and 100 px alignment.
- Seen identity `(normalised text, occurrence)` with a 120-entry window.
- Fuzzy long-text matching at 20 characters and 0.85 ratio, plus substring tails.
- Self-authored tail suppression.
- Reply split with a three-part cap and lossless tail merge.
- Bitmap ink sampling, bubble fill classification, image-block rejection,
  green-send-button detection and input-field location.
- `ScreenSnapshotBuilder` now combines OCR, text rules and pixel rules.
- Three-second `BurstCollector` resets its deadline when another message arrives.

## Regression tests

`gradle :app:testDebugUnitTest` currently runs:

- `ScreenReadingRulesTest`: 8 tests.
- `IncomingTrackerTest`: 7 tests.
- `ReplySplitterTest`: 3 tests.
- `ScreenPixelRulesTest`: 6 tests.
- `BurstCollectorTest`: 2 tests.

All 26 pass. Lint reports no issues.

## Live snapshot check

On the real 1080x2400 Shizuku screenshot with three known test messages:

- OCR returned 29 rows in 3760 ms including snapshot processing.
- The parser produced 9 ordered bubbles, including all three known messages.
- The 100-character test message merged into one bubble.
- Input field located at `(540, 2292)`; no green send button existed because
  the input box was empty.

Two residual behaviors come from the frozen Python specification and were not
"optimized" ad hoc:

- A final OCR row of one character is dropped by the two-character minimum, so
  the wrapped tail `宫` is not appended to its preceding bubble.
- A forwarded chat-record card can resemble a grey nickname over an incoming
  bubble and falsely set `isGroup=true`. The test File Transfer Assistant chat
  contains such a card above the new messages.

## Not yet ported

- OCR execution and OCR engine selection.
- Pixel picture-region scanning for stickers and photos without OCR.
- Scroll-shift/template matching.
- Foreground bot loop, model request, history and send retries.

The next platform decision is OCR. The engine must be benchmarked on real
Shizuku screenshots before the pure rules are connected to production reading.
Do not substitute a different row shape without replaying these tests.
