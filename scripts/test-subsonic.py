#!/usr/bin/env python3
"""Run an owned, loopback-only gonic fixture for Subsonic acceptance tests."""
import argparse
import hashlib
import json
import os
from pathlib import Path
import subprocess
import time
import urllib.request
import urllib.parse
from test_containers import image

ROOT = Path(__file__).resolve().parent.parent
DIRECTORY = ROOT / ".test-environment/subsonic"
NAME = "nevumi-subsonic-test"
LABEL = "me.d3sox.nevumi.subsonic-test"
ADDRESS = "http://127.0.0.1:14537"

def owned():
    result = subprocess.run(["docker", "inspect", NAME], capture_output=True, text=True)
    if result.returncode:
        if "no such object" in result.stderr.lower(): return None
        raise RuntimeError(result.stderr)
    container = json.loads(result.stdout)[0]
    mounts = {item["Destination"]: item for item in container["Mounts"]}
    if container["Config"]["Labels"].get(LABEL) != "true" or any(
        path not in mounts or Path(mounts[path]["Source"]).resolve() != DIRECTORY / name
        for path, name in [("/music", "music"), ("/data", "data")]
    ) or mounts["/music"]["RW"] or container["HostConfig"]["PortBindings"].get("4747/tcp") != [
        {"HostIp": "127.0.0.1", "HostPort": "14537"}
    ]:
        raise RuntimeError("The Subsonic container belongs to another environment")
    return container

def request(endpoint, **extra):
    salt = "nevumi-subsonic-fixture"
    parameters = dict(u="admin", t=hashlib.md5(("admin" + salt).encode()).hexdigest(), s=salt,
                      v="1.13.0", c="Nevumi-test", f="json", **extra)
    with urllib.request.urlopen(ADDRESS + "/rest/" + endpoint + ".view?" + urllib.parse.urlencode(parameters), timeout=2) as response:
        return json.load(response)["subsonic-response"]

# gonic downloads podcasts itself, so the feed is served inside its container, where only gonic reaches it.
FEED = "http://127.0.0.1:8080/podcast.xml"
EPISODES = [("episode-1", "Fixture episode one", "Sat, 02 Nov 2024 10:00:00 GMT"),
            ("episode-2", "Fixture episode two", "Sat, 09 Nov 2024 10:00:00 GMT")]
FEED_SERVER = r"""#!/bin/sh
read -r method path version
while read -r line; do [ -z "$(printf '%s' "$line" | tr -d '\r')" ] && break; done
case "${path%%\?*}" in
    /podcast.xml) file=/data/feeds/podcast.xml; type=application/rss+xml ;;
    /episode-1.mp3|/episode-2.mp3) file="/data/feeds${path%%\?*}"; type=audio/mpeg ;;
    *) printf 'HTTP/1.0 404 Not Found\r\nContent-Length: 0\r\n\r\n'; exit 0 ;;
esac
printf 'HTTP/1.0 200 OK\r\nContent-Type: %s\r\nContent-Length: %s\r\n\r\n' "$type" "$(wc -c < "$file" | tr -d ' ')"
cat "$file"
"""

def prepare_feed():
    feeds = DIRECTORY / "data/feeds"
    feeds.mkdir(parents=True, exist_ok=True)
    items = ""
    for index, (name, title, date) in enumerate(EPISODES):
        file = feeds / f"{name}.mp3"
        if not file.exists():
            # Two minutes of a quiet tone, so an episode keeps playing while a test or a person looks at the player.
            subprocess.run(["ffmpeg", "-v", "error", "-f", "lavfi", "-i", f"sine=frequency={330 + index * 110}:duration=120",
                            "-af", "volume=0.1", "-c:a", "libmp3lame", "-b:a", "64k", "-ac", "1",
                            "-metadata", f"title={title}", str(file)], check=True)
        items += f"""
    <item>
      <title>{title}</title>
      <guid>nevumi-fixture-{name}</guid>
      <description>A short fixture episode.</description>
      <pubDate>{date}</pubDate>
      <enclosure url="http://127.0.0.1:8080/{name}.mp3" length="{file.stat().st_size}" type="audio/mpeg"/>
      <itunes:duration>120</itunes:duration>
    </item>"""
    (feeds / "podcast.xml").write_text(f"""<?xml version="1.0" encoding="UTF-8"?>
<rss version="2.0" xmlns:itunes="http://www.itunes.com/dtds/podcast-1.0.dtd">
  <channel>
    <title>Nevumi Fixture Podcast</title>
    <link>http://127.0.0.1:8080/</link>
    <description>Episodes for the Subsonic acceptance tests.</description>
    <itunes:author>Fixture Host</itunes:author>{items}
  </channel>
</rss>
""")
    server = feeds / "serve.sh"
    server.write_text(FEED_SERVER)
    server.chmod(0o755)

def serve_feed():
    running = subprocess.run(["docker", "exec", NAME, "pgrep", "-f", "nc -lk"], capture_output=True)
    if running.returncode:
        subprocess.run(["docker", "exec", "--detach", NAME, "nc", "-lk", "-s", "127.0.0.1", "-p", "8080", "-e", "/data/feeds/serve.sh"],
                       check=True, stdout=subprocess.DEVNULL)

def start():
    existing = owned()
    for name in ["music", "data/cache", "data/playlists", "data/podcasts"]: (DIRECTORY / name).mkdir(parents=True, exist_ok=True)
    prepare_feed()
    for index, title in enumerate(["Northern Light", "Night Walk", "Long Way Home"]):
        file = DIRECTORY / "music" / "Fixture Artist" / ("Sky" if index < 2 else "Road") / f"{title}.flac"
        file.parent.mkdir(parents=True, exist_ok=True)
        if not file.exists():
            subprocess.run(["ffmpeg", "-v", "error", "-i", str(ROOT / "androidApp/src/androidTest/assets/fixture.wav"),
                            "-metadata", f"title={title}", "-metadata", "artist=Fixture Artist", "-metadata", f"album={file.parent.name}",
                            "-metadata", "genre=Electronic", "-metadata", "date=2024", "-c:a", "flac", str(file)], check=True)
    if existing is None:
        subprocess.run(["docker", "run", "--detach", "--name", NAME, "--label", LABEL + "=true",
                        "--user", f"{os.getuid()}:{os.getgid()}", "--publish", "127.0.0.1:14537:4747",
                        "--mount", f"type=bind,src={DIRECTORY / 'music'},dst=/music,readonly",
                        "--mount", f"type=bind,src={DIRECTORY / 'data'},dst=/data",
                        "--env", "GONIC_LISTEN_ADDR=0.0.0.0:4747", "--env", "GONIC_MUSIC_PATH=/music",
                        "--env", "GONIC_CACHE_PATH=/data/cache", "--env", "GONIC_DB_PATH=/data/gonic.db",
                        "--env", "GONIC_PODCAST_PATH=/data/podcasts", "--env", "GONIC_PLAYLISTS_PATH=/data/playlists",
                        "--env", "GONIC_SCAN_AT_START_ENABLED=true", image("subsonic")], check=True, stdout=subprocess.DEVNULL)
    elif not existing["State"]["Running"]:
        subprocess.run(["docker", "start", NAME], check=True, stdout=subprocess.DEVNULL)
    for _ in range(60):
        try:
            response = request("getAlbumList2", type="alphabeticalByName", size="500")
            if response.get("status") == "ok" and len(response.get("albumList2", {}).get("album", [])) == 2:
                serve_feed()
                print("Subsonic fixture ready at " + ADDRESS)
                return
        except (OSError, ValueError): pass
        time.sleep(1)
    raise RuntimeError("Subsonic fixture did not finish scanning")

def stop():
    if owned() is not None: subprocess.run(["docker", "stop", NAME], check=True, stdout=subprocess.DEVNULL)

if __name__ == "__main__":
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("action", choices=["start", "stop"])
    arguments = parser.parse_args()
    globals()[arguments.action]()
