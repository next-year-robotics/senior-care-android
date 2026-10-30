#!/usr/bin/env python3
"""Start isolated mock processes, run the Android JVM matrix, then stop every child."""
import os
import pathlib
import pty
import signal
import socket
import subprocess
import threading
import time

ROOT = pathlib.Path(__file__).resolve().parents[2]
SCENARIOS = ['default6', 'empty', 'conflict', 'limit', 'in-use', 'slow-ack', 'error-ack', 'flaky', 'sos', 'sos-x3', 'sos-clear', 'garbage']
BASE = int(os.environ.get('MOCK_MATRIX_BASE_PORT', '18800'))
children = []

def discard(fd):
    try:
        while os.read(fd, 8192):
            pass
    except OSError:
        pass

try:
    for index, scenario in enumerate(SCENARIOS):
        # Fail before starting if an unrelated process already owns this port.
        with socket.socket() as probe:
            probe.bind(('127.0.0.1', BASE + index))
        master, slave = pty.openpty()
        process = subprocess.Popen([str(ROOT / 'tools/mock-bridge/run.sh'), '--scenario', scenario, '--port', str(BASE + index)], stdin=slave, stdout=slave, stderr=slave, start_new_session=True)
        os.close(slave)
        children.append((process, master))
        threading.Thread(target=discard, args=(master,), daemon=True).start()
        deadline = time.monotonic() + 15
        while True:
            if process.poll() is not None:
                raise RuntimeError(f'mock {scenario} exited before listening')
            try:
                with socket.create_connection(('127.0.0.1', BASE + index), timeout=.2):
                    break
            except OSError:
                if time.monotonic() >= deadline:
                    raise TimeoutError(scenario)
                time.sleep(.1)
    environment = dict(os.environ, MOCK_MATRIX_BASE_PORT=str(BASE))
    result = subprocess.run(['./gradlew', 'assembleDebug', 'testDebugUnitTest', 'lintDebug'], cwd=ROOT, env=environment)
    raise SystemExit(result.returncode)
finally:
    for process, master in children:
        if process.poll() is None:
            os.killpg(process.pid, signal.SIGTERM)
            try:
                process.wait(timeout=3)
            except subprocess.TimeoutExpired:
                os.killpg(process.pid, signal.SIGKILL)
                process.wait()
        os.close(master)
