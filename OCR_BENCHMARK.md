# OCR benchmark

Date: 2026-10-17  
Device: vivo V2055A, Android 13  
Input: real WeChat screenshots captured through Shizuku, 1080x2400

## Candidates

- ML Kit Chinese text recognition `16.0.1`, bundled offline model.
- RapidOCR Android ONNX `1.3.0` from RapidAI, PP-OCRv3 models.
- Python RapidOCR 3.10.0 with PP-OCRv6 models as the reference.

## Results

First screenshot:

- ML Kit: 16 rows, 474 ms.
- RapidOCR Python reference: 20 rows.
- Exact text matches: 11.
- Mean matched-line offsets: 8.7 px vertically, 9.0 px horizontally.

Known-message screenshot:

- ML Kit: 25 rows, 398 ms.
- RapidOCR Android, cold engine: 29 rows, 3934 ms.
- RapidOCR Android, warm singleton: 29 rows, 2718 ms.
- Python reference: 30 rows.
- Exact text matches: 15.

All three known messages were correct in RapidOCR Android:

```text
今天下午三点开会记得带身份证和笔记本电脑还有充电器别忘了
没有测试，我还没开，卢浮宫的浮宫
行，那就这么定了，晚点见
```

ML Kit misread the second message as `没有测測试`, duplicating a character. It
also produced other traditional/simplified substitutions and missed several UI
rows. This is not acceptable for history, identity guardrails, or deduplication.

## Decision

Use RapidOCR Android ONNX for the read-screen port.

RapidOCR is slower and adds about 39 MB of AAR data plus native libraries, but
its text accuracy is materially closer to the behavior specification. The
engine is cached as a singleton; first recognition pays model initialization,
then warm calls are about 2.7 seconds on this phone. After removing ML Kit and
limiting the APK to arm64, the final RapidOCR-only APK is 32.1 MB. A cold
recognition in that final package took 3327 ms.

The Android AAR currently uses PP-OCRv3 while the Python reference uses v6.
Revisit the model bundle if v6-compatible Android assets become available.
