#!/usr/bin/env python3
"""Serve an endless internet radio stream with ICY titles on loopback for the internet radio acceptance tests."""
from http.server import BaseHTTPRequestHandler, ThreadingHTTPServer
from pathlib import Path
import subprocess
import time

ROOT = Path(__file__).resolve().parent.parent
TONE = ROOT / ".test-environment/radio-stream/tone.mp3"
ADDRESS = ("127.0.0.1", 14539)
STREAM_TITLES = ["Fixture Artist - Live Song One", "Fixture Artist - Live Song Two"]
METADATA_INTERVAL = 8192
BYTES_PER_SECOND = 16_000


def tone():
    if not TONE.exists():
        TONE.parent.mkdir(parents=True, exist_ok=True)
        # Without tags or a Xing header, consecutive copies of the file form one valid stream.
        subprocess.run(["ffmpeg", "-v", "error", "-f", "lavfi", "-i", "sine=frequency=440:duration=5", "-af", "volume=0.1",
                        "-c:a", "libmp3lame", "-b:a", "128k", "-ar", "44100", "-ac", "1", "-id3v2_version", "0", "-write_xing", "0",
                        "-y", str(TONE)], check=True)
    return TONE.read_bytes()


class Handler(BaseHTTPRequestHandler):
    protocol_version = "HTTP/1.0"

    def log_message(self, *arguments): pass

    def do_GET(self):
        if self.path != "/radio": return self.send_error(404)
        metadata = self.headers.get("Icy-MetaData") == "1"
        self.send_response(200)
        self.send_header("Content-Type", "audio/mpeg")
        self.send_header("icy-name", "Nevumi Fixture Radio")
        if metadata: self.send_header("icy-metaint", str(METADATA_INTERVAL))
        self.end_headers()
        audio = self.server.tone
        started = time.monotonic()
        sent = offset = 0
        until_metadata = METADATA_INTERVAL
        try:
            while True:
                # A two second head start fills the player's buffer; after it the stream runs in real time.
                ahead = sent - (time.monotonic() - started + 2) * BYTES_PER_SECOND
                if ahead > 0: time.sleep(ahead / BYTES_PER_SECOND)
                count = min(4096, until_metadata) if metadata else 4096
                self.wfile.write((audio[offset:] + audio)[:count])
                offset = (offset + count) % len(audio)
                sent += count
                until_metadata -= count
                if metadata and until_metadata == 0:
                    # The title changes every ten seconds, so a player can show that it follows the stream.
                    title = STREAM_TITLES[int((time.monotonic() - started) // 10) % len(STREAM_TITLES)]
                    text = f"StreamTitle='{title}';".encode()
                    blocks = (len(text) + 15) // 16
                    self.wfile.write(bytes([blocks]) + text.ljust(blocks * 16, b"\0"))
                    until_metadata = METADATA_INTERVAL
        except (BrokenPipeError, ConnectionResetError):
            pass


if __name__ == "__main__":
    server = ThreadingHTTPServer(ADDRESS, Handler)
    server.daemon_threads = True
    server.tone = tone()
    print("Radio stream at http://%s:%d/radio" % ADDRESS, flush=True)
    server.serve_forever()
