# Test data

Fixtures for scanning, player, viewer and metadata tests. Only small files: real media never goes in the repo.

## media/

Two-second 160×90 clips with audio, small enough to commit, used by scanning, metadata and player tests.

| File | Video | Audio |
|---|---|---|
| `sample.mp4` | H.264 | AAC |
| `sample.mkv` | H.264 | AAC (same streams, Matroska) |
| `sample.webm` | VP9 | Opus |
| `sample.mov` | H.264 | AAC (QuickTime) |
| `sample.avi` | H.264 | MP3 (AVI) |

Regenerate with:

```bash
ffmpeg -f lavfi -i "testsrc2=size=160x90:rate=10:duration=2" -f lavfi -i "sine=frequency=440:duration=2" -c:v libx264 -preset veryslow -crf 35 -pix_fmt yuv420p -c:a aac -b:a 16k -shortest -movflags +faststart sample.mp4
ffmpeg -i sample.mp4 -c copy sample.mkv
ffmpeg -i sample.mp4 -c copy sample.mov
ffmpeg -i sample.mp4 -c:v copy -c:a libmp3lame -b:a 32k sample.avi
ffmpeg -f lavfi -i "testsrc2=size=160x90:rate=10:duration=2" -f lavfi -i "sine=frequency=440:duration=2" -c:v libvpx-vp9 -crf 50 -b:v 0 -c:a libopus -b:a 12k -shortest sample.webm
```

`long.mp4` is a 30 s variant (H.264/AAC, 5 fps) for player UI tests, long enough that autoplay does not kick in mid-test:

```bash
ffmpeg -f lavfi -i "testsrc2=size=160x90:rate=5:duration=30" -f lavfi -i "sine=frequency=440:duration=30" -c:v libx264 -preset veryslow -crf 42 -g 50 -pix_fmt yuv420p -c:a aac -b:a 8k -shortest -movflags +faststart long.mp4
```

`hls/sample.m3u8` (+ `segment*.ts`) is the same clip as an HLS VOD playlist:

```bash
ffmpeg -i sample.mp4 -c copy -f hls -hls_time 1 -hls_playlist_type vod -hls_segment_filename 'hls/segment%d.ts' hls/sample.m3u8
```

## web/

`AWS Course/01 - Introduction/003 AWS Account Activation Troubleshooting.html` checks itself when loaded
(`assets/quiz.js`): JavaScript, relative CSS and image, quiz interaction, reading another file of the same course,
and four escape attempts that must be blocked (`file://`, encoded `..`, sibling course, absolute path). It writes one
`name=ok|blocked(...)|FAIL|LEAK` line per check into `#results` and sets `data-done="true"` (ADR-0003).

Used by the Android WebView test, the Linux resource harness and the H1 spike.

## documents/

`sample.pdf`: two pages (A4 portrait with a blue box, A4 landscape with an orange box), 1 KB, written by hand
(Helvetica, no embedded fonts) so any PDF engine renders it. Used by the Android PDF test.
