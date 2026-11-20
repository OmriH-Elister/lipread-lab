import asyncio
import json
import secrets


class PayloadTooLarge(Exception):
    pass


class UploadGuard:
    """Rejects unauthorized, oversized, or excess uploads before form parsing."""

    def __init__(self, app, max_body_bytes: int, bearer_token: str = "", max_concurrent: int = 2):
        self.app = app
        self.max_body_bytes = max_body_bytes
        self.bearer_token = bearer_token
        self.slots = asyncio.BoundedSemaphore(max_concurrent)

    async def __call__(self, scope, receive, send):
        if scope.get("type") != "http" or scope.get("path") != "/transcribe":
            await self.app(scope, receive, send)
            return

        headers = {key.lower(): value for key, value in scope.get("headers", [])}
        if self.bearer_token:
            expected = b"Bearer " + self.bearer_token.encode("utf-8")
            supplied = headers.get(b"authorization", b"")
            if not secrets.compare_digest(supplied, expected):
                await self._json(send, 401, "Valid bearer token required", [(b"www-authenticate", b"Bearer")])
                return

        raw_length = headers.get(b"content-length")
        if raw_length:
            try:
                if int(raw_length) > self.max_body_bytes:
                    await self._json(send, 413, "Request body is too large")
                    return
            except ValueError:
                await self._json(send, 400, "Invalid Content-Length header")
                return

        try:
            await asyncio.wait_for(self.slots.acquire(), timeout=0.05)
        except asyncio.TimeoutError:
            await self._json(send, 503, "Server is busy; retry later")
            return

        received = 0
        response_started = False

        async def guarded_receive():
            nonlocal received
            message = await receive()
            if message.get("type") == "http.request":
                received += len(message.get("body", b""))
                if received > self.max_body_bytes:
                    raise PayloadTooLarge()
            return message

        async def guarded_send(message):
            nonlocal response_started
            if message.get("type") == "http.response.start":
                response_started = True
            await send(message)

        try:
            await self.app(scope, guarded_receive, guarded_send)
        except PayloadTooLarge:
            if not response_started:
                await self._json(send, 413, "Request body is too large")
        finally:
            self.slots.release()

    @staticmethod
    async def _json(send, status: int, detail: str, extra_headers=None):
        body = json.dumps({"detail": detail}, separators=(",", ":")).encode("utf-8")
        headers = [(b"content-type", b"application/json"), (b"content-length", str(len(body)).encode("ascii"))]
        if extra_headers:
            headers.extend(extra_headers)
        await send({"type": "http.response.start", "status": status, "headers": headers})
        await send({"type": "http.response.body", "body": body})
