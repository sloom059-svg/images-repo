#!/usr/bin/env python3
"""Append approved YouTube Me channels from Nibras' bot inbox, preserving the live config."""
import json
import re
import urllib.request
from pathlib import Path

SOURCE_URL = "https://raw.githubusercontent.com/sloom059-svg/nibras-catalog/main/youtube-me/channels.json"
TARGET = Path("curated-tv/channels.json")
REQUEST = urllib.request.Request(SOURCE_URL, headers={"User-Agent": "YouTube-Me-GitHub-Sync"})
with urllib.request.urlopen(REQUEST, timeout=25) as response:
    source = json.load(response)
live = json.loads(TARGET.read_text(encoding="utf-8"))
if not isinstance(source, dict) or not isinstance(source.get("channels"), list):
    raise ValueError("Refusing to sync malformed source")
if not isinstance(live.get("channels"), list):
    raise ValueError("Refusing to overwrite malformed live file")

known = {c.get("channelId") for c in live["channels"] if isinstance(c, dict)}
added = []
for entry in source["channels"]:
    if not isinstance(entry, dict):
        continue
    channel_id = str(entry.get("channelId", "")).strip()
    channel_name = str(entry.get("name", "")).strip()
    if not re.fullmatch(r"UC[A-Za-z0-9_-]{22}", channel_id):
        continue
    if not channel_name or len(channel_name) > 100 or channel_id in known:
        continue
    live["channels"].append({"name": channel_name, "channelId": channel_id})
    known.add(channel_id)
    added.append(channel_name)

if added:
    live["version"] = int(live.get("version", 0)) + 1
    TARGET.write_text(json.dumps(live, ensure_ascii=False, indent=2) + "\n", encoding="utf-8")
    print(f"Added {len(added)} channel(s): " + ", ".join(added))
else:
    print("No new channels; live config unchanged")
