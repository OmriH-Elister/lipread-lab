from dataclasses import dataclass
import math

MAX_DURATION_SECONDS = 30.0
MAX_DIMENSION = 1920
MAX_PIXELS = 1920 * 1080
MAX_FRAMES = 1800


class MediaLimitError(ValueError):
    pass


@dataclass(frozen=True)
class MediaMetadata:
    duration: float
    width: int
    height: int
    frames: int


def parse_probe(probe: dict) -> MediaMetadata:
    try:
        streams = probe["streams"]
        stream = streams[0]
        duration = float(probe["format"]["duration"])
        width = int(stream["width"])
        height = int(stream["height"])
        frames = int(stream["nb_read_frames"])
    except (KeyError, IndexError, TypeError, ValueError, OverflowError) as exc:
        raise MediaLimitError("Video metadata is invalid") from exc

    if not math.isfinite(duration) or duration <= 0:
        raise MediaLimitError("Video duration is invalid")
    if duration > MAX_DURATION_SECONDS:
        raise MediaLimitError("Video exceeds the 30 second duration limit")
    if width <= 0 or height <= 0:
        raise MediaLimitError("Video resolution is invalid")
    if width > MAX_DIMENSION or height > MAX_DIMENSION or width * height > MAX_PIXELS:
        raise MediaLimitError("Video exceeds the resolution limit")
    if frames <= 0:
        raise MediaLimitError("Video frame count is invalid")
    if frames > MAX_FRAMES:
        raise MediaLimitError("Video exceeds the 1800 frames limit")
    return MediaMetadata(duration=duration, width=width, height=height, frames=frames)
