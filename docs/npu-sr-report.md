# NPU super-resolution: Phase 8 R&D gate report (§4, milestone 6)

Status: **report only — do not ship.** Verdict up front: on-device ESRGAN-class
super-resolution fails the ~400 ms/page gate on the reference SoC by one to two orders
of magnitude (§5 below). It stays behind no toggle at all — there is nothing to toggle —
until a distilled model or a new SoC changes the arithmetic. The shipping answer for
low-resolution scans remains milestone 3's Mitchell/Lanczos GPU path.

All version numbers and benchmark figures below were resolved against live sources in
September 2026, not from memory. Links are inline.

---

## 1. Delegate story on the Snapdragon 8+ Gen 1 (SM8475)

- **NNAPI is dead.** Deprecated in Android 15
  ([migration guide](https://developer.android.com/ndk/guides/neuralnetworks/migration-guide),
  updated March 2026); the LiteRT docs mark the NNAPI and Hexagon delegates deprecated and
  unsupported
  ([delegates](https://github.com/google-ai-edge/LiteRT/blob/main/tflite/g3doc/performance/delegates.md)).
  The reference phone runs Android 16, so NNAPI is not a path at all — any prototype that
  touches it is testing an emulator-grade fallback.
- **The NPU path is the LiteRT Qualcomm AI Engine Direct (QNN) delegate.** Google and
  Qualcomm ship it as Maven artifacts (`com.qualcomm.qti:qnn-litert-delegate:2.34.0`,
  November 2025) with the HTP backend, initialised in a few lines
  ([guide](https://developers.google.com/edge/litert/android/npu/qualcomm),
  [announcement](https://developers.googleblog.com/en/unlocking-peak-performance-on-qualcomm-npu-with-litert/)).
  The SM8475's Hexagon HTP is the same IP generation as the SM8450 the delegate lists as
  supported, and Qualcomm AI Hub benchmarks ESRGAN models on "Snapdragon 8 Gen 1 Mobile" —
  delegate compatibility on the reference phone is expected but must be confirmed by the
  harness (§6), not assumed.
- **Fallback order for any prototype:** QNN delegate → GPU delegate → XNNPACK CPU, which
  LiteRT's `CompiledModel` API expresses directly with automatic fallback.

## 2. Licence and EULA: the go/no-go gates

- **QNN SDK (LICENSE.qcom).** Binary redistribution **is** permitted, with conditions that
  fit a Play Store build but must be honoured deliberately: binary-only (no source in the
  APK/AAB), may only run on Qualcomm-chipset devices (fine — the whole app is
  Snapdragon-only), must ship Qualcomm's licence text, no Qualcomm trademarks, and —
  underlined — **no patent grant**; practising Qualcomm patents needs a separate agreement
  with Qualcomm Incorporated
  ([licence](https://github.com/qualcomm-linux/meta-qcom/blob/bbac4fd1/licenses/LICENSE.qcom)).
  The delegate alone does not entangle us. The remaining legal check before any integration
  is whether the HTP backend libraries the delegate loads at runtime (vendor-partition
  `libQnnHtp*`, OEM-variable) are present on every target device — a technical risk with a
  legal aftertaste, owned by the harness.
- **Model weights.** `RealESRGAN_x4plus_anime_6B` and `RealESRGAN_x4plus` ship from
  [xinntao/Real-ESRGAN](https://github.com/xinntao/Real-ESRGAN) under BSD-3-Clause, which
  clears the repo's no-GPL/AGPL rule with room to spare. Qualcomm AI Hub's pre-converted
  `.tflite`/`.so` ESRGAN exports carry Qualcomm's own Hub terms — re-verify the exact grant
  at integration time rather than assuming the xinntao licence travels with the converted
  bytes.

## 3. Candidate models

| Candidate | Architecture | Params | Size | Licence | Suits |
|---|---|---|---|---|---|
| `RealESRGAN_x4plus_anime_6B` ([weights](https://github.com/xinntao/Real-ESRGAN/releases/download/v0.2.2.4/RealESRGAN_x4plus_anime_6B.pth), [notes](https://github.com/xinntao/Real-ESRGAN/blob/master/docs/anime_model.md)) | RRDBNet ×4, 6 blocks, 64 feat | 4.47 M | ~17 MB fp32 `.pth` (~9 MB fp16) | BSD-3-Clause | Manga, manhwa, anime-style — the primary candidate |
| `RealESRGAN_x4plus` ([AI Hub](https://aihub.qualcomm.com/models/real_esrgan_x4plus)) | RRDBNet ×4, 23 blocks | 16.7 M | ~64 MB fp32 | BSD-3-Clause | Western comics (non-anime linework); 4× the compute of the anime model |
| `Real-ESRGAN-General-x4v3` ([Hub](https://huggingface.co/qualcomm/Real-ESRGAN-General-x4v3)) | light ESRGAN derivative, ships as `.tflite` | — | single-digit MB | Hub terms (re-verify) | Fallback if conversion stalls: pre-converted, NPU-proven, weaker on line art |

Conversion path for the primary candidate: `.pth` → ONNX → Qualcomm AI Runtime (QAIRT)
converter → fp16 `.tflite`. Precedent exists — Hub's ESRGAN `.tflite` delegates 69/72 ops
to the NPU — so pixel-shuffle upsampling survives the HTP; the 3 CPU ops are the known
unknown to re-measure on SM8475.

## 4. Why the gate fails: the arithmetic

Qualcomm AI Hub's published NPU figures (TFLITE, FP16, per ~128×128 tile):

| Model | Closest SoC to SM8475 | ms / tile |
|---|---|---|
| Real-ESRGAN-General-x4v3 | Snapdragon 8 Gen 1 | **8.8** (w8a8: 2.7) |
| Real-ESRGAN-x4plus (16.7 M) | QCS8450 proxy (≈ 8 Gen 1 class) | **~130–160** |

A §3 corpus page is 2400×3600. A 4× ESRGAN pass cannot run whole-page (a 9600×14400
output exceeds any mobile memory budget), so it must tile: at 128×128 LR tiles with
overlap, a page needs on the order of **500+ inferences**. Even crediting the anime-6B
model a generous 4× FLOP advantage over x4plus (~30 ms/tile on the reference class):

> 500 tiles × 30 ms ≈ **15 s per page** — 37× over the gate.

The rosiest case — SR a 600×900 downscaled proxy (40 tiles) in w8a8 (~10 ms/tile) —
still lands near a second per page, before tiling seams, I/O and the disk cache are
counted. There is no placement of current ESRGAN-class models that reaches 400 ms/page
on SM8475. Waifu2x-class CNNs (smaller, BSD/MIT lineage) are cheaper but visibly weaker
than ESRGAN on print textures and do not change the order of magnitude.

## 5. Verdict

**Fail. No implementation, no toggle, no APK weight.** Revisit only if one of these
changes: (a) a distilled ≤1 M-param SR model with published NPU numbers under ~2 ms/tile,
(b) a future reference SoC that moves the arithmetic a full order of magnitude, or
(c) product decides chapter-level offline precompute (minutes per chapter, cached to
disk) is an acceptable UX — a product call, not a rendering call.

## 6. Harness plan (runnable when the verdict changes)

A device-side harness, gated on this report's verdict, reusing the existing
`tools/run-benchmark.sh` corpus staging:

1. **Convert**: `.pth` → ONNX → QAIRT fp16 `.tflite` for the primary candidate; record
   which ops fall back to CPU (Hub precedent: ~3).
2. **Microbenchmark** (new `benchmark/` module file, Macrobenchmark-style):
   `Interpreter` + QNN delegate (HTP backend, GPU/XNNPACK fallback), 128×128 RGB tiles
   cut from the Absolute Batman CBR, 20 warmup + 100 timed iterations; report median/p99
   ms per tile, init time, and `dumpsys meminfo` PSS delta (peak RSS is the OOM guard).
3. **Tile-and-stitch check**: SR a full 600×900 proxy with 16 px overlap, blend seams,
   PSNR vs the desktop PyTorch reference — seams are the known ESRGAN-tiling failure.
4. **Extrapolate**: tiles/page at the real page size × p99 ms/tile + stitch + cache
   write. The gate number is that total against 400 ms.
5. **Cache design** (only if the gate ever passes): disk cache keyed by
   (page-content SHA + model id + scale), LRU-capped, precomputed one chapter ahead on
   the decode dispatcher — never on the draw path.
