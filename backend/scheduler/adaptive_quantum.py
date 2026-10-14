"""
Adaptive Time-Quantum (ATQ) Engine for CodeMesh.

Dynamically computes token slice sizes based on:
1. Current active user concurrency (N_active)
2. Queue pressure & average wait times
3. Request priority weights
4. Starvation burst credits for long-waiting jobs
"""

import math
import time
from typing import Dict, Any


class AdaptiveQuantumManager:
    """Calculates dynamic time quanta for fair continuous batching."""

    def __init__(
        self,
        base_quantum: int = 512,
        min_quantum: int = 128,
        max_quantum: int = 1024,
        target_latency_ms: float = 1500.0,
    ):
        self.base_quantum = base_quantum
        self.min_quantum = min_quantum
        self.max_quantum = max_quantum
        self.target_latency_ms = target_latency_ms
        self._quantum_history: list = []

    def compute_quantum(
        self,
        active_users_count: int,
        queue_depth: int,
        priority_level: int,  # 0=CRITICAL, 1=HIGH, 2=NORMAL, 3=LOW
        wait_time_seconds: float = 0.0,
    ) -> int:
        """
        Calculates the optimal token slice for the next round-robin turn.
        
        Formula:
            Q_scale = 1 / sqrt(max(1, active_users_count))
            Q_priority = Multiplier based on priority (CRITICAL=1.5x, LOW=0.75x)
            Q_burst = Extra bonus if waiting longer than 15s (anti-starvation burst)
        """
        # 1. Scale with active concurrent users
        concurrency = max(1, active_users_count + (queue_depth * 0.5))
        scale = 1.0 / math.sqrt(concurrency)
        scaled_quantum = self.base_quantum * scale

        # 2. Priority weighting
        priority_multipliers = {
            0: 1.5,   # CRITICAL gets larger uninterrupted burst
            1: 1.2,   # HIGH
            2: 1.0,   # NORMAL
            3: 0.75,  # LOW
        }
        p_multiplier = priority_multipliers.get(priority_level, 1.0)
        weighted_quantum = scaled_quantum * p_multiplier

        # 3. Anti-starvation burst bonus
        # If request has been waiting > 10s, grant a 20% bonus per 5s over deadline
        burst_bonus = 1.0
        if wait_time_seconds > 10.0:
            excess_wait = wait_time_seconds - 10.0
            burst_bonus += min(1.0, (excess_wait / 10.0) * 0.4)

        final_quantum = int(weighted_quantum * burst_bonus)

        # 4. Clamp within bounded boundaries
        clamped = max(self.min_quantum, min(self.max_quantum, final_quantum))
        return clamped

    def get_telemetry(self) -> Dict[str, Any]:
        """Returns snapshot for dashboard/telemetry."""
        return {
            "base_quantum": self.base_quantum,
            "min_quantum": self.min_quantum,
            "max_quantum": self.max_quantum,
            "target_latency_ms": self.target_latency_ms,
        }
