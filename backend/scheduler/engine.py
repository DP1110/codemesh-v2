"""
Round Robin Scheduler with Priority Queue for CodeMesh v2.0.

Provides multi-level priority queuing, quantum-based round robin scheduling,
preemption mechanics, starvation prevention via aging, and scheduler metrics.
"""

from __future__ import annotations

import asyncio
from collections import deque
from dataclasses import dataclass, field
from enum import IntEnum
import heapq
import logging
import time
from typing import Any, Dict, List, Optional, Tuple

logger = logging.getLogger("codemesh.scheduler.engine")
if not logger.handlers:
    logging.basicConfig(level=logging.INFO)


class PriorityLevel(IntEnum):
    """Priority levels for inference requests. Lower values indicate higher priority."""
    CRITICAL = 0  # Highest — preempts lower priority active requests
    HIGH = 1
    NORMAL = 2
    LOW = 3


@dataclass
class InferenceRequest:
    """Represents an inference job submitted to the scheduler."""
    request_id: str                          # UUID or unique identifier
    user_id: str                             # Client IP or session token
    messages: list                           # Chat messages [{'role': str, 'content': str}]
    model: str                               # Target model identifier
    priority: PriorityLevel                  # Current priority level
    created_at: float                        # Submission timestamp (time.time())
    tokens_generated: int = 0                # Total tokens generated so far
    total_tokens_requested: int = 2048       # Upper bound max output tokens
    time_quantum_used: int = 0               # Tokens produced in current time slice
    status: str = "queued"                   # queued | active | paused | completed
    result_tokens: list = field(default_factory=list)  # Accumulated generated token strings
    completed_at: Optional[float] = None     # Timestamp when finished
    started_at: Optional[float] = None       # Timestamp when first allocated quantum
    original_priority: PriorityLevel = PriorityLevel.NORMAL

    def __post_init__(self) -> None:
        self.original_priority = self.priority


@dataclass
class SchedulerStats:
    """Runtime telemetry and performance statistics for the scheduler."""
    total_requests: int = 0
    completed_requests: int = 0
    preemptions: int = 0
    avg_wait_time: float = 0.0
    avg_completion_time: float = 0.0
    tokens_per_second: float = 0.0
    active_users: int = 0
    queue_depth: int = 0
    _total_wait_time: float = 0.0
    _total_turnaround_time: float = 0.0
    _total_tokens_generated: int = 0
    _start_timestamp: float = field(default_factory=time.time)


class RoundRobinScheduler:
    """
    Round Robin Scheduler integrated with a Minimum-Heap Priority Queue.

    Features:
    - Priority-based scheduling (CRITICAL, HIGH, NORMAL, LOW)
    - Quantum slicing (time_quantum tokens per turn)
    - Active execution ring buffer (max_concurrent slots)
    - Starvation prevention via dynamic priority aging
    - Preemption support for CRITICAL tasks
    - Full thread and async task safety with asyncio.Lock
    """

    def __init__(self, time_quantum: int = 512, max_concurrent: int = 3) -> None:
        self.time_quantum: int = time_quantum
        self.max_concurrent: int = max_concurrent
        # Heap elements: (priority, arrival_time, counter, request)
        # Counter breaks ties without comparing InferenceRequest objects
        self.priority_queue: List[Tuple[int, float, int, InferenceRequest]] = []
        self._heap_counter: int = 0

        self.active_ring: deque[InferenceRequest] = deque()
        self.completed: Dict[str, InferenceRequest] = {}
        self.user_fair_share: Dict[str, int] = {}       # user_id -> tokens consumed
        self.aging_counter: Dict[str, int] = {}         # request_id -> number of skips
        self.stats: SchedulerStats = SchedulerStats()
        self._lock: asyncio.Lock = asyncio.Lock()
        
        # Adaptive Time-Quantum (ATQ) Engine
        from .adaptive_quantum import AdaptiveQuantumManager
        self.quantum_manager = AdaptiveQuantumManager(
            base_quantum=time_quantum,
            min_quantum=128,
            max_quantum=1024
        )

    def get_effective_quantum(self, request: InferenceRequest) -> int:
        """Returns the dynamic adaptive quantum for this specific request."""
        active_count = len(self.active_ring)
        q_depth = len(self.priority_queue)
        wait_time = (time.time() - request.created_at) if request.created_at else 0.0
        return self.quantum_manager.compute_quantum(
            active_users_count=max(1, active_count),
            queue_depth=q_depth,
            priority_level=int(request.priority),
            wait_time_seconds=wait_time,
        )

    async def submit(self, request: InferenceRequest) -> str:
        """
        Submit a new request to the priority queue. Returns request_id.
        Triggers preemption evaluation if request is CRITICAL.
        """
        async with self._lock:
            request.status = "queued"
            self._heap_counter += 1
            heapq.heappush(
                self.priority_queue,
                (int(request.priority), request.created_at, self._heap_counter, request)
            )
            self.aging_counter[request.request_id] = 0
            self.stats.total_requests += 1
            self.stats.queue_depth = len(self.priority_queue)

            logger.info(
                "Enqueued request %s (priority=%s, user=%s, model=%s)",
                request.request_id,
                request.priority.name,
                request.user_id,
                request.model,
            )

            if request.priority == PriorityLevel.CRITICAL:
                await self._check_and_preempt(request)

            self._update_telemetry()
            return request.request_id

    async def get_next_slice(self) -> Optional[InferenceRequest]:
        """
        Round Robin: get the next request that should receive a token generation slice.

        Algorithm:
        1. If active_ring has capacity (< max_concurrent) and priority_queue has items,
           promote highest priority item into active_ring.
        2. Pop front of active_ring (current turn).
        3. If request's time_quantum_used >= time_quantum, reset and move to back of ring.
        4. Return the request for token generation slice.
        5. Apply aging: increment skip counter for waiting requests in priority_queue.
        """
        async with self._lock:
            # 1. Fill open slots in active_ring from priority_queue
            while len(self.active_ring) < self.max_concurrent and self.priority_queue:
                _, _, _, top_req = heapq.heappop(self.priority_queue)
                top_req.status = "active"
                if top_req.started_at is None:
                    top_req.started_at = time.time()
                self.active_ring.append(top_req)
                logger.debug("Promoted request %s to active ring", top_req.request_id)

            if not self.active_ring:
                self.stats.queue_depth = len(self.priority_queue)
                return None

            # 2. Check front item
            candidate: InferenceRequest = self.active_ring.popleft()

            # 3. Check quantum slice exhaustion
            if candidate.time_quantum_used >= self.time_quantum:
                candidate.time_quantum_used = 0
                candidate.status = "active"
                self.active_ring.append(candidate)
                candidate = self.active_ring.popleft()

            # Mark active and prepare for slice execution
            candidate.status = "active"
            # Put candidate back at the end of the round robin ring so turns rotate
            self.active_ring.append(candidate)

            # 4. Apply starvation aging to requests still waiting in the priority queue
            self._apply_aging()

            self.stats.queue_depth = len(self.priority_queue)
            self._update_telemetry()
            return candidate

    async def report_tokens(self, request_id: str, num_tokens: int) -> None:
        """
        Called after tokens are generated. Updates counters and handles rotation or completion.
        """
        async with self._lock:
            # Locate request in active_ring, priority_queue, or completed
            req: Optional[InferenceRequest] = None
            for item in self.active_ring:
                if item.request_id == request_id:
                    req = item
                    break

            if req is None:
                # Check if it was already marked or finished
                req = self.completed.get(request_id)
                if not req:
                    for _, _, _, item in self.priority_queue:
                        if item.request_id == request_id:
                            req = item
                            break

            if not req:
                logger.warning("report_tokens: Request %s not found in scheduler state", request_id)
                return

            req.tokens_generated += num_tokens
            req.time_quantum_used += num_tokens
            self.user_fair_share[req.user_id] = self.user_fair_share.get(req.user_id, 0) + num_tokens
            self.stats._total_tokens_generated += num_tokens

            # Check completion criteria
            if req.tokens_generated >= req.total_tokens_requested or req.status == "completed":
                req.status = "completed"
                req.completed_at = time.time()
                # Remove from active_ring if present
                self._remove_from_ring(req.request_id)
                self.completed[req.request_id] = req
                self.aging_counter.pop(req.request_id, None)

                wait_time = (req.started_at - req.created_at) if req.started_at else 0.0
                turnaround = req.completed_at - req.created_at
                self.stats.completed_requests += 1
                self.stats._total_wait_time += max(0.0, wait_time)
                self.stats._total_turnaround_time += max(0.0, turnaround)

                logger.info(
                    "Request %s completed (tokens=%d, turnaround=%.2fs)",
                    req.request_id,
                    req.tokens_generated,
                    turnaround,
                )
            elif req.time_quantum_used >= self.time_quantum:
                # Quantum slice expired; mark paused for this cycle
                req.status = "paused"
                req.time_quantum_used = 0
                logger.debug("Request %s exhausted quantum slice, paused for rotation", req.request_id)

            self._update_telemetry()

    async def preempt(self, request: InferenceRequest) -> bool:
        """
        Preempt: pause lowest-priority active request, insert critical one.
        Returns True if preemption occurred, False otherwise.
        """
        async with self._lock:
            return await self._check_and_preempt(request)

    async def _check_and_preempt(self, critical_req: InferenceRequest) -> bool:
        """Internal lock-guarded helper to preempt lower priority active tasks."""
        if not self.active_ring:
            return False

        # Find the lowest priority item in active_ring (highest int value of PriorityLevel)
        lowest_priority_req: Optional[InferenceRequest] = None
        for item in self.active_ring:
            if lowest_priority_req is None or int(item.priority) > int(lowest_priority_req.priority):
                lowest_priority_req = item

        if lowest_priority_req and int(lowest_priority_req.priority) > int(critical_req.priority):
            logger.info(
                "Preempting active request %s (priority=%s) in favor of critical request %s (priority=%s)",
                lowest_priority_req.request_id,
                lowest_priority_req.priority.name,
                critical_req.request_id,
                critical_req.priority.name,
            )
            # Remove lowest from ring and pause it back into priority queue
            self._remove_from_ring(lowest_priority_req.request_id)
            lowest_priority_req.status = "paused"
            self._heap_counter += 1
            heapq.heappush(
                self.priority_queue,
                (int(lowest_priority_req.priority), lowest_priority_req.created_at, self._heap_counter, lowest_priority_req)
            )

            # Insert critical request directly into active_ring
            # Remove from priority_queue if it was placed there earlier
            self._remove_from_priority_queue(critical_req.request_id)
            critical_req.status = "active"
            if critical_req.started_at is None:
                critical_req.started_at = time.time()
            self.active_ring.appendleft(critical_req)

            self.stats.preemptions += 1
            return True

        return False

    def _remove_from_ring(self, request_id: str) -> None:
        """Removes a request by ID from active_ring."""
        temp = [item for item in self.active_ring if item.request_id != request_id]
        self.active_ring = deque(temp)

    def _remove_from_priority_queue(self, request_id: str) -> None:
        """Removes a request by ID from priority_queue and re-heaps."""
        new_queue = [item for item in self.priority_queue if item[3].request_id != request_id]
        if len(new_queue) != len(self.priority_queue):
            self.priority_queue = new_queue
            heapq.heapify(self.priority_queue)

    def get_queue_status(self) -> dict:
        """Return current snapshot of queue state for API telemetry and debugging."""
        active_list = [
            {
                "request_id": r.request_id,
                "user_id": r.user_id,
                "model": r.model,
                "priority": r.priority.name,
                "tokens_generated": r.tokens_generated,
                "quantum_used": r.time_quantum_used,
                "status": r.status,
            }
            for r in self.active_ring
        ]

        queued_list = [
            {
                "request_id": r.request_id,
                "user_id": r.user_id,
                "model": r.model,
                "priority": r.priority.name,
                "created_at": r.created_at,
                "skips": self.aging_counter.get(r.request_id, 0),
            }
            for _, _, _, r in sorted(self.priority_queue, key=lambda x: (x[0], x[1]))
        ]

        return {
            "max_concurrent": self.max_concurrent,
            "time_quantum": self.time_quantum,
            "active_ring_count": len(self.active_ring),
            "queued_count": len(self.priority_queue),
            "completed_count": len(self.completed),
            "active_requests": active_list,
            "queued_requests": queued_list,
            "stats": {
                "total_requests": self.stats.total_requests,
                "completed_requests": self.stats.completed_requests,
                "preemptions": self.stats.preemptions,
                "avg_wait_time_sec": round(self.stats.avg_wait_time, 3),
                "avg_completion_time_sec": round(self.stats.avg_completion_time, 3),
                "tokens_per_second": round(self.stats.tokens_per_second, 2),
                "active_users": self.stats.active_users,
                "queue_depth": self.stats.queue_depth,
            },
        }

    def get_user_position(self, user_id: str) -> int:
        """
        Returns position index (0-based) of the user's earliest pending request in queue.
        Returns 0 if actively running, positive integer if queued, -1 if not found.
        """
        # Active ring indicates currently running
        for r in self.active_ring:
            if r.user_id == user_id:
                return 0

        # Sort priority queue snapshot to determine exact ordinal rank
        sorted_queue = sorted(self.priority_queue, key=lambda x: (x[0], x[1]))
        for rank, (_, _, _, req) in enumerate(sorted_queue, start=1):
            if req.user_id == user_id:
                return rank

        return -1

    def _apply_aging(self) -> None:
        """
        Starvation prevention: escalate priority of long-waiting requests.
        Rule:
        - After 5 skips: promote LOW -> NORMAL
        - After 10 skips: promote NORMAL -> HIGH
        Re-heaps if priorities change.
        """
        if not self.priority_queue:
            return

        rebuild_heap = False
        new_queue: List[Tuple[int, float, int, InferenceRequest]] = []

        for prio, arr_time, cnt, req in self.priority_queue:
            skips = self.aging_counter.get(req.request_id, 0) + 1
            self.aging_counter[req.request_id] = skips

            promoted = False
            if skips >= 10 and req.priority == PriorityLevel.NORMAL:
                req.priority = PriorityLevel.HIGH
                promoted = True
                logger.info("Aging promotion: Request %s elevated to HIGH (skips=%d)", req.request_id, skips)
            elif skips >= 5 and req.priority == PriorityLevel.LOW:
                req.priority = PriorityLevel.NORMAL
                promoted = True
                logger.info("Aging promotion: Request %s elevated to NORMAL (skips=%d)", req.request_id, skips)

            if promoted:
                rebuild_heap = True

            new_queue.append((int(req.priority), arr_time, cnt, req))

        if rebuild_heap:
            self.priority_queue = new_queue
            heapq.heapify(self.priority_queue)
        else:
            self.priority_queue = new_queue

    def _update_telemetry(self) -> None:
        """Calculates running averages and updates SchedulerStats."""
        comp_count = self.stats.completed_requests
        if comp_count > 0:
            self.stats.avg_wait_time = self.stats._total_wait_time / comp_count
            self.stats.avg_completion_time = self.stats._total_turnaround_time / comp_count

        elapsed = time.time() - self.stats._start_timestamp
        if elapsed > 0:
            self.stats.tokens_per_second = self.stats._total_tokens_generated / elapsed

        users = set()
        for r in self.active_ring:
            users.add(r.user_id)
        for _, _, _, r in self.priority_queue:
            users.add(r.user_id)
        self.stats.active_users = len(users)
        self.stats.queue_depth = len(self.priority_queue)
