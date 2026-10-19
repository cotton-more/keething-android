#!/usr/bin/env python3
"""Wireless adb to a phone through a local TCP forwarder.

On this Mac the Google-signed adb gets "No route to host" for addresses on the local network (macOS Local
Network privacy; adb never shows up in that list, so it cannot be allowed). Python can reach the phone, so
this script listens on 127.0.0.1 and forwards to the phone; adb only ever talks to 127.0.0.1.

    tools/adb-wireless.py <phone> [--pair PORT CODE] [--port PORT] [--local 15555]

<phone> is the phone's IP or its .local name. --pair takes the port and code of the phone's "Pair device
with pairing code" dialog (needed once per Mac). The connect port (main Wireless debugging screen) rotates;
without --port it is looked up over Bonjour. The script stays in the foreground and keeps forwarding;
adb then sees the phone as 127.0.0.1:<local> (Ctrl-C ends it).
"""
import argparse
import re
import socket
import subprocess
import sys
import threading
import time


def pipe(source, target):
    try:
        while data := source.recv(65536):
            target.sendall(data)
    except OSError:
        pass
    finally:
        for s in (source, target):
            try:
                s.shutdown(socket.SHUT_RDWR)
            except OSError:
                pass


def forward(local_port, host, remote_port):
    """Listens on 127.0.0.1:local_port in a daemon thread, forwarding every connection to host:remote_port."""
    server = socket.socket()
    server.setsockopt(socket.SOL_SOCKET, socket.SO_REUSEADDR, 1)
    server.bind(("127.0.0.1", local_port))
    server.listen()

    def accept():
        while True:
            client, _ = server.accept()
            try:
                upstream = socket.create_connection((host, remote_port), timeout=5)
                upstream.settimeout(None)
            except OSError as error:
                print(f"cannot reach {host}:{remote_port}: {error}", file=sys.stderr, flush=True)
                client.close()
                continue
            threading.Thread(target=pipe, args=(client, upstream), daemon=True).start()
            threading.Thread(target=pipe, args=(upstream, client), daemon=True).start()

    threading.Thread(target=accept, daemon=True).start()


def dns_sd(*args, seconds=3):
    process = subprocess.Popen(["dns-sd", *args], stdout=subprocess.PIPE, text=True)
    time.sleep(seconds)
    process.terminate()
    return process.communicate()[0]


def connect_port():
    """The current port of the phone's adb-tls-connect service, from Bonjour."""
    instances = re.findall(r"_adb-tls-connect\._tcp\.\s+(\S+)", dns_sd("-B", "_adb-tls-connect._tcp", "local."))
    if not instances:
        sys.exit("no phone announces wireless debugging over Bonjour: is it on, and on the same Wi-Fi?")
    if len(set(instances)) > 1:
        print(f"several phones announce it, taking {instances[0]}: {sorted(set(instances))}", file=sys.stderr)
    resolved = dns_sd("-L", instances[0], "_adb-tls-connect._tcp", "local.")
    match = re.search(r"can be reached at \S+:(\d+)", resolved)
    if not match:
        sys.exit(f"could not resolve {instances[0]}")
    return int(match.group(1))


def adb(*args):
    print("$ adb " + " ".join(args), flush=True)
    return subprocess.run(["adb", *args]).returncode


def main():
    parser = argparse.ArgumentParser(description=__doc__, formatter_class=argparse.RawDescriptionHelpFormatter)
    parser.add_argument("phone", help="the phone's IP or .local name")
    parser.add_argument("--pair", nargs=2, metavar=("PORT", "CODE"), help="pairing dialog's port and code")
    parser.add_argument("--port", type=int, help="connect port (default: looked up over Bonjour)")
    parser.add_argument("--local", type=int, default=15555, help="local port adb connects to")
    options = parser.parse_args()
    host = socket.gethostbyname(options.phone)

    if options.pair:
        pair_port, code = options.pair
        forward(options.local + 1, host, int(pair_port))
        if adb("pair", f"127.0.0.1:{options.local + 1}", code) != 0:
            sys.exit("pairing failed: the dialog must stay open; its port and code change each time")

    port = options.port or connect_port()
    forward(options.local, host, port)
    serial = f"127.0.0.1:{options.local}"
    subprocess.run(["adb", "disconnect", serial], capture_output=True)  # a stale entry from an earlier run
    if adb("connect", serial) != 0:
        sys.exit("connect failed")
    print(f"forwarding {serial} -> {host}:{port}; use ANDROID_SERIAL={serial}; Ctrl-C ends it", flush=True)
    try:
        while True:
            time.sleep(3600)
    except KeyboardInterrupt:
        pass


main()
