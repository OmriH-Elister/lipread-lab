import asyncio
import json
import unittest

from backend.upload_guard import UploadGuard


class UploadGuardTest(unittest.IsolatedAsyncioTestCase):
    async def call_guard(self, guard, headers=None, chunks=None):
        headers = headers or []
        chunks = list(chunks or [b""])
        read_count = 0
        sent = []

        async def receive():
            nonlocal read_count
            read_count += 1
            body = chunks.pop(0) if chunks else b""
            return {"type": "http.request", "body": body, "more_body": bool(chunks)}

        async def send(message):
            sent.append(message)

        scope = {"type": "http", "path": "/transcribe", "headers": headers}
        await guard(scope, receive, send)
        status = next(message["status"] for message in sent if message["type"] == "http.response.start")
        body = b"".join(message.get("body", b"") for message in sent if message["type"] == "http.response.body")
        return status, json.loads(body), read_count

    async def test_rejects_auth_before_reading_body(self):
        async def downstream(scope, receive, send):
            self.fail("downstream must not run")

        guard = UploadGuard(downstream, max_body_bytes=100, bearer_token="secret", max_concurrent=1)
        status, body, reads = await self.call_guard(guard, chunks=[b"private-video"])
        self.assertEqual(401, status)
        self.assertEqual(0, reads)
        self.assertEqual("Valid bearer token required", body["detail"])

    async def test_rejects_large_content_length_before_reading_body(self):
        async def downstream(scope, receive, send):
            self.fail("downstream must not run")

        guard = UploadGuard(downstream, max_body_bytes=5, max_concurrent=1)
        status, _, reads = await self.call_guard(guard, headers=[(b"content-length", b"6")], chunks=[b"123456"])
        self.assertEqual(413, status)
        self.assertEqual(0, reads)

    async def test_rejects_chunked_body_over_limit(self):
        async def downstream(scope, receive, send):
            while True:
                message = await receive()
                if not message.get("more_body"):
                    break
            await send({"type": "http.response.start", "status": 200, "headers": []})
            await send({"type": "http.response.body", "body": b"{}"})

        guard = UploadGuard(downstream, max_body_bytes=5, max_concurrent=1)
        status, body, _ = await self.call_guard(guard, chunks=[b"123", b"456"])
        self.assertEqual(413, status)
        self.assertEqual("Request body is too large", body["detail"])

    async def test_rejects_concurrent_request_before_body_read(self):
        entered = asyncio.Event()
        release = asyncio.Event()

        async def downstream(scope, receive, send):
            entered.set()
            await release.wait()
            await send({"type": "http.response.start", "status": 200, "headers": []})
            await send({"type": "http.response.body", "body": b"{}"})

        guard = UploadGuard(downstream, max_body_bytes=100, max_concurrent=1)
        first = asyncio.create_task(self.call_guard(guard))
        await entered.wait()
        status, body, reads = await self.call_guard(guard, chunks=[b"video"])
        self.assertEqual(503, status)
        self.assertEqual(0, reads)
        self.assertEqual("Server is busy; retry later", body["detail"])
        release.set()
        await first


if __name__ == "__main__":
    unittest.main()
