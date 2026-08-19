#!/usr/bin/env python3
"""Unit-style proof of FormatWorker.mapError order (1.6.5). Synthetic strings only."""


def map_error(raw: str) -> str:
    s = raw.lower()
    if "cancel" in s:
        return "Cancelled"
    if "unsupported url" in s or "unsupported site" in s:
        return "Unsupported site. Open page."
    if "404" in s or "not found" in s:
        return "File gone (404)"
    if "requested format is not available" in s or "format is not available" in s:
        return "That format is gone. Try 1080 or Open page."
    if (
        "certificate_verify_failed" in s
        or "hostname mismatch" in s
        or "ssl" in s
        or "certificate" in s
    ):
        return "Site TLS bad. Open page."
    if "need x login" in s:
        return "Need X login"
    if "no video in this tweet" in s or "no video could be found in this tweet" in s:
        return "No video in this tweet"
    if any(
        x in s
        for x in (
            "sign in",
            "login",
            "cookie",
            "authentication",
            "private video",
            "confirm you’re not a bot",
            "confirm you're not a bot",
        )
    ):
        return "Need X login"
    if any(
        x in s
        for x in (
            "unknown host",
            "unable to resolve",
            "network is unreachable",
            "failed to connect",
            "timeout",
            "timed out",
            "no address associated",
            "enotconn",
            "econnrefused",
            "offline",
        )
    ):
        return "No network"
    import re

    cleaned = re.sub(r"(?i)cookie[^\n]*", "", raw).strip()
    return (cleaned[:160] or "Download failed")


CASES = [
    ("Download cancelled by user", "Cancelled"),
    ("ERROR: Unsupported URL: https://example.test/watch", "Unsupported site. Open page."),
    ("HTTP Error 404: Not Found", "File gone (404)"),
    ("ERROR: [generic] Requested format is not available", "That format is gone. Try 1080 or Open page."),
    (
        "ssl.SSLCertVerificationError: [SSL: CERTIFICATE_VERIFY_FAILED] certificate verify failed: Hostname mismatch",
        "Site TLS bad. Open page.",
    ),
    ("https://cdn.example.test:443: Connection timed out", "No network"),
    ("ERROR: unable to download video data: HTTP Error 404: Not Found", "File gone (404)"),
]


def main() -> int:
    failed = 0
    for raw, want in CASES:
        got = map_error(raw)
        ok = got == want
        print(("PASS" if ok else "FAIL"), repr(raw[:70]), "->", repr(got))
        if not ok:
            print("  expected", repr(want))
            failed += 1
    # timeout must not steal TLS
    tls = map_error("certificate verify failed: hostname mismatch (handshake timeout)")
    if tls != "Site TLS bad. Open page.":
        print("FAIL TLS+timeout leaked to", repr(tls))
        failed += 1
    else:
        print("PASS TLS wins over timeout substring")
    print("failed" if failed else "all ok", failed)
    return 1 if failed else 0


if __name__ == "__main__":
    raise SystemExit(main())
