# Speech denoiser model (DeepFilterNet3)

`enc.onnx`, `erb_dec.onnx` and `df_dec.onnx` are the three graphs of DeepFilterNet3, unpacked
unchanged from the upstream ONNX export:

https://github.com/Rikorose/DeepFilterNet/raw/main/models/DeepFilterNet3_onnx.tar.gz

sha256 of the archive: `c94d91f70911001c946e0fabb4aa9adc37045f45a03b56008cb0c8244cb63616`

| file | sha256 |
|---|---|
| `enc.onnx` | `7c5399d3da8a50ebef1c1a0ae421b33376aa5e45d0e92df16da7e83c9c131916` |
| `erb_dec.onnx` | `ab669a1d10afe20911728b33053a452071042317a90581092b325da7b2f9d895` |
| `df_dec.onnx` | `23114ce3b0f6464b763ee62f7bb8aab6b2a129a21eabd5bcfe59413db05f278a` |

The archive's `config.ini` fixes the signal processing the network expects (48 kHz, 960/480
STFT, 32 ERB bands, 96 deep-filter bins, filter order 5 with 2 frames of look-ahead); those
values are constants in `dev.gravaai.media.denoise.DenoiseDsp` and `SpeechDenoiser`.

License: DeepFilterNet is dual-licensed MIT / Apache-2.0 (upstream `LICENSE`, `LICENSE-MIT`,
`LICENSE-APACHE`), copyright Hendrik Schröter. The MIT text is in `LICENSE-MIT`.

Reference: H. Schröter, T. Rosenkranz, A. N. Escalante-B., A. Maier, "DeepFilterNet: Perceptually
Motivated Real-Time Speech Enhancement", INTERSPEECH 2023.

Used by the editor's noise reduction (`dev.gravaai.media.denoise`), run on the bundled ONNX
Runtime. The signal processing around the network is our own Java implementation.
