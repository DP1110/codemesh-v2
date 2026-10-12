"""
Continuous Batching Layer for CodeMesh v2.0.

Interfaces with Ollama /api/chat streaming endpoint to generate tokens
within prescribed time slices, preserving generation context across slices.
"""

from __future__ import annotations

import asyncio
import json
import logging
from typing import Any, AsyncGenerator, Dict, List, Optional
import aiohttp

from .engine import InferenceRequest

logger = logging.getLogger("codemesh.scheduler.batcher")
if not logger.handlers:
    logging.basicConfig(level=logging.INFO)


class ContinuousBatcher:
    """
    Continuous batching layer interfacing with Ollama.
    Manages incremental token generation slices and model state.
    """

    def __init__(self, ollama_url: str = "http://localhost:11434", timeout_seconds: float = 60.0) -> None:
        self.ollama_url: str = ollama_url.rstrip("/")
        self.timeout_seconds: float = timeout_seconds
        # Generation state tracked per request (accumulated text, active streams)
        self.active_generations: Dict[str, Dict[str, Any]] = {}
        self._session: Optional[aiohttp.ClientSession] = None
        self._lock: asyncio.Lock = asyncio.Lock()

    async def _get_session(self) -> aiohttp.ClientSession:
        """Lazy-initializes reusable aiohttp client session with connection pooling."""
        if self._session is None or self._session.closed:
            timeout = aiohttp.ClientTimeout(total=self.timeout_seconds)
            connector = aiohttp.TCPConnector(limit=20, keepalive_timeout=30)
            self._session = aiohttp.ClientSession(timeout=timeout, connector=connector)
        return self._session

    async def close(self) -> None:
        """Closes the underlying aiohttp session."""
        if self._session and not self._session.closed:
            await self._session.close()

    async def generate_slice(self, request: InferenceRequest, token_limit: int) -> List[str]:
        """
        Generate up to `token_limit` tokens for the given request.

        Uses Ollama /api/chat with stream=True. Reads tokens incrementally
        until token_limit is met or the generation stream ends.
        Accumulates generated tokens onto the request object.
        """
        generated_tokens: List[str] = []
        session = await self._get_session()
        endpoint = f"{self.ollama_url}/api/chat"

        # Construct messages payload, incorporating any previously generated assistant text
        messages_payload = list(request.messages)
        if request.result_tokens:
            accumulated_assistant_text = "".join(request.result_tokens)
            messages_payload.append({
                "role": "assistant",
                "content": accumulated_assistant_text
            })

        payload = {
            "model": request.model,
            "messages": messages_payload,
            "stream": True,
            "options": {
                "num_predict": min(token_limit, max(1, request.total_tokens_requested - request.tokens_generated)),
                "temperature": 0.7,
            }
        }

        logger.debug(
            "Request %s: requesting slice of up to %d tokens from %s",
            request.request_id,
            token_limit,
            request.model,
        )

        try:
            async with session.post(endpoint, json=payload) as response:
                if response.status != 200:
                    error_text = await response.text()
                    logger.error("Ollama API error (%d): %s", response.status, error_text)
                    return generated_tokens

                async for line in response.content:
                    if not line:
                        continue
                    line_str = line.decode("utf-8").strip()
                    if not line_str:
                        continue

                    try:
                        chunk = json.loads(line_str)
                    except json.JSONDecodeError:
                        continue

                    msg = chunk.get("message", {})
                    token_piece = msg.get("content", "")
                    if token_piece:
                        generated_tokens.append(token_piece)
                        request.result_tokens.append(token_piece)

                    # Check Ollama done signal
                    if chunk.get("done", False):
                        request.status = "completed"
                        break

                    # Enforce token limit per slice
                    if len(generated_tokens) >= token_limit:
                        break

        except asyncio.TimeoutError:
            logger.warning("Slice generation timed out for request %s", request.request_id)
        except Exception as exc:
            logger.exception("Exception in generate_slice for request %s: %s", request.request_id, exc)

        logger.info(
            "Request %s: generated slice of %d tokens (total: %d)",
            request.request_id,
            len(generated_tokens),
            request.tokens_generated + len(generated_tokens),
        )
        return generated_tokens

    async def check_model_health(self, model: str) -> bool:
        """
        Ping Ollama to check if model is loaded and responsive.
        Queries /api/tags and inspects model presence.
        """
        session = await self._get_session()
        endpoint = f"{self.ollama_url}/api/tags"

        try:
            async with session.get(endpoint) as response:
                if response.status != 200:
                    logger.warning("Ollama healthcheck returned status %d", response.status)
                    return False

                data = await response.json()
                models = data.get("models", [])
                base_target = model.split(":")[0].lower()

                for m in models:
                    m_name = m.get("name", "").lower()
                    if model.lower() == m_name or base_target in m_name:
                        return True

                logger.warning("Model '%s' not found among loaded models: %s", model, [m.get("name") for m in models])
                return False

        except Exception as exc:
            logger.warning("Failed to reach Ollama at %s: %s", self.ollama_url, exc)
            return False
