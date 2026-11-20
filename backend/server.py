import argparse
import asyncio
import json
import os
import secrets
import subprocess
import tempfile
from pathlib import Path

import torch
import torchvision
from fastapi import FastAPI, File, Header, HTTPException, UploadFile
from fastapi.responses import JSONResponse

from backend.media_validation import MediaLimitError, parse_probe
from backend.upload_guard import UploadGuard

AUTO_AVSR_ROOT = Path(os.environ.get("AUTO_AVSR_ROOT", "../auto_avsr")).resolve()
CHECKPOINT = Path(os.environ.get("AUTO_AVSR_CHECKPOINT", "vsr_trlrs2lrs3vox2avsp_base.pth")).resolve()
BEARER_TOKEN = os.environ.get("LIPREAD_BEARER_TOKEN", "")
MAX_UPLOAD = 80 * 1024 * 1024
MAX_REQUEST_BODY = MAX_UPLOAD + 1024 * 1024

if not AUTO_AVSR_ROOT.exists():
    raise RuntimeError(f"AUTO_AVSR_ROOT does not exist: {AUTO_AVSR_ROOT}")

import sys
sys.path.insert(0, str(AUTO_AVSR_ROOT))
from lightning import ModelModule
from datamodule.transforms import VideoTransform
from preparation.detectors.mediapipe.detector import LandmarksDetector
from preparation.detectors.mediapipe.video_process import VideoProcess


class VisualSpeechPipeline(torch.nn.Module):
    """Adapted from Auto-AVSR's Apache-2.0 inference tutorial."""

    def __init__(self, checkpoint: Path):
        super().__init__()
        if not checkpoint.exists():
            raise RuntimeError(f"Checkpoint does not exist: {checkpoint}")
        args = argparse.Namespace(modality="video")
        self.device = torch.device("cuda" if torch.cuda.is_available() else "cpu")
        self.landmarks_detector = LandmarksDetector()
        self.video_process = VideoProcess(convert_gray=False)
        self.video_transform = VideoTransform(subset="test")
        state = torch.load(str(checkpoint), map_location="cpu")
        self.modelmodule = ModelModule(args)
        self.modelmodule.model.load_state_dict(state)
        self.modelmodule.to(self.device).eval()

    def forward(self, filename: str) -> str:
        frames = torchvision.io.read_video(filename, pts_unit="sec")[0].numpy()
        if len(frames) == 0:
            raise ValueError("The uploaded file has no video frames")
        landmarks = self.landmarks_detector(frames)
        video = self.video_process(frames, landmarks)
        video = torch.tensor(video).permute((0, 3, 1, 2))
        video = self.video_transform(video).to(self.device)
        with torch.no_grad():
            result = self.modelmodule(video)
        if isinstance(result, (list, tuple)):
            result = " ".join(str(item) for item in result)
        return str(result).strip()


def probe_video(source: Path):
    try:
        completed = subprocess.run(
            [
                "ffprobe", "-v", "error", "-count_frames",
                "-select_streams", "v:0",
                "-show_entries", "stream=width,height,nb_read_frames:format=duration",
                "-of", "json", str(source),
            ],
            check=True,
            stdout=subprocess.PIPE,
            stderr=subprocess.DEVNULL,
            text=True,
            timeout=30,
        )
        return parse_probe(json.loads(completed.stdout))
    except subprocess.TimeoutExpired as exc:
        raise HTTPException(status_code=408, detail="Video inspection timed out") from exc
    except (subprocess.CalledProcessError, json.JSONDecodeError) as exc:
        raise HTTPException(status_code=422, detail="Video metadata could not be read") from exc
    except MediaLimitError as exc:
        raise HTTPException(status_code=422, detail=str(exc)) from exc


def normalize_video(source: Path, normalized: Path) -> None:
    try:
        subprocess.run(
            [
                "ffmpeg", "-v", "error", "-y", "-i", str(source),
                "-an", "-vf", "fps=25", str(normalized),
            ],
            check=True,
            stdout=subprocess.DEVNULL,
            stderr=subprocess.DEVNULL,
            timeout=120,
        )
    except subprocess.TimeoutExpired as exc:
        raise HTTPException(status_code=408, detail="Video normalization timed out") from exc
    except subprocess.CalledProcessError as exc:
        raise HTTPException(status_code=422, detail="Video could not be decoded") from exc


def require_authorization(authorization: str | None) -> None:
    if not BEARER_TOKEN:
        return
    prefix = "Bearer "
    supplied = authorization[len(prefix):] if authorization and authorization.startswith(prefix) else ""
    if not supplied or not secrets.compare_digest(supplied, BEARER_TOKEN):
        raise HTTPException(
            status_code=401,
            detail="Valid bearer token required",
            headers={"WWW-Authenticate": "Bearer"},
        )


app = FastAPI(title="LipRead Lab visual-only backend", version="0.2.0")
app.add_middleware(
    UploadGuard,
    max_body_bytes=MAX_REQUEST_BODY,
    bearer_token=BEARER_TOKEN,
    max_concurrent=1,
)
pipeline = VisualSpeechPipeline(CHECKPOINT)
inference_slot = asyncio.BoundedSemaphore(1)


@app.get("/health")
def health():
    return {"ok": True, "device": str(pipeline.device), "model": CHECKPOINT.name}


@app.post("/transcribe")
async def transcribe(
    video: UploadFile = File(...),
    authorization: str | None = Header(default=None),
):
    require_authorization(authorization)
    if video.content_type and not video.content_type.startswith("video/"):
        raise HTTPException(status_code=415, detail="Upload must be a video")

    with tempfile.TemporaryDirectory(prefix="lipread-") as directory:
        source = Path(directory) / "upload.mp4"
        normalized = Path(directory) / "normalized.mp4"
        size = 0
        with source.open("wb") as output:
            while chunk := await video.read(1024 * 1024):
                size += len(chunk)
                if size > MAX_UPLOAD:
                    raise HTTPException(status_code=413, detail="Video exceeds 80 MB")
                await asyncio.to_thread(output.write, chunk)
        if size == 0:
            raise HTTPException(status_code=422, detail="Uploaded video is empty")

        await asyncio.to_thread(probe_video, source)
        await asyncio.to_thread(normalize_video, source, normalized)
        try:
            async with inference_slot:
                transcript = await asyncio.to_thread(pipeline, str(normalized))
        except HTTPException:
            raise
        except Exception as exc:
            raise HTTPException(status_code=422, detail="Visual speech recognition failed") from exc
        return JSONResponse({"transcript": transcript})
