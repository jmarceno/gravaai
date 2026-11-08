# Detection models

Bundled models for auto masking (see `ASSETS.md` at the repository root for the full table).

## YuNet

`face_detection_yunet_2023mar.onnx` is OpenCV Zoo's YuNet face detector
([opencv_zoo/models/face_detection_yunet](https://github.com/opencv/opencv_zoo/tree/main/models/face_detection_yunet)),
the 2023-03 export used by `cv::FaceDetectorYN`.

sha256: `8f2383e4dd3cfbb4553ea8718107fc0423210dc964f9f4280604804ed2552fa4`

License: MIT, Copyright (c) 2020 Shiqi Yu (see `LICENSE` in this directory).

Used by `dev.gravaai.media.detection.FaceDetector`: faces detected in sampled frames become timed
blur annotations. Paper: _YuNet: A Tiny Millisecond-level Face Detector_
(https://arxiv.org/abs/2211.15214).

## PP-OCRv4 mobile

`ch_PP-OCRv4_det_infer.onnx` and `ch_PP-OCRv4_rec_infer.onnx` are PaddleOCR's PP-OCRv4 mobile
detection and recognition models, taken from RapidOCR's mirror of the PaddleOCR model zoo
([SWHL/RapidOCR](https://huggingface.co/SWHL/RapidOCR)).

| File | Bytes | sha256 |
| --- | --- | --- |
| `ch_PP-OCRv4_det_infer.onnx` | 4,745,517 | `d2a7720d45a54257208b1e13e36a8479894cb74155a5efe29462512d42f49da9` |
| `ch_PP-OCRv4_rec_infer.onnx` | 10,857,958 | `48fc40f24f6d2a207a2b1091d3437eb3cc3eb6b676dc3ef9c37384005483683b` |

License: Apache-2.0 (see `PP-OCR-LICENSE` in this directory).

Used by `dev.gravaai.media.privacy.OcrEngine`: detection finds text lines, recognition reads
them, and the recognizer's own `character` metadata supplies the CTC alphabet (so no dictionary
file is shipped). Recognised lines go through the PII detector before becoming blur regions.
