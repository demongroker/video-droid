"""yt-dlp download helper, bundled into the app via Chaquopy.

Download any supported link to a local file and return the on-disk path plus
probe info (duration/width/height) as JSON so the Kotlin side can build the
trim + aspect filter without a separate ffprobe pass.
"""
import json
import os
import yt_dlp


def download(url, quality, outdir, filename="source"):
    fmt = {
        "best": "best",
        "1080p": "best[height<=1080]/best",
        "720p": "best[height<=720]/best",
        "480p": "best[height<=480]/best",
    }.get(quality, "best")

    opts = {
        "format": fmt,
        "outtmpl": os.path.join(outdir, filename + ".%(ext)s"),
        "noplaylist": True,
        "quiet": True,
        "no_warnings": True,
        "noprogress": True,
        "retries": 3,
        "fragment_retries": 3,
        # Match the desktop recipe: use YouTube's android client (more reliable).
        "extractor_args": {"youtube": {"player_client": ["android"]}},
    }
    with yt_dlp.YoutubeDL(opts) as ydl:
        info = ydl.extract_info(url, download=True)

    for f in sorted(os.listdir(outdir)):
        if f.startswith(filename + "."):
            return json.dumps({
                "path": os.path.join(outdir, f),
                "duration": info.get("duration") or 0,
                "width": info.get("width") or 0,
                "height": info.get("height") or 0,
            })
    raise FileNotFoundError("no file produced for " + url)
