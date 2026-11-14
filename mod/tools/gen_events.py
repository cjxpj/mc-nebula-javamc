#!/usr/bin/env python3
"""Generate the NeoForge event catalog shipped inside the mod jar.

The catalog is a build-time snapshot of every event class in the NeoForge
universal jar. At runtime EventBridge loads it and registers listeners only
for the event names that word libraries actually subscribe to, so the catalog
can cover the whole event bus without paying for 150+ permanent listeners.

Usage:
    python mod/tools/gen_events.py
    python mod/tools/gen_events.py --check
    python mod/tools/gen_events.py --server-dir ../server-1.21.11

The catalog must come from the SAME NeoForge version as the target server;
a catalog generated from 1.21.1 is useless on 1.21.11.

--check exits non-zero when the checked-in catalog is stale. CI / regress.py
can call it to catch a NeoForge upgrade that was not followed by a rebuild.
"""

from __future__ import annotations

import argparse
import json
import re
import sys
import zipfile
from pathlib import Path

ROOT = Path(__file__).resolve().parents[2]
DEFAULT_SERVER = ROOT / "server-1.21.11"
OUTPUT = ROOT / "mod" / "src" / "main" / "resources" / "nebula" / "events.json"

EVENT_PREFIX = "net/neoforged/neoforge/event/"
INNER_SEP = "$"

# Events published once on the mod loading bus or only on the client side.
# They are kept in the catalog for completeness, but EventBridge logs a hint
# when a word library subscribes to one of them on a dedicated server.
LOAD_TIME_HINTS = (
    "AddPackFindersEvent",
    "AddServerReloadListenersEvent",
    "SortedReloadListenerEvent",
    "RegisterGameRuleCategoryEvent",
    "BuildCreativeModeTabContentsEvent",
    "BlockEntityTypeAddBlocksEvent",
    "EntityAttributeCreationEvent",
    "EntityAttributeModificationEvent",
    "RegisterCommandsEvent",
    "RegisterGameTestsEvent",
    "RegisterStructureConversionsEvent",
    "ModifyDefaultComponentsEvent",
    "RegisterBrewingRecipesEvent",
    "RegisterSpawnPlacementsEvent",
    "ModMismatchEvent",
    "TagsUpdatedEvent",
    "ServerAboutToStartEvent",
    "ServerStartingEvent",
    "ServerStoppedEvent",
    "GameShuttingDownEvent",
)

# Every tick, per entity / per level. Subscribing to these is throttled by the
# host to one dispatch per 20 ticks unless the plugin opts into a higher rate.
HIGH_FREQUENCY = (
    "EntityTickEvent",
    "LevelTickEvent",
    "PlayerTickEvent",
    "ServerTickEvent",
)


def find_universal_jar(server: Path) -> Path:
    libraries = server / "libraries" / "net" / "neoforged" / "neoforge"
    candidates = sorted(libraries.glob("*/*-universal.jar"))
    if not candidates:
        raise SystemExit(f"no NeoForge universal jar under {libraries}")
    return candidates[-1]


def catalog_from(jar: Path) -> dict:
    version = jar.parent.name
    with zipfile.ZipFile(jar) as zf:
        classes = sorted(
            name[: -len(".class")].replace("/", ".")
            for name in zf.namelist()
            if name.startswith(EVENT_PREFIX)
            and name.endswith(".class")
            and not name.endswith("package-info.class")
        )

    events = []
    by_name: dict[str, str] = {}
    for fqcn in classes:
        if fqcn.endswith("$1") or fqcn.endswith("EventHooks"):
            continue
        short = fqcn.rsplit(".", 1)[-1]
        # Inner classes use $ in Java; word libraries use a dot instead.
        name = short.replace(INNER_SEP, ".")
        base = name.split(".", 1)[0]
        if name in by_name and by_name[name] != fqcn:
            # Two packages exposing the same simple name: qualify with the
            # last package segment so both remain addressable.
            package = fqcn.rsplit(".", 1)[0].rsplit(".", 1)[-1]
            name = f"{package}.{name}"
        by_name[name] = fqcn
        events.append(
            {
                "name": name,
                "class": fqcn,
                "throttle": 20 if base in HIGH_FREQUENCY else 0,
                "hint": "load-time" if base in LOAD_TIME_HINTS else "",
            }
        )

    events.sort(key=lambda e: e["name"])
    return {
        "neoforge": version,
        "generatedFrom": jar.name,
        "count": len(events),
        "events": events,
    }


def main() -> int:
    ap = argparse.ArgumentParser()
    ap.add_argument("--check", action="store_true", help="only verify the catalog is current")
    ap.add_argument("--server-dir", default=str(DEFAULT_SERVER),
                    help="server directory whose libraries/ holds the NeoForge universal jar")
    args = ap.parse_args()

    jar = find_universal_jar(Path(args.server_dir).resolve())
    fresh = catalog_from(jar)
    text = json.dumps(fresh, ensure_ascii=False, indent=2) + "\n"

    if args.check:
        if not OUTPUT.exists():
            print(f"MISSING: {OUTPUT}")
            return 1
        if OUTPUT.read_text(encoding="utf-8") != text:
            print(f"STALE: {OUTPUT} does not match {jar.name}")
            return 1
        print(f"OK: {fresh['count']} events, NeoForge {fresh['neoforge']}")
        return 0

    OUTPUT.parent.mkdir(parents=True, exist_ok=True)
    OUTPUT.write_text(text, encoding="utf-8")
    print(f"Wrote {OUTPUT} ({fresh['count']} events, NeoForge {fresh['neoforge']})")
    return 0


if __name__ == "__main__":
    sys.exit(main())
