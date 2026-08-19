"""yt-dlp download helper, bundled into the app via Chaquopy.

Download any supported link to a local file and return the on-disk path plus
probe info (duration/width/height) as JSON so the Kotlin side can build the
trim + aspect filter without a separate ffprobe pass.
"""
import json
import os
import re
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


def _check_cancel():
    if _cancelled:
        raise _Cancelled("Cancelled")


class _AbortableYDL(yt_dlp.YoutubeDL):
    """urlopen is used for extract_info HTTP, not only progress_hooks."""

    def urlopen(self, req):
        _check_cancel()
        return super().urlopen(req)


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


def _hook(d):
    global _last_pct, _last_t
    _check_cancel()
    if d.get("status") != "downloading":
        return
    downloaded = d.get("downloaded_bytes") or 0
    total = d.get("total_bytes") or d.get("total_bytes_estimate") or 0
    pct = None
    if total > 0:
        pct = int(downloaded * 100.0 / total)
    else:
        raw = d.get("_percent_str")
        if raw:
            try:
                pct = int(float(str(raw).replace("%", "").strip()))
            except (TypeError, ValueError):
                pct = None
    if pct is None:
        return
    pct = max(0, min(100, pct))
    now = time.monotonic()
    if pct < _last_pct + 2 and (now - _last_t) < 0.5 and pct < 100:
        return
    _last_pct = pct
    _last_t = now
    _emit("Downloading %d%%" % pct)


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
    with urllib.request.urlopen(req, timeout=timeout) as resp:
        return json.loads(resp.read().decode("utf-8", "replace"))


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
    }


def _download_direct(url, dest_base, duration=0, width=0, height=0):
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


def download(url, quality, outdir, filename="source", cookiefile=None, progress=None):
    # Muxed `best` is often missing on android/ios clients; always allow adaptive merge.
    fmt = {
        "best": "bv*+ba/b",
        "1080p": "bv*[height<=1080]+ba/b[height<=1080]/bv*+ba/b",
        "720p": "bv*[height<=720]+ba/b[height<=720]/bv*+ba/b",
        "480p": "bv*[height<=480]+ba/b[height<=480]/bv*+ba/b",
    }.get(quality, "bv*+ba/b")

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
            })
    if twid:
        raise NoVideoInTweet("No video in this tweet")
    raise FileNotFoundError("no file produced for " + url)
