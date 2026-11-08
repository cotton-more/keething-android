#!/usr/bin/env python3
"""The large-database measurement before a release (keething-spec app.large-database AC6; DEVELOPMENT.md).

    tools/large-measure.py files [--fresh]
    tools/large-measure.py measure [--heap]
    tools/large-measure.py summary <log>

files: makes the measurement's databases with task large:database's generator (a fixed seed, password pw) into
build/large, those not there yet (--fresh: all again), and pushes them to the phone's Documents/Large, so each
release measures the same bytes. Lock the app first: a database is replaced under it.

measure: needs the measurement build (task apk:timings). Follows the app's timing lines (Session, List, Card) while
the screens are driven; Ctrl-C ends it, keeps the log in build/large and prints each scenario, a scenario being what
happened between two Lock alls. The log names a database by an id, never its file; the summary names it by its size in
MiB, the file of build/large of that size. AC1's budgets are judged where large-5000 is the only one open: listed
within 3 s of Unlock, a search keystroke within 100 ms, a card within 300 ms, a save within 4 s. A save that failed fails anywhere (AC2). The exit
status is 1 when something failed. --heap also samples the Java heap (dumpsys meminfo's Dalvik Heap, Heap Alloc)
about every 0.3 s; each sample makes the app collect its garbage, which slows a save by about 0.5 s, so the times of a
--heap run are printed but not judged: time in one run, the heap in another.

summary: prints the same from a kept log.
"""
import argparse
import datetime
import os
import re
import signal
import subprocess
import sys
import threading
import time

PACKAGE = "dev.keething.android"
PHONE_FOLDER = "/sdcard/Documents/Large"
ROOT = os.path.dirname(os.path.dirname(os.path.abspath(__file__)))
FOLDER = os.path.join(ROOT, "build", "large")

# name: the generator's entries, attachments MiB (plans/android-large-database.md §1 in keething-spec).
FILES = {
    "large-5000.kdbx": [],  # AC1: 5,000 entries, 40 MiB of attachments, Argon2 64 MiB × 2 (the defaults)
    "large-90.kdbx": ["10350", "87"],  # AC2 alone: 89.8 MiB
    "large-80.kdbx": ["9200", "77"],  # AC2 beside AC1's: 79.5 MiB
    "large-100.kdbx": ["11500", "97"],  # AC3: read-only alone, 100.1 MiB
}

AC1 = "large-5000.kdbx"
BUDGET_LISTED = 3.0
BUDGET_SEARCH = 100
BUDGET_CARD = 300
BUDGET_SAVE = 4000

LINE = re.compile(r"^\s*(\d+\.\d+)\s+\d+\s+\d+\s+[VDIWEF]\s+(\S+)\s*:\s?(.*)$")
HEAP = re.compile(r"^#heap (\d+\.\d+) (\d+)$")


def adb(*args, **kwargs):
    return subprocess.run(["adb", *args], check=True, **kwargs)


def files(fresh):
    os.makedirs(FOLDER, exist_ok=True)
    for name, arguments in FILES.items():
        path = os.path.join(FOLDER, name)
        if fresh or not os.path.exists(path):
            print(f"making {name}", flush=True)
            if os.path.exists(path):
                os.remove(path)
            subprocess.run([os.path.join(ROOT, "gradlew"), "-q", "--console=plain", ":core:kdbx:makeLargeDatabase",
                            "-Parguments=" + " ".join([path, *arguments])], cwd=ROOT, check=True)
    adb("shell", "mkdir", "-p", PHONE_FOLDER)
    for name in FILES:
        print(f"pushing {name} ({os.path.getsize(os.path.join(FOLDER, name)) / 2**20:.1f} MiB)", flush=True)
        adb("push", "-q", os.path.join(FOLDER, name), f"{PHONE_FOLDER}/{name}")
    print(f"Done: in {PHONE_FOLDER}; add each in the app once (⋮ › Databases), password pw.")


def clock_offset():
    """The phone's clock minus this Mac's, in seconds: the heap samples are timed here, the log lines there."""
    before = time.time()
    phone = float(adb("shell", "date", "+%s.%N", capture_output=True, text=True).stdout.strip())
    after = time.time()
    return phone - (before + after) / 2


def sample_heap(out, offset, stopping):
    while not stopping.is_set():
        result = subprocess.run(["adb", "shell", "dumpsys", "meminfo", PACKAGE], capture_output=True, text=True)
        taken = time.time() + offset
        for line in result.stdout.splitlines():
            if line.strip().startswith("Dalvik Heap"):
                out.write(f"#heap {taken:.3f} {line.split()[-2]}\n")
                out.flush()
                break
        stopping.wait(0.3)


def measure(heap):
    os.makedirs(FOLDER, exist_ok=True)
    path = os.path.join(FOLDER, datetime.datetime.now().strftime("measure-%Y%m%d-%H%M%S.log"))
    if subprocess.run(["adb", "shell", "pidof", PACKAGE], capture_output=True).returncode != 0:
        print("Note: the app is not running; start it.")
    adb("logcat", "-c")
    stopping = threading.Event()
    with open(path, "w") as out:
        out.write(f"#heap-run {1 if heap else 0}\n")
        logcat = subprocess.Popen(["adb", "logcat", "-v", "epoch", "-s", "Session:I", "List:I", "Card:I"],
                                  stdout=subprocess.PIPE, text=True, start_new_session=True)
        sampler = None
        if heap:
            sampler = threading.Thread(target=sample_heap, args=(out, clock_offset(), stopping), daemon=True)
            sampler.start()
        print(f"Following the timing lines into {os.path.relpath(path, ROOT)}" + (" with the heap" if heap else "") +
              "; drive the screens, Lock all between scenarios, Ctrl-C to end.", flush=True)
        try:
            for line in logcat.stdout:
                if LINE.match(line):
                    out.write(line)
                    out.flush()
                    print(line.rstrip(), flush=True)
        except KeyboardInterrupt:
            pass
        finally:
            stopping.set()
            os.killpg(logcat.pid, signal.SIGTERM)
            if sampler:
                sampler.join(2)
    print()
    return summary(path)


class Scenario:
    def __init__(self, number):
        self.number = number
        self.unlocking = {}  # name: when its unlock began
        self.databases = {}  # id: {entries, mib, room, key, unlock, unlocked, listed}
        self.listings = []  # (when, ms, entries)
        self.searches = []
        self.cards = []
        self.saves = []  # (id, when it ended, ms, done)
        self.heap = []  # (when, kB)
        self.waiting = []  # ids unlocked, their listing not yet seen

    def empty(self):
        return not (self.databases or self.searches or self.cards or self.saves)


def parse(path):
    scenarios = [Scenario(1)]
    heap_run = False
    with open(path) as f:
        for line in f:
            s = scenarios[-1]
            if line.startswith("#heap-run"):
                heap_run = line.split()[1] == "1"
                continue
            if m := HEAP.match(line.strip()):
                s.heap.append((float(m[1]), int(m[2])))
                continue
            m = LINE.match(line)
            if not m:
                continue
            when, tag, text = float(m[1]), m[2], m[3]
            if tag == "Session":
                if text.startswith("lock all"):
                    if not s.empty():
                        scenarios.append(Scenario(s.number + 1))
                    continue
                name, _, state = text.partition(" ")
                if state == "unlocking":
                    s.unlocking[name] = when
                    s.waiting = []  # their listing waits for this one's password: not timed
                elif u := re.match(r"unlocked: (\d+) entries(?:, key derived in (\d+) ms)?, (\d+) MiB, room (.*)", state):
                    if name in s.databases and name not in s.unlocking:
                        # The same session again after a save or a reload: its counts only.
                        d = s.databases[name]
                        d["entries"] = int(u[1])
                        if u[4] != d["rooms"][-1]:
                            d["rooms"].append(u[4])  # Near acknowledged (OK) reads Enough; a thaw too
                        continue
                    began = s.unlocking.pop(name, None)
                    s.databases[name] = dict(entries=int(u[1]), key=int(u[2]) if u[2] else None, mib=int(u[3]),
                                             rooms=[u[4]], unlock=when - began if began is not None else None,
                                             unlocked=when, listed=None)
                    s.waiting.append(name)
                elif v := re.match(r"save (done|failed) in (\d+) ms", state):
                    s.saves.append((name, when, int(v[2]), v[1] == "done"))
            elif tag == "List":
                if v := re.match(r"listing prepared in (\d+) ms: (\d+) entries", text):
                    s.listings.append((when, int(v[1]), int(v[2])))
                    # The listing that holds every database unlocked so far (one prepared before the last one came
                    # in lists fewer entries).
                    if int(v[2]) >= sum(d["entries"] for d in s.databases.values()):
                        for name in s.waiting:
                            s.databases[name]["listed"] = when - s.databases[name]["unlocked"]
                        s.waiting = []
                elif v := re.match(r"search of (\d+) characters in (\d+) ms", text):
                    s.searches.append(int(v[2]))
            elif tag == "Card":
                if v := re.match(r"built in (\d+) ms", text):
                    s.cards.append(int(v[1]))
    return [s for s in scenarios if not s.empty()], heap_run


def file_names():
    """MiB as the log says them (the size shifted right by 20): the file of build/large of that size."""
    names = {}
    for name in FILES:
        path = os.path.join(FOLDER, name)
        if os.path.exists(path):
            names[os.path.getsize(path) >> 20] = name
    return names


def summary(path):
    scenarios, heap_run = parse(path)
    names_by_size = file_names()
    failed = []

    def judge(ok, what):
        if heap_run:
            return ""
        if not ok:
            failed.append(what)
        return "  ✓" if ok else "  ✗ over the budget"

    def span(values, unit=" ms"):
        return f"{min(values)}–{max(values)}{unit}" if len(values) > 1 else f"{values[0]}{unit}"

    if not scenarios:
        print("Nothing measured: no timing lines (is the measurement build installed? task apk:timings).")
        return 1
    for s in scenarios:
        called = {i: names_by_size.get(d["mib"], i) for i, d in s.databases.items()}
        ids = list(s.databases)  # in the order unlocked
        alone = len(ids) == 1 and called[ids[0]] == AC1
        print(f"Scenario {s.number}: " + (", ".join(called[i] for i in ids) or "no unlock seen") +
              (" (AC1's, alone)" if alone else ""))
        for i in ids:
            d = s.databases[i]
            text = f"  {called[i]}: {d['entries']:,} entries, {d['mib']} MiB, room {', then '.join(d['rooms'])}"
            if d["unlock"] is not None:
                text += f"; unlock {d['unlock']:.2f} s" + (f" (key {d['key']} ms)" if d["key"] is not None else "")
            if d["listed"] is not None:
                text += f", listed {d['listed']:.2f} s after it"
                if alone and d["unlock"] is not None:
                    total = d["unlock"] + d["listed"]
                    text += f": {total:.2f} s from Unlock" + judge(total <= BUDGET_LISTED,
                                                                   f"scenario {s.number}: listed {total:.2f} s from Unlock")
            print(text)
        if s.searches:
            print(f"  search: {len(s.searches)} keystrokes, {span(s.searches)}" +
                  (judge(max(s.searches) <= BUDGET_SEARCH, f"scenario {s.number}: a search {max(s.searches)} ms")
                   if alone else ""))
        if s.cards:
            print(f"  card: {len(s.cards)} opened, {span(s.cards)}" +
                  (judge(max(s.cards) <= BUDGET_CARD, f"scenario {s.number}: a card {max(s.cards)} ms") if alone else ""))
        for name, ended, ms, done in s.saves:
            name = called.get(name, name)
            text = f"  save of {name}: " + (f"done in {ms / 1000:.1f} s" if done else f"FAILED after {ms / 1000:.1f} s")
            if not done:
                failed.append(f"scenario {s.number}: a save of {name} failed")
            elif alone:
                text += judge(ms <= BUDGET_SAVE, f"scenario {s.number}: a save {ms / 1000:.1f} s")
            peak = [kb for t, kb in s.heap if ended - ms / 1000 - 0.3 <= t <= ended + 0.3]
            if peak:
                text += f"; heap at its peak {max(peak) / 1024:.0f} MiB"
            print(text)
        if s.heap:
            saving = [(e - ms / 1000 - 0.3, e + 0.3) for _, e, ms, _ in s.saves]
            idle = [kb for t, kb in s.heap if not any(a <= t <= b for a, b in saving)]
            print(f"  heap: peak {max(kb for _, kb in s.heap) / 1024:.0f} MiB" +
                  (f", outside saves {max(idle) / 1024:.0f} MiB" if idle else "") + f" ({len(s.heap)} samples)")
        print()
    if heap_run:
        print("A heap run: the times are slowed by the sampling and not judged.")
    if failed:
        print("Over the budget or failed:\n  " + "\n  ".join(failed))
        return 1
    print("Every budget judged was met." if not heap_run else "No save failed.")
    return 0


def main():
    parser = argparse.ArgumentParser(description=__doc__, formatter_class=argparse.RawDescriptionHelpFormatter)
    commands = parser.add_subparsers(dest="command", required=True)
    making = commands.add_parser("files")
    making.add_argument("--fresh", action="store_true")
    measuring = commands.add_parser("measure")
    measuring.add_argument("--heap", action="store_true")
    summing = commands.add_parser("summary")
    summing.add_argument("log")
    args = parser.parse_args()
    if args.command == "files":
        files(args.fresh)
    elif args.command == "measure":
        sys.exit(measure(args.heap))
    else:
        sys.exit(summary(args.log))


if __name__ == "__main__":
    main()
