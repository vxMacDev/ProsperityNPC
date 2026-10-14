#!/usr/bin/env python3
"""Signs approved resident skins through MineSkin and saves each result as a candidate file.

    python3 tools/skins/sign.py <candidates-dir> <png>...            check the PNGs, upload nothing
    python3 tools/skins/sign.py --upload <candidates-dir> <png>...   upload and save candidates

Each PNG must be named <key>-<slot>-v<n>.png, for example otto-marsh-work-v1.png, and be a 64x64 RGBA classic
skin. Every upload is permanent and stored by MineSkin, so only upload art the owner has approved.

Uploads are unlisted and use the classic model. After signing, the tool downloads the texture Mojang now serves
and compares every pixel with the PNG, so a candidate is always the approved art. Candidates are named after the
PNG (otto-marsh-work-v1.json). A PNG that already has a candidate is skipped, so a run that stops part way can be
started again. MineSkin returns the same skin for the same image, so uploading a PNG twice does no harm.

An API key is optional. MineSkin gives keyed accounts higher limits. Pass --key-file with a file that holds only
the key. On Linux the file must be readable by its owner only. The key is sent only to api.mineskin.org and is
never printed or saved.
"""
import argparse
import base64
import hashlib
import json
import os
import re
import stat
import struct
import sys
import time
import urllib.error
import urllib.request
import uuid
import zlib
from pathlib import Path

API = "https://api.mineskin.org"
TEXTURE_HOSTS = ("http://textures.minecraft.net/", "https://textures.minecraft.net/")
NAME = re.compile(r"([a-z0-9][a-z0-9-]{1,31})-(everyday|work|leisure)-v[0-9]+\.png")
MAX_PNG_BYTES = 64 * 1024
MAX_RESPONSE_BYTES = 512 * 1024
POLL_SECONDS = 1.5
JOB_TIMEOUT_SECONDS = 180
MAX_RATE_LIMIT_WAITS = 5
MAX_WAIT_SECONDS = 120


class Refused(Exception):
    pass


class NoRedirects(urllib.request.HTTPRedirectHandler):
    """Treats a redirect as an error, so the key never follows a request to another address."""

    def redirect_request(self, *args):
        return None


OPENER = urllib.request.build_opener(NoRedirects)


def rgba_pixels(data):
    """Decodes a 64x64 8-bit RGBA PNG into 4096 (r, g, b, a) tuples. Anything else is refused."""
    if len(data) < 33 or data[:8] != b"\x89PNG\r\n\x1a\n" or data[12:16] != b"IHDR":
        raise Refused("not a PNG")
    width, height, depth, colour, compression, filtering, interlace = struct.unpack(">IIBBBBB", data[16:29])
    if (width, height, depth, colour, compression, filtering, interlace) != (64, 64, 8, 6, 0, 0, 0):
        raise Refused("must be a 64x64 8-bit RGBA PNG without interlacing")
    compressed, offset, ended = bytearray(), 8, False
    while not ended:
        if offset + 12 > len(data):
            raise Refused("PNG is truncated")
        length, kind = struct.unpack(">I4s", data[offset:offset + 8])
        body = data[offset + 8:offset + 8 + length]
        crc = data[offset + 8 + length:offset + 12 + length]
        if len(body) != length or len(crc) != 4 or zlib.crc32(kind + body) != struct.unpack(">I", crc)[0]:
            raise Refused("PNG is damaged")
        if kind == b"IDAT":
            compressed += body
        ended = kind == b"IEND"
        offset += length + 12
    raw = zlib.decompress(bytes(compressed))
    stride = 64 * 4
    if len(raw) != 64 * (stride + 1):
        raise Refused("PNG image data has the wrong size")
    rows, previous = [], bytearray(stride)
    for y in range(64):
        kind, line = raw[y * (stride + 1)], bytearray(raw[y * (stride + 1) + 1:(y + 1) * (stride + 1)])
        for i in range(stride):
            left = line[i - 4] if i >= 4 else 0
            up, up_left = previous[i], previous[i - 4] if i >= 4 else 0
            if kind == 1:
                line[i] = (line[i] + left) & 255
            elif kind == 2:
                line[i] = (line[i] + up) & 255
            elif kind == 3:
                line[i] = (line[i] + (left + up) // 2) & 255
            elif kind == 4:
                guess = left + up - up_left
                nearest = min((abs(guess - left), 0, left), (abs(guess - up), 1, up), (abs(guess - up_left), 2, up_left))
                line[i] = (line[i] + nearest[2]) & 255
            elif kind != 0:
                raise Refused("PNG uses an unknown filter")
        rows.append(line)
        previous = line
    return [tuple(row[x * 4:x * 4 + 4]) for row in rows for x in range(64)]


def dig(value, *keys):
    """Follows keys through nested JSON objects. Returns None as soon as one is missing or not an object."""
    for key in keys:
        value = value.get(key) if isinstance(value, dict) else None
    return value


def same_art(a, b):
    """True when both images show the same pixels. Fully transparent pixels match whatever their colour."""
    return all(p == q or (p[3] == 0 and q[3] == 0) for p, q in zip(a, b))


def read_skin(path):
    """Returns (asset key, PNG bytes, pixels) for an approved skin, or raises Refused."""
    match = NAME.fullmatch(path.name)
    if not match:
        raise Refused(f"{path.name}: name it <key>-<everyday|work|leisure>-v<n>.png")
    data = path.read_bytes()
    if len(data) > MAX_PNG_BYTES:
        raise Refused(f"{path.name}: over 64 KiB")
    try:
        pixels = rgba_pixels(data)
    except (Refused, zlib.error) as problem:
        raise Refused(f"{path.name}: {problem}")
    return f"resident/v1/{match.group(1)}/{match.group(2)}", data, pixels


def read_key(path):
    descriptor = os.open(path, os.O_RDONLY | getattr(os, "O_NOFOLLOW", 0))
    try:
        info = os.fstat(descriptor)
        if not stat.S_ISREG(info.st_mode) or (os.name == "posix" and info.st_mode & 0o077):
            raise Refused("the key file must be a regular file readable by its owner only (chmod 600)")
        text = os.read(descriptor, 1025)
    finally:
        os.close(descriptor)
    key = text.decode("ascii", "replace").strip()
    if not re.fullmatch(r"[A-Za-z0-9_-]{16,1024}", key):
        raise Refused("the key file does not hold a key")
    return key


def fetch(request, timeout=90):
    """Returns (HTTP status, body, Retry-After header). Redirects come back as errors."""
    try:
        with OPENER.open(request, timeout=timeout) as response:
            return response.status, response.read(MAX_RESPONSE_BYTES + 1), None
    except urllib.error.HTTPError as error:
        return error.code, error.read(MAX_RESPONSE_BYTES + 1), error.headers.get("Retry-After")


class MineSkin:
    def __init__(self, user_agent, key=None):
        self.headers = {"User-Agent": user_agent, "Accept": "application/json"}
        if key:
            self.headers["Authorization"] = "Bearer " + key
        self.next_request = 0.0

    def call(self, method, path, body=None, content_type=None):
        """Sends one request, waiting out the rate limit first. Returns (HTTP status, parsed JSON object)."""
        for _ in range(MAX_RATE_LIMIT_WAITS):
            time.sleep(max(0.0, self.next_request - time.monotonic()))
            headers = dict(self.headers, **({"Content-Type": content_type} if content_type else {}))
            status, raw, retry_after = fetch(urllib.request.Request(API + path, data=body, headers=headers, method=method))
            if len(raw) > MAX_RESPONSE_BYTES:
                raise Refused("MineSkin sent an oversized reply")
            try:
                reply = json.loads(raw)
            except ValueError:
                reply = None
            reply = reply if isinstance(reply, dict) else {}
            wait = dig(reply, "rateLimit", "next", "relative")
            wait = min(wait / 1000, MAX_WAIT_SECONDS) if isinstance(wait, (int, float)) and wait > 0 else 0.0
            self.next_request = time.monotonic() + wait
            if status != 429:
                return status, reply
            # Rate limited: the request was not accepted, so sending it again is safe.
            seconds = float(retry_after) if retry_after and retry_after.isdigit() else max(wait, 5.0)
            self.next_request = time.monotonic() + min(seconds, MAX_WAIT_SECONDS)
        raise Refused("still rate limited after several waits; try again later")

    def queue(self, filename, png):
        boundary = "skin" + uuid.uuid4().hex
        body = bytearray()
        for name, value in (("variant", "classic"), ("visibility", "unlisted")):
            body += f'--{boundary}\r\nContent-Disposition: form-data; name="{name}"\r\n\r\n{value}\r\n'.encode()
        body += (f'--{boundary}\r\nContent-Disposition: form-data; name="file"; filename="{filename}"\r\n'
                 "Content-Type: image/png\r\n\r\n").encode()
        body += png + f"\r\n--{boundary}--\r\n".encode()
        return self.call("POST", "/v2/queue", bytes(body), "multipart/form-data; boundary=" + boundary)

    def sign(self, filename, png):
        """Uploads one skin and waits for MineSkin to finish it. Returns (value, signature, skin object)."""
        status, reply = self.queue(filename, png)
        deadline = time.monotonic() + JOB_TIMEOUT_SECONDS
        while True:
            if status not in (200, 202):
                raise Refused(f"MineSkin refused the upload (HTTP {status}{error_codes(reply)})")
            job = dig(reply, "job")
            job = job if isinstance(job, dict) else {}
            if job.get("status") == "failed":
                raise Refused(f"MineSkin could not generate the skin{error_codes(reply)}")
            if job.get("status") == "completed":
                skin = dig(reply, "skin")
                if not isinstance(skin, dict):
                    status, reply = self.call("GET", "/v2/skins/" + safe_id(job.get("result")))
                    skin = dig(reply, "skin") if status == 200 else None
                return signed_texture(skin)
            if time.monotonic() > deadline:
                raise Refused("MineSkin did not finish the job in time")
            self.next_request = max(self.next_request, time.monotonic() + POLL_SECONDS)
            status, reply = self.call("GET", "/v2/queue/" + safe_id(job.get("id")))


def safe_id(value):
    if not isinstance(value, str) or not re.fullmatch(r"[A-Za-z0-9-]{1,64}", value):
        raise Refused("MineSkin sent an unexpected job or skin id")
    return value


def signed_texture(skin):
    data = dig(skin, "texture", "data")
    if not isinstance(data, dict) or not all(isinstance(data.get(k), str) and 0 < len(data[k]) <= 16384
                                             for k in ("value", "signature")):
        raise Refused("MineSkin's reply has no signed texture")
    return data["value"], data["signature"], skin


def error_codes(reply):
    """MineSkin's error codes, cleaned so only short plain words reach the terminal. Messages are not printed."""
    errors = dig(reply, "errors") if isinstance(dig(reply, "errors"), list) else []
    codes = [re.sub(r"[^a-z0-9_]", "", str(e.get("code")).lower())[:40] for e in errors if isinstance(e, dict)]
    return ": " + ", ".join(c for c in codes if c) if any(codes) else ""


def texture_url(value):
    """The signed value must point at a Mojang texture and use the classic model."""
    payload = json.loads(base64.b64decode(value, validate=True))
    url = dig(payload, "textures", "SKIN", "url")
    if not isinstance(url, str) or not url.startswith(TEXTURE_HOSTS):
        raise Refused("the signed value does not point at a Mojang texture")
    if dig(payload, "textures", "SKIN", "metadata", "model") == "slim":
        raise Refused("MineSkin signed the slim model; residents need classic")
    return url


def download_texture(url, user_agent):
    status, body, _ = fetch(urllib.request.Request(url.replace("http://", "https://", 1), headers={"User-Agent": user_agent}), 30)
    if status != 200 or len(body) > MAX_PNG_BYTES:
        raise Refused(f"could not download the signed texture (HTTP {status})")
    return rgba_pixels(body)


def write_whole(path, document):
    """Writes the file in one step, so a partial file is never left under the final name."""
    temporary = path.with_name(path.name + ".partial")
    with temporary.open("w", encoding="utf-8", newline="\n") as out:
        json.dump(document, out, indent=2)
        out.write("\n")
    os.replace(temporary, path)


def existing_candidate(path, asset, png):
    """A candidate left by an earlier run must be complete, for the same skin and made from this exact PNG."""
    receipt = path.with_name(path.stem + ".receipt.json")
    try:
        document = json.loads(path.read_text(encoding="utf-8"))
        signed_from = json.loads(receipt.read_text(encoding="utf-8")).get("source_sha256")
    except (OSError, ValueError, AttributeError):
        raise Refused(f"{path.name} or its receipt is missing or damaged; move them away and run again")
    if not isinstance(document, dict) or set(document) != {"key", "value", "signature"} or document["key"] != asset \
            or not all(isinstance(document[k], str) and document[k] for k in ("value", "signature")):
        raise Refused(f"{path.name} is not a candidate for {asset}; move it away and run again")
    if signed_from != hashlib.sha256(png).hexdigest():
        raise Refused(f"{path.name} was signed from a different PNG; move it away and run again")


def main():
    parser = argparse.ArgumentParser(description=__doc__, formatter_class=argparse.RawDescriptionHelpFormatter)
    parser.add_argument("candidates", type=Path, help="folder for candidate files (created if missing)")
    parser.add_argument("pngs", type=Path, nargs="+")
    parser.add_argument("--upload", action="store_true", help="upload to MineSkin; without it, only check the PNGs")
    parser.add_argument("--key-file", type=Path, help="file holding a MineSkin API key (optional)")
    parser.add_argument("--user-agent", default="ProsperityNPC-skins/1.0")
    args = parser.parse_args()

    skins = [(path, *read_skin(path)) for path in args.pngs]
    assets = [asset for _, asset, _, _ in skins]
    if len(set(assets)) != len(assets):
        raise Refused("two PNGs are for the same resident and slot")
    if not args.upload:
        for path, asset, _, _ in skins:
            print(f"ok      {path.name} -> {asset}")
        print(f"{len(skins)} skins checked. Nothing uploaded. Add --upload to sign them.")
        return 0

    client = MineSkin(args.user_agent, read_key(args.key_file) if args.key_file else None)
    args.candidates.mkdir(parents=True, exist_ok=True)
    failed = 0
    for path, asset, png, pixels in skins:
        candidate = args.candidates / (path.stem + ".json")
        if candidate.exists():
            existing_candidate(candidate, asset, png)
            print(f"skip    {path.name}: already signed")
            continue
        try:
            value, signature, skin = client.sign(path.name, png)
            url = texture_url(value)
            if not same_art(download_texture(url, args.user_agent), pixels):
                raise Refused("the signed texture is not this PNG's art")
        except (Refused, OSError, ValueError, zlib.error) as problem:
            failed += 1
            print(f"FAILED  {path.name}: {problem if isinstance(problem, Refused) else type(problem).__name__}")
            continue
        # The receipt goes first: the candidate is the file that marks this skin as done.
        write_whole(args.candidates / (path.stem + ".receipt.json"), {
            "key": asset, "source_sha256": hashlib.sha256(png).hexdigest(), "mineskin_uuid": skin.get("uuid"),
            "duplicate": skin.get("duplicate"), "visibility": skin.get("visibility"), "texture_url": url})
        write_whole(candidate, {"key": asset, "value": value, "signature": signature})
        print(f"signed  {path.name}{' (MineSkin already had this image)' if skin.get('duplicate') else ''}")
    print(f"{len(skins) - failed} of {len(skins)} skins have candidates. Check them with: skins prepare")
    return 1 if failed else 0


if __name__ == "__main__":
    try:
        sys.exit(main())
    except Refused as refused:
        print("Refused: " + str(refused), file=sys.stderr)
        sys.exit(1)
