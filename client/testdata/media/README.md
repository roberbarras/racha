# Test media

Two-second 160×90 clips with audio, small enough to commit, used by scanning, metadata and player tests.

| File | Video | Audio |
|---|---|---|
| `sample.mp4` | H.264 | AAC |
| `sample.mkv` | H.264 | AAC (same streams, Matroska) |
| `sample.webm` | VP9 | Opus |

Regenerate with:

```bash
ffmpeg -f lavfi -i "testsrc2=size=160x90:rate=10:duration=2" -f lavfi -i "sine=frequency=440:duration=2" -c:v libx264 -preset veryslow -crf 35 -pix_fmt yuv420p -c:a aac -b:a 16k -shortest -movflags +faststart sample.mp4
ffmpeg -i sample.mp4 -c copy sample.mkv
ffmpeg -f lavfi -i "testsrc2=size=160x90:rate=10:duration=2" -f lavfi -i "sine=frequency=440:duration=2" -c:v libvpx-vp9 -crf 50 -b:v 0 -c:a libopus -b:a 12k -shortest sample.webm
```
