# Eye-contact models

Bundled models for webcam eye-contact correction (see `ASSETS.md` at the repository root for
the full table). All four run through the bundled ONNX Runtime; the face mesh and the glasses
segmenter on the GPU (WebGPU), the two small eye-warp nets on the CPU, where they are faster on
the RX 560 baseline. `export/export_gaze_models.py` is the one-off script that converted the
glasses segmenter and the eye-warp nets to ONNX.

| File | Bytes | sha256 |
| --- | --- | --- |
| `face_landmarks.onnx` | 4,920,979 | `d16e5a55e6a480284d468ee32469692464049518c311662ab3956681de31e3e9` |
| `glasses_frames.onnx` | 12,866,195 | `ba95d0f5d48679a934e089f07a2b042d6251b7e49cad5af53b3f0b715c8697b4` |
| `eye_warp_L.onnx` | 1,018,224 | `0b5c58e959be39f9f646b14228d47519a9a02d729b630412d21efd117a501f0f` |
| `eye_warp_R.onnx` | 1,018,259 | `93dc83b2aa72af7c2a6f891007665595f943e6f6e497ba868f682c31d7a3fd71` |

## Face mesh

`face_landmarks.onnx` is MediaPipe Face Mesh V2 (478 points with irises), the ONNX conversion
published at [fernandotonon/QtMeshEditor-facemesh-onnx](https://huggingface.co/fernandotonon/QtMeshEditor-facemesh-onnx).
Input 1x256x256x3 RGB 0..1; outputs the landmarks and a face-presence logit. License: Apache-2.0
(Google, MediaPipe). Used by `dev.gravaai.media.gaze.FaceMeshLandmarker`.

## Glasses frames

`glasses_frames.onnx` is the `frames` / `medium` LR-ASPP segmenter of
[mantasu/glasses-detector](https://github.com/mantasu/glasses-detector), exported to ONNX with
its `out` head only (input 1x3x256x256, ImageNet mean/std; output frame logits). License: MIT.
Used by `dev.gravaai.media.gaze.GlassesFrameSegmenter`: every pixel it marks as frame is handed
back untouched, so a glasses frame never bends.

## Eye-warp nets

`eye_warp_L.onnx` and `eye_warp_R.onnx` are the per-eye gaze-redirection nets of
[WangWilly/gaze-correction-cam](https://github.com/WangWilly/gaze-correction-cam), the
implementation of Hsu, Wang and Chiu, _Look at Me! Correcting Eye Gaze in Live Video
Communication_ (ACM TOMM 2019). Re-exported from the TensorFlow checkpoints with only the flow
and light-map outputs (`flow:0`, `lcm:0`); the image warp itself is applied at full resolution
in Java (`dev.gravaai.media.gaze.EyeContactMath`). The repository states no license.
