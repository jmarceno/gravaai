# RNNoise model

`voice.rnnn` is `cb.rnnn` ("conjoined-burgers", 2018-08-28) from
https://github.com/GregorR/rnnoise-models, renamed so the code can use a stable name.
Its training notes are in `conjoined-burgers-info.txt`.

sha256: `f1357c4e5be9dee8467bead486dfced2d75b640c26ad0b594fa7f102322371d9`

License: the upstream README states that, apart from its `tools/` directory and README,
none of the repository's contents are creative works, so the models are not subject to copyright.

Used by ffmpeg's `arnndn` filter for the Balanced and Strong microphone noise reduction levels
(`electron/ipc/recording/micNoiseReduction.ts`).
