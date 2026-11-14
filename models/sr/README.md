# Super-resolution models

The reader's optional **High Quality** super-resolution model. The app
downloads these files on request (from this folder at a fixed commit) and
checks their SHA-256; they aren't part of the app.

| File | For | SHA-256 |
| --- | --- | --- |
| `AnimeSRPlus-coreml.tar` | iOS (Core ML package in a tar) | `a2095d57403681abc6a383dba91a8987dcafc30b499de98be1d257a63c83801b` |
| `AnimeSRPlus.param` | Android (ncnn) | `2b8fb6e0ae4d2d85704ca08c119a2f5ea40add4f2ecd512eb7f4cd44b6127ed4` |
| `AnimeSRPlus.bin` | Android (ncnn) | `fe01c269cfd10cdef8e018ab66ebe750cf79c7af4d1f9c16c737e1295229bacc` |

The model is Real-ESRGAN's `realesrgan-x4plus-anime` by Xintao Wang et al.
(BSD-3-Clause, https://github.com/xinntao/Real-ESRGAN), taken in ncnn form
from realesrgan-ncnn-vulkan-20220424. The bundled **Standard** model is
`realesr-animevideov3` (x4) from the same release.

The Core ML packages are converted from the ncnn files with
`tools/sr/ncnn2coreml.py` (input and output 1×3×256×256 → 1×3×1024×1024,
RGB 0–1, float16 weights):

    python tools/sr/ncnn2coreml.py realesrgan-x4plus-anime.param realesrgan-x4plus-anime.bin --coreml AnimeSRPlus.mlpackage --tile 256
