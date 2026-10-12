"""
LRU Response Cache with Time-to-Live (TTL) for CodeMesh v2.0.

Caches query responses based on message sequence hashing to prevent redundant
local LLM inference on identical user prompts.
"""

from __future__ import annotations

from collections import OrderedDict
import hashlib
import json
import logging
import threading
import time
from typing import Any, Optional, Tuple

logger = logging.getLogger("codemesh.scheduler.cache")
if not logger.handlers:
    logging.basicConfig(level=logging.INFO)


class ResponseCache:
    """
    Thread-safe Least Recently Used (LRU) response cache with TTL expiration.
    """

    def __init__(self, max_size: int = 100, ttl_seconds: int = 300) -> None:
        self.cache: OrderedDict[str, Tuple[str, float]] = OrderedDict()
        self.max_size: int = max_size
        self.ttl: int = ttl_seconds
        self._lock: threading.Lock = threading.Lock()

    def get(self, messages: list) -> Optional[str]:
        """
        Check if we have a valid, non-expired cached response for this message prompt sequence.
        Returns cached response string or None on cache miss / expiration.
        """
        key = self._hash_messages(messages)
        with self._lock:
            if key not in self.cache:
                return None

            response, timestamp = self.cache[key]
            # Check TTL expiry
            if time.time() - timestamp > self.ttl:
                del self.cache[key]
                logger.debug("Cache expired for key %s", key[:12])
                return None

            # Move to end to maintain LRU recency
            self.cache.move_to_end(key)
            logger.info("Cache hit for key %s", key[:12])
            return response

    def put(self, messages: list, response: str) -> None:
        """
        Store a response in cache with current timestamp.
        Evicts oldest entry if max_size is exceeded.
        """
        if not response:
            return

        key = self._hash_messages(messages)
        with self._lock:
            now = time.time()
            if key in self.cache:
                self.cache.move_to_end(key)
            self.cache[key] = (response, now)

            # Evict LRU item if size threshold exceeded
            while len(self.cache) > self.max_size:
                evicted_key, _ = self.cache.popitem(last=False)
                logger.debug("LRU evicted key %s", evicted_key[:12])

    def _hash_messages(self, messages: list) -> str:
        """
        Deterministic SHA-256 hash of normalized messages representation.
        """
        try:
            # Normalize list of dicts
            normalized = []
            for m in messages:
                if isinstance(m, dict):
                    normalized.append({
                        "role": str(m.get("role", "")).strip().lower(),
                        "content": str(m.get("content", "")).strip(),
                    })
                else:
                    normalized.append(str(m))
            serialized = json.dumps(normalized, sort_keys=True, separators=(",", ":"))
        except Exception:
            serialized = str(messages)

        return hashlib.sha256(serialized.encode("utf-8")).hexdigest()

    def clear(self) -> None:
        """Flush cache."""
        with self._lock:
            self.cache.clear()

    def size(self) -> int:
        """Current number of items in cache."""
        with self._lock:
            return len(self.cache)
