"""
User & Session Manager for CodeMesh v2.0.

Tracks client sessions, per-user token consumption, conversation histories,
and enforces fair-share resource limits across concurrent mesh clients.
"""

from __future__ import annotations

from dataclasses import dataclass, field
import logging
import threading
import time
from typing import Dict, List, Optional

from .engine import PriorityLevel

logger = logging.getLogger("codemesh.scheduler.sessions")
if not logger.handlers:
    logging.basicConfig(level=logging.INFO)


@dataclass
class UserSession:
    """Represents a connected client's session state."""
    user_id: str
    connected_at: float
    total_tokens_consumed: int = 0
    active_requests: int = 0
    conversation_history: list = field(default_factory=list)
    preferred_model: str = "auto"
    priority_level: PriorityLevel = PriorityLevel.NORMAL
    last_active: float = field(default_factory=time.time)


class SessionManager:
    """
    Thread-safe User and Session state manager. Enforces fair-share token ceilings.
    """

    def __init__(self, max_fair_share_pct: float = 0.4, default_token_ceiling: int = 50000) -> None:
        """
        max_fair_share_pct: Maximum fraction of total cluster tokens a single user may consume
                            when multiple users contend for resources.
        default_token_ceiling: Absolute ceiling if only one user is active.
        """
        self.sessions: Dict[str, UserSession] = {}
        self.max_fair_share_pct: float = max_fair_share_pct
        self.default_token_ceiling: int = default_token_ceiling
        self._lock: threading.Lock = threading.Lock()

    def get_or_create(self, user_id: str) -> UserSession:
        """Get existing session or initialize a new one thread-safely."""
        with self._lock:
            now = time.time()
            if user_id not in self.sessions:
                session = UserSession(
                    user_id=user_id,
                    connected_at=now,
                    last_active=now,
                )
                self.sessions[user_id] = session
                logger.info("Initialized new session for user_id=%s", user_id)
            else:
                session = self.sessions[user_id]
                session.last_active = now
            return session

    def check_fair_share(self, user_id: str) -> bool:
        """
        Returns True if user has NOT exceeded their fair share of capacity.
        If user exceeds fair share and multiple active users exist, returns False.
        """
        with self._lock:
            session = self.sessions.get(user_id)
            if not session:
                return True

            total_consumed_all = sum(s.total_tokens_consumed for s in self.sessions.values())
            active_users_count = sum(1 for s in self.sessions.values() if s.active_requests > 0)

            # If user is the only one requesting resources, permit up to default ceiling
            if active_users_count <= 1:
                return session.total_tokens_consumed < self.default_token_ceiling

            # When contending with other active users, enforce percentage quota
            if total_consumed_all > 1000:
                user_share = session.total_tokens_consumed / total_consumed_all
                if user_share > self.max_fair_share_pct:
                    logger.warning(
                        "User %s exceeded fair share (%.1f%% > %.1f%%)",
                        user_id,
                        user_share * 100,
                        self.max_fair_share_pct * 100,
                    )
                    return False

            return True

    def record_token_usage(self, user_id: str, tokens: int) -> None:
        """Increments token consumption for a given user."""
        with self._lock:
            if user_id in self.sessions:
                self.sessions[user_id].total_tokens_consumed += tokens
                self.sessions[user_id].last_active = time.time()

    def increment_active_requests(self, user_id: str) -> None:
        """Tracks open request count for fair-share weighting."""
        with self._lock:
            if user_id in self.sessions:
                self.sessions[user_id].active_requests += 1

    def decrement_active_requests(self, user_id: str) -> None:
        """Reduces open request count upon completion."""
        with self._lock:
            if user_id in self.sessions and self.sessions[user_id].active_requests > 0:
                self.sessions[user_id].active_requests -= 1
