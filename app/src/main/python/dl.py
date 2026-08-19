"""yt-dlp download helper, bundled into the app via Chaquopy.

Download any supported link to a local file and return the on-disk path plus
probe info (duration/width/height) as JSON so the Kotlin side can build the
trim + aspect filter without a separate ffprobe pass.
"""
import json
import os
import re
import threading
import time
import urllib.error
import urllib.request

import yt_dlp

_cancelled = False
_last_status = ""
_last_pct = -1
_last_t = 0.0
_progress_cb = None

_TWITTER_HOST = re.compile(
    r"(?:^|\.)(?:x\.com|twitter\.com|mobile\.twitter\.com|vxtwitter\.com|fxtwitter\.com)$",
    re.I,
)
_STATUS_RE = re.compile(r"(?:status|statuses|i/web/status)/(\d{5,})")


def set_cancelled(v):
    global _cancelled
    _cancelled = bool(v)


def get_status():
    return _last_status


class _Cancelled(Exception):
    pass


class NeedXLogin(Exception):
    pass


class NoVideoInTweet(Exception):
    pass


class HevcOnlyError(Exception):
    """Export path: only H.264 (avc1) is acceptable; source has none."""


def _check_cancel():
    if _cancelled:
        raise _Cancelled("Cancelled")


class _CancelAwareResponse:
    """Honor _cancelled between / during body reads after urlopen returns."""

    def __init__(self, resp):
        self._resp = resp

    def read(self, amt=None):
        _check_cancel()
        if amt is None:
            chunks = []
            while True:
                _check_cancel()
                part = self._resp.read(64 * 1024)
                if not part:
                    break
                chunks.append(part)
            _check_cancel()
            return b"".join(chunks)
        data = self._resp.read(amt)
        _check_cancel()
        return data

    def readline(self, *args, **kwargs):
        _check_cancel()
        data = self._resp.readline(*args, **kwargs)
        _check_cancel()
        return data

    def close(self):
        return self._resp.close()

    def __enter__(self):
        self._resp.__enter__()
        return self

    def __exit__(self, *exc):
        return self._resp.__exit__(*exc)

    def __iter__(self):
        return self

    def __next__(self):
        line = self.readline()
        if not line:
            raise StopIteration
        return line

    def __getattr__(self, name):
        return getattr(self._resp, name)


def _urlopen_cancellable(opener, *args, **kwargs):
    """Run urlopen off-thread so Cancel raises without waiting the full socket hang.

    In-flight socket still ends at socket_timeout; extract_info / GraphQL abort promptly.
    """
    _check_cancel()
    box = {"resp": None, "err": None}

    def run():
        try:
            box["resp"] = opener(*args, **kwargs)
        except Exception as e:
            box["err"] = e

    t = threading.Thread(target=run, daemon=True)
    t.start()
    while t.is_alive():
        t.join(0.2)
        if _cancelled:
            raise _Cancelled("Cancelled")
    if box["err"] is not None:
        _check_cancel()
        raise box["err"]
    _check_cancel()
    return _CancelAwareResponse(box["resp"])


class _AbortableYDL(yt_dlp.YoutubeDL):
    """urlopen is used for extract_info HTTP, not only progress_hooks."""

    def urlopen(self, req):
        return _urlopen_cancellable(super().urlopen, req)


def _emit(text):
    global _last_status
    _last_status = text
    cb = _progress_cb
    if cb is None:
        return
    try:
        if hasattr(cb, "invoke"):
            cb.invoke(text)
        elif callable(cb):
            cb(text)
    except Exception:
        pass


def _human_bytes(n):
    try:
        n = int(n)
    except (TypeError, ValueError):
        return ""
    if n < 0:
        return ""
    if n < 1024:
        return "%d B" % n
    if n < 1024 * 1024:
        return "%d KB" % (n // 1024)
    if n < 1024 * 1024 * 1024:
        mb = n / (1024.0 * 1024.0)
        if mb >= 10:
            return "%d MB" % int(mb)
        return "%.1f MB" % mb
    return "%.2f GB" % (n / (1024.0 * 1024.0 * 1024.0))


def _hook_status_line(d):
    """Honest status. Never a numeric % unless total_bytes or estimate is known."""
    downloaded = d.get("downloaded_bytes") or 0
    try:
        downloaded = int(downloaded)
    except (TypeError, ValueError):
        downloaded = 0
    total = d.get("total_bytes") or d.get("total_bytes_estimate") or 0
    try:
        total = int(total)
    except (TypeError, ValueError):
        total = 0
    if total > 0:
        pct = int(downloaded * 100.0 / total)
        pct = max(0, min(100, pct))
        return "Downloading %d%%" % pct, pct
    human = _human_bytes(downloaded)
    if human and downloaded > 0:
        return "Downloading %s" % human, None
    return "Downloading…", None


def _hook(d):
    global _last_pct, _last_t
    _check_cancel()
    if d.get("status") != "downloading":
        return
    text, pct = _hook_status_line(d)
    now = time.monotonic()
    if pct is not None:
        if pct < _last_pct + 2 and (now - _last_t) < 0.5 and pct < 100:
            return
        _last_pct = pct
        _last_t = now
        _emit(text)
        return
    if (now - _last_t) < 0.5 and _last_status.startswith("Downloading"):
        return
    _last_t = now
    _emit(text)


def _twitter_status_id(url):
    try:
        from urllib.parse import urlparse
        p = urlparse(url)
        host = (p.netloc or "").split(":")[0].lower()
        if host.startswith("www."):
            host = host[4:]
        if not _TWITTER_HOST.search(host) and "twitter" not in host and host != "x.com":
            if not _STATUS_RE.search(url):
                return None
        m = _STATUS_RE.search(p.path or "")
        if m:
            return m.group(1)
    except Exception:
        pass
    m = _STATUS_RE.search(url or "")
    return m.group(1) if m else None


def _http_json(url, timeout=12):
    _check_cancel()
    req = urllib.request.Request(
        url,
        headers={
            "User-Agent": "Mozilla/5.0 (Linux; Android 14) AppleWebKit/537.36 VideoDroid/1.4.2",
            "Accept": "application/json",
        },
    )
    with _urlopen_cancellable(urllib.request.urlopen, req, timeout=timeout) as resp:
        raw = resp.read()
        _check_cancel()
        return json.loads(raw.decode("utf-8", "replace"))


def _pick_twitter_mp4(media_item, quality):
    variants = []
    for src in (
        media_item.get("formats") or [],
        media_item.get("variants") or [],
    ):
        for v in src:
            if not isinstance(v, dict):
                continue
            u = v.get("url") or ""
            if ".mp4" not in u:
                continue
            br = v.get("bitrate") or 0
            try:
                br = int(br)
            except (TypeError, ValueError):
                br = 0
            variants.append((br, u))
    if not variants:
        u = media_item.get("url") or ""
        if ".mp4" in u:
            variants.append((0, u))
    if not variants:
        return None
    variants.sort()
    cap = {"480p": 950000, "720p": 2200000, "1080p": 5000000}.get(quality)
    if quality == "best" or cap is None:
        return variants[-1][1]
    chosen = variants[0][1]
    for br, u in variants:
        if br <= cap:
            chosen = u
    return chosen


def _twitter_fallback_info(status_id, quality):
    """Resolve amplify/quoted video via public FxTwitter JSON. Never send cookies."""
    last_err = None
    data = None
    for base in (
        "https://api.fxtwitter.com/status/",
        "https://api.vxtwitter.com/Twitter/status/",
    ):
        _check_cancel()
        try:
            data = _http_json(base + status_id)
            break
        except urllib.error.HTTPError as e:
            last_err = e
            if e.code in (401, 403):
                raise NeedXLogin("Need X login")
            continue
        except Exception as e:
            last_err = e
            continue
    if data is None:
        err_s = str(last_err or "")
        if "401" in err_s or "403" in err_s:
            raise NeedXLogin("Need X login")
        raise NoVideoInTweet("No video in this tweet")

    tweet = data.get("tweet") if isinstance(data, dict) else None
    if tweet is None and isinstance(data, dict) and data.get("media_extended"):
        tweet = data
    if not isinstance(tweet, dict):
        raise NoVideoInTweet("No video in this tweet")

    media = tweet.get("media") or {}
    items = []
    if isinstance(media, dict):
        items.extend(media.get("videos") or media.get("all") or [])
    items.extend(tweet.get("media_extended") or [])
    videos = [m for m in items if isinstance(m, dict) and (
        m.get("type") in ("video", "gif") or ".mp4" in str(m.get("url") or "")
    )]
    if not videos:
        raise NoVideoInTweet("No video in this tweet")

    chosen = videos[0]
    mp4 = _pick_twitter_mp4(chosen, quality)
    if not mp4:
        raise NoVideoInTweet("No video in this tweet")
    return {
        "url": mp4,
        "duration": chosen.get("duration") or tweet.get("duration") or 0,
        "width": chosen.get("width") or 0,
        "height": chosen.get("height") or 0,
        "title": tweet.get("text") or tweet.get("altText") or "",
    }


def _download_direct(url, dest_base, duration=0, width=0, height=0, title=""):
    _check_cancel()
    dest = dest_base + ".mp4"
    req = urllib.request.Request(
        url,
        headers={"User-Agent": "Mozilla/5.0 VideoDroid/1.4.2"},
    )
    with urllib.request.urlopen(req, timeout=20) as resp, open(dest, "wb") as out:
        total = 0
        try:
            total = int(resp.headers.get("Content-Length") or 0)
        except (TypeError, ValueError):
            total = 0
        got = 0
        last_emit = 0.0
        while True:
            _check_cancel()
            chunk = resp.read(64 * 1024)
            if not chunk:
                break
            out.write(chunk)
            got += len(chunk)
            now = time.monotonic()
            if total > 0 and (now - last_emit) >= 0.4:
                last_emit = now
                _emit("Downloading %d%%" % min(100, int(got * 100.0 / total)))
    if not os.path.isfile(dest) or os.path.getsize(dest) < 64:
        raise FileNotFoundError("no file produced")
    return {
        "path": dest,
        "duration": duration or 0,
        "width": width or 0,
        "height": height or 0,
        "title": title or "",
    }


def _classify_twitter_error(err, had_cookies):
    msg = str(err)
    low = msg.lower()
    if "cancel" in low:
        raise _Cancelled("Cancelled")
    loginish = any(x in low for x in (
        "sign in", "login", "cookie", "authentication", "unauthorized",
        "403", "401", "private", "not a bot",
    ))
    novid = "no video" in low
    if loginish and not novid:
        raise NeedXLogin("Need X login")
    if novid and not had_cookies:
        # Guest GraphQL often lies "no video" for amplify/quoted; login may help.
        raise NeedXLogin("Need X login")
    if novid:
        raise NoVideoInTweet("No video in this tweet")
    raise err


def download(url, quality, outdir, filename="source", cookiefile=None,
             export_enabled=False, progress=None):
    if export_enabled:
        # Export ON: H.264-REQUIRED, no any-codec fallback. The phone's ffmpeg
        # build cannot decode HEVC at all (rc=69 invalid data even in software),
        # so never download HEVC here — fail fast instead (1.6.25).
        fmt = "bv*[vcodec^=avc1][height<=1080]+ba/bv*[vcodec^=avc1][height<=1080]"
    else:
        # Export OFF: presets unchanged, may grab HEVC (no convert needed).
        fmt = {
            "best": "bv*[vcodec^=avc1][height<=1080]+ba/b[height<=1080]/b",
            "4K": "bv*[height<=2160]+ba/b",
            "1080p": "bv*[height<=1080]+ba/b[height<=1080]/bv*[height<=1080]/b",
            "720p": "bv*[height<=720]+ba/b[height<=720]/bv*[height<=720]/b",
            "480p": "bv*[height<=480]+ba/b[height<=480]/bv*[height<=480]/b",
        }.get(quality, "bv*[height<=1080]+ba/b[height<=1080]/bv*[height<=1080]/b")

    global _progress_cb, _last_status, _last_pct, _last_t
    _progress_cb = progress
    _last_status = "Downloading..."
    _last_pct = -1
    _last_t = 0.0
    _check_cancel()

    cookie_ok = bool(cookiefile and os.path.isfile(cookiefile))
    twid = _twitter_status_id(url)

    opts = {
        "format": fmt,
        "outtmpl": os.path.join(outdir, filename + ".%(ext)s"),
        "noplaylist": True,
        "quiet": True,
        "no_warnings": True,
        "noprogress": True,
        "retries": 3,
        "fragment_retries": 3,
        "socket_timeout": 8,
        "progress_hooks": [_hook],
        "merge_output_format": "mp4",
        "extractor_args": {
            "youtube": {"player_client": ["android", "ios", "web"]},
            "twitter": {"api": ["graphql"]},
        },
    }
    if cookie_ok:
        opts["cookiefile"] = cookiefile

    dest_base = os.path.join(outdir, filename)
    info = None
    ytdlp_err = None

    def _extract(format_spec):
        run_opts = dict(opts)
        if format_spec is None:
            run_opts.pop("format", None)
        else:
            run_opts["format"] = format_spec
        _check_cancel()
        with _AbortableYDL(run_opts) as ydl:
            return ydl.extract_info(url, download=True)

    try:
        info = _extract(fmt)
    except (_Cancelled, NeedXLogin, NoVideoInTweet):
        raise
    except Exception as e:
        low = str(e).lower()
        if "format is not available" in low:
            if export_enabled:
                raise HevcOnlyError(
                    "This video is HEVC-only. Cannot convert for %s on this phone. Try another source." % quality
                )
            try:
                info = _extract("best*/bv*+ba/b")
            except (_Cancelled, NeedXLogin, NoVideoInTweet):
                raise
            except Exception:
                try:
                    info = _extract(None)
                except (_Cancelled, NeedXLogin, NoVideoInTweet):
                    raise
                except Exception:
                    ytdlp_err = e
        else:
            ytdlp_err = e
        if ytdlp_err is not None:
            if twid:
                try:
                    _classify_twitter_error(ytdlp_err, cookie_ok)
                except (NeedXLogin, NoVideoInTweet, _Cancelled):
                    pass
                except Exception:
                    pass
            else:
                raise ytdlp_err

    if info is None and twid:
        _check_cancel()
        try:
            fb = _twitter_fallback_info(twid, quality)
            return json.dumps(_download_direct(
                fb["url"], dest_base,
                duration=fb.get("duration") or 0,
                width=fb.get("width") or 0,
                height=fb.get("height") or 0,
                title=fb.get("title") or "",
            ))
        except (_Cancelled, NeedXLogin, NoVideoInTweet):
            raise
        except Exception:
            if ytdlp_err is not None:
                _classify_twitter_error(ytdlp_err, cookie_ok)
            raise NoVideoInTweet("No video in this tweet")

    if info is None and ytdlp_err is not None:
        raise ytdlp_err

    _check_cancel()
    for f in sorted(os.listdir(outdir)):
        if f.startswith(filename + "."):
            return json.dumps({
                "path": os.path.join(outdir, f),
                "duration": (info or {}).get("duration") or 0,
                "width": (info or {}).get("width") or 0,
                "height": (info or {}).get("height") or 0,
                "title": (info or {}).get("title") or "",
            })
    if twid:
        raise NoVideoInTweet("No video in this tweet")
    raise FileNotFoundError("no file produced for " + url)
