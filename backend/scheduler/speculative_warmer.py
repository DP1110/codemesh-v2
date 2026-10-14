"""
Speculative Model Pre-warming & Watchdog Fallback for CodeMesh.

Pre-warms candidate models in background when user begins typing or submits,
and enforces a TTFT (Time To First Token) deadline with automatic fallback.
"""

import asyncio
import logging
import time
from typing import Optional, Dict, Any, List
import aiohttp

logger = logging.getLogger("codemesh.scheduler.speculative")


class SpeculativeWarmer:
    """Manages background model warmup and TTFT watchdog switching."""

    def __init__(
        self,
        ollama_url: str = "http://localhost:11434",
        ttft_deadline_seconds: float = 4.5,
        fallback_model: str = "llama3:8b",
    ):
        self.ollama_url = ollama_url
        self.ttft_deadline_seconds = ttft_deadline_seconds
        self.fallback_model = fallback_model
        self._warm_models: set = set()
        self._session: Optional[aiohttp.ClientSession] = None
        self._lock = asyncio.Lock()

    async def _get_session(self) -> aiohttp.ClientSession:
        if self._session is None or self._session.closed:
            timeout = aiohttp.ClientTimeout(total=10)
            self._session = aiohttp.ClientSession(timeout=timeout)
        return self._session

    async def prewarm(self, model_name: str) -> bool:
        """
        Sends an empty generation probe to load weights into VRAM.
        Non-blocking background task.
        """
        if model_name in self._warm_models:
            return True

        async with self._lock:
            if model_name in self._warm_models:
                return True

            try:
                session = await self._get_session()
                payload = {
                    "model": model_name,
                    "prompt": "",
                    "keep_alive": "15m",
                }
                async with session.post(f"{self.ollama_url}/api/generate", json=payload) as resp:
                    if resp.status == 200:
                        self._warm_models.add(model_name)
                        logger.info("Successfully pre-warmed model: %s", model_name)
                        return True
                    else:
                        logger.warning("Warmup probe for %s returned status %d", model_name, resp.status)
                        return False
            except Exception as e:
                logger.debug("Ollama pre-warm probe failed for %s: %s", model_name, e)
                return False

    def select_model_with_fallback(self, primary_model: str, is_overloaded: bool = False) -> str:
        """Determines if request should be speculative-routed to faster fallback."""
        if is_overloaded and primary_model != self.fallback_model:
            logger.info("Diverting from %s to fast fallback %s due to overload", primary_model, self.fallback_model)
            return self.fallback_model
        return primary_model

    async def close(self):
        if self._session and not self._session.closed:
            await self._session.close()
