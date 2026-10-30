"""Optional server-side SOS monotonic timing, never logs coordinates or frame bodies."""
import json
import runpy
import time
from mr_phone_bridge.server.ws import WsConnection

original_send = WsConnection.send
original_iter = WsConnection.__aiter__

async def measured_send(self, text):
    await original_send(self, text)
    try:
        message = json.loads(text)
        if message.get("type") == "sos.request":
            if not hasattr(self, "_sos_measure"):
                self._sos_measure = {}
                self._sos_first = {}
            stamp = time.monotonic_ns()
            sos_id = message["payload"]["sos_id"]
            self._sos_measure[sos_id] = stamp
            self._sos_first.setdefault(sos_id, stamp)
    except (ValueError, KeyError, TypeError):
        pass

async def measured_iter(self):
    async for text in original_iter(self):
        received = time.monotonic_ns()
        try:
            message = json.loads(text)
            kind = message.get("type")
            if kind in ("sos.ack", "location.update"):
                payload = message["payload"]
                stamp = getattr(self, "_sos_measure", {}).get(payload.get("sos_id"))
                if stamp is not None:
                    # Only protocol type, source, and elapsed time. Never print a payload.
                    first = self._sos_first[payload["sos_id"]]
                    print(f"SOS_TIMING type={kind} source={payload.get('source', '-')} elapsed_ms={(received-stamp)/1e6:.1f} since_first_ms={(received-first)/1e6:.1f}", flush=True)
        except (ValueError, KeyError, TypeError):
            pass
        yield text

WsConnection.send = measured_send
WsConnection.__aiter__ = measured_iter
runpy.run_module("mr_phone_bridge.mock", run_name="__main__")
