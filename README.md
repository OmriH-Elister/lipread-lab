# LipRead Lab

Android research prototype for visual-only speech recognition. It records a short clip or opens a video, creates a local video-only MP4 with the audio track removed, uploads that silent file to an HTTPS backend, displays the returned transcript, and can synthesize/play or save a voice rendition with Android Text-to-Speech.

This is not a generic or accessibility-grade lip reader. Visemes are ambiguous and the language model can produce plausible but wrong words. Never use its output as evidence, a verbatim record, or a substitute for captions/interpreters.

## Android build

Requirements are already present on the target ARM64 environment. Run:

    chmod +x test.sh build.sh
    ./test.sh
    ./build.sh

APK output:

    build/apk/LipReadLab.apk

The app requires only Internet access. It uses the system camera through a temporary grant to an app-internal cache destination and uses the Storage Access Framework for user-selected files, so it requests neither camera nor storage permission. The camera app may capture audio into the internal original recording; LipRead Lab removes the audio track before uploading and deletes obsolete captures.

## Backend contract

Set the app endpoint to an HTTPS URL accepting multipart field `video` and returning one top-level JSON object:

    {"transcript":"recognized words","confidence":0.72}

`transcript` is required and must be a JSON string. `confidence` is optional, must be a finite JSON number from 0 through 1, and should only be supplied if the backend has a meaningful calibrated value. Duplicate keys, trailing data, malformed JSON, and invalid field types are rejected.

The Android endpoint settings include an optional bearer token. When set, the app sends `Authorization: Bearer <token>`. The token field is masked and deliberately not persisted by the app. The supplied backend enables this check when `LIPREAD_BEARER_TOKEN` is non-empty; when the variable is unset or empty, `/transcribe` does not require authorization. `/health` remains unauthenticated for service monitoring. Put both routes behind HTTPS and avoid placing tokens in URLs or logs.

## Auto-AVSR backend

`backend/server.py` integrates the official Apache-2.0 Auto-AVSR inference pipeline with MediaPipe face landmarks. It is intended for an x86-64 Linux NVIDIA server, not the phone.

1. Clone the official repository:

       git clone https://github.com/mpc001/auto_avsr.git

2. Create a Python environment and install Auto-AVSR's dependencies plus `backend/requirements.txt`. Install the system `ffmpeg` binary.
3. Download the public visual-only checkpoint `vsr_trlrs2lrs3vox2avsp_base.pth` from the model-zoo link in the Auto-AVSR README. The file is roughly 955 MB.
4. Start the backend from this project:

       export AUTO_AVSR_ROOT=/absolute/path/to/auto_avsr
       export AUTO_AVSR_CHECKPOINT=/absolute/path/to/vsr_trlrs2lrs3vox2avsp_base.pth
       export LIPREAD_BEARER_TOKEN='replace-with-a-long-random-token'  # optional
       uvicorn backend.server:app --host 127.0.0.1 --port 8000

5. Put it behind an HTTPS reverse proxy, disable request-body logging, and preserve immediate deletion. Test `/health`, then set the Android endpoint to `https://your-host/transcribe` and configure the same optional bearer token in the app.

The supplied backend rejects invalid authentication, oversized request bodies, and concurrent transcription work in ASGI middleware before FastAPI parses multipart data. It limits video uploads to 80 MB, 30 seconds, 1,800 decoded source frames, dimensions no larger than 1,920 pixels, and at most 1,920×1,080 total pixels (portrait video is allowed within the same pixel limit). It validates these properties with `ffprobe`, normalizes with `ffmpeg`, performs blocking media/model work outside the event loop, and permits one transcription request at a time. Limit and failure responses are stable messages that do not expose server exception details. The model is initialized once when the service starts.

Auto-AVSR reports 20.3% word error rate for this model on LRS3. That benchmark does not predict performance on handheld video. Accuracy degrades with profile views, small/blurred mouths, occlusion, poor lighting, frame drops, multiple faces, accents, and out-of-domain language.

## Privacy and licensing

Face video is biometric-sensitive. The supplied server uses per-request temporary directories, but production deployment also needs TLS, authentication, access controls, retention auditing, and explicit subject consent.

LipRead Lab's original source code is licensed under the MIT License; see `LICENSE`. Auto-AVSR source code is Apache-2.0. Its pretrained weights inherit unresolved restrictions from training datasets (LRS2, LRS3, VoxCeleb2 and AVSpeech-derived data). Treat the checkpoint as research/prototype-only unless the authors or legal counsel confirm your intended commercial use.

## Current scope

Implemented:

- Record up to 30 seconds or choose a stored video
- Preview the selected clip
- Remove audio locally before network transfer
- HTTPS-only configurable endpoint
- Visual-only transcript display and optional server confidence
- Save transcript to a chosen file
- Generate/play speech using Android TTS
- Save generated speech as WAV only when its bytes are detected as RIFF/WAVE; otherwise save it as generic `.audio`/`application/octet-stream`

Not implemented:

- On-device Auto-AVSR inference (the model is too large for this MVP)
- Word-level timestamps
- Lip-synchronized voice-over muxing into the source video
- Multi-speaker tracking
- Certificate pinning or a full production identity/access system (the supplied shared bearer token is optional deployment protection, not user identity)
