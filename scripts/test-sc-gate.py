#!/usr/bin/env python3
"""Loopback sc-gate-dl contract fixture for native app tests.

Searches return fixed songs. A job finishes after one status poll; library jobs copy the
fixture MP3 into the music server's library folder, client jobs serve it once.
"""
import argparse
import json
import shutil
import threading
import uuid
from http.server import BaseHTTPRequestHandler, ThreadingHTTPServer
from pathlib import Path
from urllib.parse import parse_qs, urlparse

ROOT = Path(__file__).resolve().parent.parent
AUDIO = ROOT / "androidApp/src/androidTest/assets/fixture.mp3"
SONGS = [
    {"id": 1, "url": "https://soundcloud.com/fixture-artist/fixture-gate-song", "title": "Fixture Gate Song",
     "artist": "Fixture Artist", "uploader": "fixture-artist", "artworkUrl": None, "durationMs": 1000, "genre": "Fixture", "source": "gate"},
    {"id": 2, "url": "https://soundcloud.com/fixture-artist/no-gate-song", "title": "No Gate Song",
     "artist": "Fixture Artist", "uploader": "fixture-artist", "artworkUrl": None, "durationMs": 1000, "genre": None, "source": "none"},
]
jobs = {}
lock = threading.Lock()
library = Path()


def status(job):
    return {"id": job["id"], "soundcloudUrl": job["url"], "destination": job["destination"],
            "defaultMetadata": {"title": job["song"]["title"], "artist": job["song"]["artist"]},
            "progress": {"stage": job["stage"], "message": job["stage"].replace("_", " "), "percent": 100 if job["stage"] == "ready" else 50},
            "libraryFilename": job.get("libraryFilename"), "outputFilename": job.get("outputFilename"),
            "error": None}


def finish(job):
    name = f'{job["song"]["artist"]} - {job["song"]["title"]}.mp3'
    if job["destination"] == "library":
        library.mkdir(parents=True, exist_ok=True)
        target = library / name
        index = 2
        while target.exists():
            target = library / f'{job["song"]["artist"]} - {job["song"]["title"]} ({index}).mp3'
            index += 1
        shutil.copyfile(AUDIO, target)
        job["libraryFilename"] = target.name
    else:
        job["outputFilename"] = name
    job["stage"] = "ready"


class Handler(BaseHTTPRequestHandler):
    def log_message(self, *_):
        pass

    def respond(self, status_code, payload):
        body = json.dumps(payload).encode()
        self.send_response(status_code)
        self.send_header("Content-Type", "application/json")
        self.send_header("Content-Length", str(len(body)))
        self.end_headers()
        self.wfile.write(body)

    def body(self):
        length = int(self.headers.get("Content-Length", "0"))
        if not 0 <= length <= 64 * 1024:
            raise ValueError()
        return json.loads(self.rfile.read(length) or b"{}")

    def job(self, parts):
        with lock:
            return jobs.get(parts[2]) if len(parts) >= 3 else None

    def do_GET(self):
        address = urlparse(self.path)
        parts = address.path.strip("/").split("/")
        if address.path == "/api/capabilities":
            return self.respond(200, {"browserModes": ["headless"], "browserViewUrl": None, "search": True, "libraryDestination": True})
        if address.path == "/api/search":
            query = parse_qs(address.query)
            text = query.get("q", [""])[0].strip().lower()
            try:
                limit = int(query.get("limit", ["20"])[0])
                offset = int(query.get("offset", ["0"])[0])
            except ValueError:
                return self.respond(400, {"error": "limit and offset must be numbers"})
            if not text or not 1 <= limit <= 50 or offset < 0:
                return self.respond(400, {"error": "q is required"})
            matches = [song for song in SONGS if text in song["title"].lower() or text in song["artist"].lower()]
            page = matches[offset:offset + limit]
            return self.respond(200, {"results": page, "nextOffset": offset + limit if offset + limit < len(matches) else None})
        job = self.job(parts)
        if parts[:2] != ["api", "job"] or job is None:
            return self.respond(404, {"error": "Job not found"})
        if len(parts) == 3:
            with lock:
                payload = status(job)
                # The download takes one poll, so clients see progress before the finished file.
                if job["stage"] == "downloading":
                    finish(job)
            return self.respond(200, payload)
        if parts[3:] == ["file"] and job.get("outputFilename"):
            body = AUDIO.read_bytes()
            self.send_response(200)
            self.send_header("Content-Type", "audio/mpeg")
            self.send_header("Content-Length", str(len(body)))
            self.send_header("Content-Disposition", f'attachment; filename="{job["outputFilename"]}"')
            self.end_headers()
            self.wfile.write(body)
            return None
        return self.respond(404, {"error": "No file available"})

    def do_POST(self):
        parts = urlparse(self.path).path.strip("/").split("/")
        try:
            payload = self.body()
        except ValueError:
            return self.respond(400, {"error": "Invalid JSON"})
        if parts == ["api", "job"]:
            song = next((song for song in SONGS if song["url"] == payload.get("soundcloudUrl")), None)
            if song is None:
                return self.respond(400, {"error": "Failed to fetch track: not found"})
            if payload.get("outputFormat") not in ("mp3-320", "flac") or payload.get("destination") not in ("library", "client"):
                return self.respond(400, {"error": "Invalid format or destination"})
            needs_link = song["source"] == "none"
            job = {"id": str(uuid.uuid4()), "url": song["url"], "song": song, "destination": payload["destination"],
                   "stage": "waiting_hypeddit" if needs_link else "downloading"}
            with lock:
                jobs[job["id"]] = job
            return self.respond(200, {"jobId": job["id"], "track": None, "hypedditUrl": None, "defaultMetadata": status(job)["defaultMetadata"],
                                      "outputFormat": payload["outputFormat"], "destination": job["destination"],
                                      "soundcloudDownloadWarning": None, "needsHypedditUrl": needs_link, "started": not needs_link})
        job = self.job(parts)
        if parts[:2] != ["api", "job"] or job is None or len(parts) != 4:
            return self.respond(404, {"error": "Job not found"})
        with lock:
            if parts[3] == "hypeddit":
                if not payload.get("hypedditUrl"):
                    return self.respond(400, {"error": "Gate URL (hypedditUrl) is required"})
                job["link"] = payload["hypedditUrl"]
                job["stage"] = "pending"
                return self.respond(200, {"success": True, "hypedditUrl": job["link"]})
            if parts[3] == "start":
                if job["stage"] not in ("pending", "error", "cancelled") or (job["song"]["source"] == "none" and not job.get("link")):
                    return self.respond(400, {"error": "Job is already in progress or completed"})
                job["stage"] = "downloading"
                return self.respond(200, {"success": True, "queued": False, "message": "Download started"})
            if parts[3] == "cancel":
                job["stage"] = "cancelled"
                return self.respond(200, {"success": True, "message": "Download cancelled"})
        return self.respond(404, {"error": "Not found"})


if __name__ == "__main__":
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--port", type=int, default=14538)
    parser.add_argument("--library", type=Path, default=ROOT / ".test-environment/sftp-music/SoundCloud",
                        help="Library folder of the music server fixture")
    options = parser.parse_args()
    library = options.library
    ThreadingHTTPServer(("127.0.0.1", options.port), Handler).serve_forever()
