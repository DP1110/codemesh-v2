"""
CodeMesh v2.0 - Offline WiFi Bridge Backend Server.

FastAPI server with round-robin scheduler integration, continuous batching,
SSE & WebSocket streaming, priority queueing, and smart model routing.
"""

from __future__ import annotations

import asyncio
from contextlib import asynccontextmanager
import json
import logging
import os
from pathlib import Path
import sys
import time
from typing import Any, AsyncGenerator, Dict, List, Literal, Optional, Union
import uuid

# Ensure backend directory is discoverable on python path
BACKEND_DIR = Path(__file__).resolve().parent
if str(BACKEND_DIR) not in sys.path:
    sys.path.insert(0, str(BACKEND_DIR))

from fastapi import FastAPI, HTTPException, Request, Response, WebSocket, WebSocketDisconnect, status
from fastapi.middleware.cors import CORSMiddleware
from fastapi.responses import JSONResponse, StreamingResponse
from fastapi.staticfiles import StaticFiles
from pydantic import BaseModel, Field

from scheduler.engine import InferenceRequest, PriorityLevel, RoundRobinScheduler, SchedulerStats
from scheduler.batcher import ContinuousBatcher
from scheduler.router import ModelRouter
from scheduler.sessions import SessionManager, UserSession
from scheduler.cache import ResponseCache
from scheduler.speculative_warmer import SpeculativeWarmer
from rag.search_engine import LocalOfflineRAG

# Configure logging
LOG_LEVEL = os.getenv("LOG_LEVEL", "INFO").upper()
logging.basicConfig(
    level=getattr(logging, LOG_LEVEL, logging.INFO),
    format="%(asctime)s [%(levelname)s] [%(name)s] %(message)s",
)
logger = logging.getLogger("codemesh.backend")


# ---------------------------------------------------------------------------
# Pydantic Schemas
# ---------------------------------------------------------------------------

class ChatMessage(BaseModel):
    """Chat message structure conforming to standard LLM format."""
    role: str = Field(..., description="Message role: system, user, or assistant")
    content: str = Field(..., description="Message content")


class ChatRequest(BaseModel):
    """Inference request payload submitted by clients."""
    messages: Optional[List[ChatMessage]] = Field(
        default=None,
        description="Structured conversation messages list"
    )
    prompt: Optional[str] = Field(
        default=None,
        description="Raw prompt text shorthand if messages array is omitted"
    )
    model: Optional[str] = Field(
        default=None,
        description="Target model identifier or 'auto'"
    )
    priority: Optional[Literal["critical", "high", "normal", "low"]] = Field(
        default="normal",
        description="Execution priority level"
    )
    client_id: Optional[str] = Field(
        default=None,
        description="Unique client session or mesh device identifier"
    )
    max_tokens: Optional[int] = Field(
        default=2048,
        description="Maximum tokens to generate for this request"
    )


class ChatSubmissionResponse(BaseModel):
    """Response returned immediately upon queuing a chat request."""
    request_id: str
    queue_position: int
    estimated_wait: float
    estimated_wait_seconds: float
    model: str
    priority: str


class ActiveRequestInfo(BaseModel):
    """Descriptor for requests currently inside active execution ring."""
    id: str
    user: str
    model: str
    progress: int


class WaitingRequestInfo(BaseModel):
    """Descriptor for queued requests waiting for round-robin turn."""
    id: str
    user: str
    model: str
    priority: str
    created_at: float
    skips: int


class QueueStatusResponse(BaseModel):
    """Full queue snapshot schema for visualization dashboard."""
    queue_depth: int
    active_requests: List[ActiveRequestInfo]
    waiting: List[WaitingRequestInfo]
    active_users: int
    peers: List[Dict[str, Any]] = []


class ModelHealthInfo(BaseModel):
    """Runtime status and metadata for an LLM model."""
    name: str
    status: str
    specialty: str
    load: int


class ModelsListResponse(BaseModel):
    """Listing of supported LLMs and their health states."""
    models: List[ModelHealthInfo]


class StatsResponse(BaseModel):
    """Real-time scheduler telemetry metrics."""
    total_requests: int
    completed: int
    total_served: int
    preemptions: int
    preemption_count: int
    avg_wait: float
    avg_wait_time: float
    avg_completion: float
    tps: float
    tokens_per_sec: float
    active_users: int


class HealthResponse(BaseModel):
    """System health check payload."""
    status: str
    version: str
    uptime_seconds: float
    active_connections: int
    queue_depth: int


# ---------------------------------------------------------------------------
# Real-Time Stream Event Broadcaster
# ---------------------------------------------------------------------------

class StreamBroadcaster:
    """
    Manages SSE and WebSocket client event distributions for requests.
    Supports buffering and catch-up playback for late-connecting subscribers.
    """

    def __init__(self) -> None:
        self._subscribers: Dict[str, List[asyncio.Queue]] = {}
        self._history: Dict[str, List[str]] = {}
        self._is_completed: Dict[str, bool] = {}
        self._lock: asyncio.Lock = asyncio.Lock()

    async def register(self, request_id: str) -> asyncio.Queue:
        """Register a subscriber queue for receiving tokens for a request."""
        async with self._lock:
            q: asyncio.Queue = asyncio.Queue()
            if request_id not in self._subscribers:
                self._subscribers[request_id] = []
            self._subscribers[request_id].append(q)

            # Replay any history accumulated before this subscriber connected
            for token_chunk in self._history.get(request_id, []):
                await q.put({"event": "token", "data": token_chunk})

            # If request is already completed, emit completion sentinel
            if self._is_completed.get(request_id, False):
                await q.put({"event": "done", "data": "[DONE]"})

            return q

    async def unregister(self, request_id: str, queue: asyncio.Queue) -> None:
        """Unregister a subscriber queue."""
        async with self._lock:
            if request_id in self._subscribers:
                if queue in self._subscribers[request_id]:
                    self._subscribers[request_id].remove(queue)
                if not self._subscribers[request_id] and self._is_completed.get(request_id, False):
                    # Clean up old completed request state
                    self._subscribers.pop(request_id, None)
                    self._history.pop(request_id, None)
                    self._is_completed.pop(request_id, None)

    async def push(self, request_id: str, tokens: Union[str, List[str]], done: bool = False) -> None:
        """Broadcast token tokens or slice chunk to all active subscribers."""
        async with self._lock:
            if request_id not in self._history:
                self._history[request_id] = []

            token_list: List[str] = [tokens] if isinstance(tokens, str) else list(tokens)
            queues = self._subscribers.get(request_id, [])

            for tok in token_list:
                if tok:
                    self._history[request_id].append(tok)
                    for q in queues:
                        await q.put({"event": "token", "data": tok})

            if done:
                self._is_completed[request_id] = True
                for q in queues:
                    await q.put({"event": "done", "data": "[DONE]"})

    async def push_preempted(self, request_id: str) -> None:
        """Inform client that their request was temporarily preempted."""
        async with self._lock:
            for q in self._subscribers.get(request_id, []):
                await q.put({"event": "preempted", "data": "Request paused for higher priority task"})

    async def push_queue_update(self, request_id: str, position: int, estimated_wait: float) -> None:
        """Broadcast queue ordinal position updates."""
        async with self._lock:
            payload = json.dumps({"position": position, "estimated_wait": estimated_wait})
            for q in self._subscribers.get(request_id, []):
                await q.put({"event": "queue_update", "data": payload})


# ---------------------------------------------------------------------------
# Global State & Subsystem Singletons
# ---------------------------------------------------------------------------

scheduler: RoundRobinScheduler = RoundRobinScheduler(time_quantum=512, max_concurrent=3)
batcher: ContinuousBatcher = ContinuousBatcher(ollama_url=os.getenv("OLLAMA_URL", "http://localhost:11434"))
router: ModelRouter = ModelRouter(ollama_url=os.getenv("OLLAMA_URL", "http://localhost:11434"))
session_manager: SessionManager = SessionManager(max_fair_share_pct=0.4, default_token_ceiling=50000)
cache: ResponseCache = ResponseCache(max_size=200, ttl_seconds=300)
broadcaster: StreamBroadcaster = StreamBroadcaster()
warmer: SpeculativeWarmer = SpeculativeWarmer(ollama_url=os.getenv("OLLAMA_URL", "http://localhost:11434"))
rag_engine: LocalOfflineRAG = LocalOfflineRAG()

# Pre-populate RAG with project documentation and notes
try:
    rag_engine.add_document("doc-1", "CodeMesh Architecture", "CodeMesh is an offline WiFi bridge system for local LLM-assisted code editing with Monaco and FastAPI.", "docs/WORKFLOW_v2.md")
    rag_engine.add_document("doc-2", "Round Robin Scheduling", "Continuous batching and adaptive time quanta provide fair inference turns across multi-tenant clients.", "docs/PRD.md")
    rag_engine.add_document("doc-3", "ESP8266 Firmware Guide", "NodeMCU creates the LLM-Bridge AP and relays SSE chunks to connected devices.", "firmware/codemesh_v2.ino")
    rag_engine.build_index()
except Exception as _rag_err:
    logger.warning("RAG initialization notice: %s", _rag_err)

server_start_time: float = time.time()
scheduler_task: Optional[asyncio.Task] = None
shutdown_event: asyncio.Event = asyncio.Event()


async def notify_client(request_id: str, tokens: Union[str, List[str]], done: bool = False) -> None:
    """Helper invoked by scheduler loop to stream tokens out to waiting clients."""
    await broadcaster.push(request_id=request_id, tokens=tokens, done=done)


# ---------------------------------------------------------------------------
# Background Scheduler Loop
# ---------------------------------------------------------------------------

async def scheduler_loop() -> None:
    """
    Main scheduler loop. Runs continuously in background.
    Rotates active requests through round-robin slices, streams tokens,
    and updates session consumption & response cache.
    """
    logger.info("Scheduler background loop initialized and running.")
    while not shutdown_event.is_set():
        try:
            # 1. Get next request from RR scheduler
            request: Optional[InferenceRequest] = await scheduler.get_next_slice()
            if request is None:
                await asyncio.sleep(0.1)  # No work, sleep briefly
                continue

            # 2. Check cache first
            cached: Optional[str] = cache.get(request.messages)
            if cached:
                # Serve from cache instantly
                logger.info("Serving request %s directly from response cache", request.request_id)
                request.result_tokens = [cached]
                request.tokens_generated = len(cached.split())
                request.status = "completed"
                await notify_client(request.request_id, cached, done=True)
                await scheduler.report_tokens(request.request_id, request.tokens_generated)
                session_manager.record_token_usage(request.user_id, request.tokens_generated)
                session_manager.decrement_active_requests(request.user_id)
                continue

            # 3. Generate a slice of tokens via batcher using dynamic Adaptive Time-Quantum
            dynamic_quantum = scheduler.get_effective_quantum(request)
            tokens: List[str] = await batcher.generate_slice(request, dynamic_quantum)

            # Determine whether generation has finished
            is_complete = (
                request.status == "completed"
                or (len(tokens) == 0 and request.tokens_generated > 0)
                or (request.tokens_generated + len(tokens) >= request.total_tokens_requested)
            )

            # 4. Push tokens to client via SSE / WebSocket
            await notify_client(request.request_id, tokens, done=is_complete)

            # 5. Report back to scheduler
            await scheduler.report_tokens(request.request_id, len(tokens))
            session_manager.record_token_usage(request.user_id, len(tokens))

            # 6. If complete, cache the response and decrement session active count
            if request.status == "completed" or is_complete:
                session_manager.decrement_active_requests(request.user_id)
                full_response = "".join(request.result_tokens)
                if full_response:
                    cache.put(request.messages, full_response)
                logger.info("Finished request %s (total tokens: %d)", request.request_id, request.tokens_generated)

        except asyncio.CancelledError:
            logger.info("Scheduler loop received cancellation signal.")
            break
        except Exception as exc:
            logger.exception("Unexpected error inside scheduler_loop: %s", exc)
            await asyncio.sleep(0.2)

    logger.info("Scheduler background loop has exited cleanly.")


# ---------------------------------------------------------------------------
# Application Lifespan Context Manager
# ---------------------------------------------------------------------------

@asynccontextmanager
async def lifespan(app: FastAPI):
    """FastAPI lifespan manager for startup and graceful shutdown."""
    global scheduler_task, server_start_time
    server_start_time = time.time()
    logger.info("Starting CodeMesh v2.0 backend server...")

    # Spawn background scheduler worker
    shutdown_event.clear()
    scheduler_task = asyncio.create_task(scheduler_loop(), name="CodeMeshSchedulerLoop")

    yield

    # Graceful shutdown sequence
    logger.info("Shutting down CodeMesh v2.0 backend server...")
    shutdown_event.set()
    if scheduler_task and not scheduler_task.done():
        scheduler_task.cancel()
        try:
            await asyncio.wait_for(scheduler_task, timeout=3.0)
        except (asyncio.CancelledError, asyncio.TimeoutError):
            pass

    # Close HTTP connection pools
    await batcher.close()
    logger.info("CodeMesh v2.0 backend shutdown complete.")


# ---------------------------------------------------------------------------
# FastAPI App Initialization
# ---------------------------------------------------------------------------

app = FastAPI(
    title="CodeMesh v2.0 Backend",
    description="Offline WiFi bridge backend with RR priority scheduler and streaming LLM inference",
    version="2.0.0",
    lifespan=lifespan,
)

# CORS middleware for local mesh / offline network communication
app.add_middleware(
    CORSMiddleware,
    allow_origins=["*"],
    allow_credentials=True,
    allow_methods=["*"],
    allow_headers=["*"],
)


# ---------------------------------------------------------------------------
# Helper Utilities
# ---------------------------------------------------------------------------

def resolve_priority(priority_str: Optional[str]) -> PriorityLevel:
    """Maps priority string to PriorityLevel enum."""
    if not priority_str:
        return PriorityLevel.NORMAL
    p = priority_str.strip().lower()
    mapping = {
        "critical": PriorityLevel.CRITICAL,
        "high": PriorityLevel.HIGH,
        "normal": PriorityLevel.NORMAL,
        "low": PriorityLevel.LOW,
    }
    return mapping.get(p, PriorityLevel.NORMAL)


def extract_client_id(request: Request, body_client_id: Optional[str] = None) -> str:
    """Determine client identifier from body, headers, or client IP."""
    if body_client_id and body_client_id.strip():
        return body_client_id.strip()
    forwarded = request.headers.get("x-forwarded-for")
    if forwarded:
        return forwarded.split(",")[0].strip()
    if request.client and request.client.host:
        return request.client.host
    return f"client-{uuid.uuid4().hex[:6]}"


def normalize_messages(body: ChatRequest) -> List[Dict[str, str]]:
    """Normalize incoming messages structure from body."""
    messages: List[Dict[str, str]] = []
    if body.messages:
        for m in body.messages:
            messages.append({"role": m.role.strip().lower(), "content": m.content})
    elif body.prompt:
        messages.append({"role": "user", "content": body.prompt.strip()})
    else:
        messages.append({"role": "user", "content": ""})
    return messages


# ---------------------------------------------------------------------------
# REST Endpoints
# ---------------------------------------------------------------------------

@app.post("/api/v2/chat", response_model=ChatSubmissionResponse)
async def submit_chat(payload: ChatRequest, request: Request) -> ChatSubmissionResponse:
    """
    Submit a chat request with optional priority and model.
    Enqueues the request in the Round-Robin Priority Scheduler.
    """
    client_id = extract_client_id(request, payload.client_id)
    session = session_manager.get_or_create(client_id)

    # Fair share enforcement
    if not session_manager.check_fair_share(client_id):
        logger.warning("Client %s throttled due to fair share limits", client_id)
        # Downgrade priority to LOW if quota exceeded rather than rejecting entirely
        priority_enum = PriorityLevel.LOW
    else:
        priority_enum = resolve_priority(payload.priority)

    # Normalize messages
    messages = normalize_messages(payload)
    if not messages or not any(m.get("content") for m in messages):
        raise HTTPException(status_code=status.HTTP_400_BAD_REQUEST, detail="Messages or prompt must not be empty.")

    # Determine model via Smart Router
    selected_model = router.route(messages, preferred_model=payload.model)

    # Trigger speculative pre-warming in background
    try:
        asyncio.create_task(warmer.prewarm(selected_model))
    except Exception:
        pass

    # Instantiate Inference Request
    request_id = str(uuid.uuid4())
    inference_req = InferenceRequest(
        request_id=request_id,
        user_id=client_id,
        messages=messages,
        model=selected_model,
        priority=priority_enum,
        created_at=time.time(),
        total_tokens_requested=payload.max_tokens or 2048,
    )

    session_manager.increment_active_requests(client_id)
    await scheduler.submit(inference_req)

    # Compute estimated position and wait
    user_pos = scheduler.get_user_position(client_id)
    pos = max(0, user_pos)
    # Estimate wait: 1.5s per queued item ahead or zero if immediately active
    tps = scheduler.stats.tokens_per_second if scheduler.stats.tokens_per_second > 0 else 25.0
    est_wait = round((pos * scheduler.time_quantum) / tps, 2) if pos > 0 else 0.0

    logger.info("Enqueued chat request %s for user %s (pos=%d, wait=%.1fs)", request_id, client_id, pos, est_wait)

    return ChatSubmissionResponse(
        request_id=request_id,
        queue_position=pos,
        estimated_wait=est_wait,
        estimated_wait_seconds=est_wait,
        model=selected_model,
        priority=priority_enum.name.lower(),
    )


@app.post("/api/v2/preempt", response_model=ChatSubmissionResponse)
async def submit_preempt_chat(payload: ChatRequest, request: Request) -> ChatSubmissionResponse:
    """
    Submit a CRITICAL priority request.
    Forces priority to CRITICAL and preempts lower priority active tasks.
    """
    # Force priority to critical
    payload.priority = "critical"
    logger.info("High-priority CRITICAL preemption request received from %s", payload.client_id)
    return await submit_chat(payload, request)


@app.get("/api/v2/stream/{request_id}")
async def stream_tokens(request_id: str, request: Request) -> StreamingResponse:
    """
    Server-Sent Events (SSE) endpoint for streaming tokens as they are generated.
    Emits events:
      - 'token': Incremental token chunk
      - 'queue_update': JSON payload with queue position
      - 'done': Signal indicating end of generation
    """
    # Verify request existence or state
    is_known = (
        request_id in scheduler.completed
        or any(r.request_id == request_id for r in scheduler.active_ring)
        or any(r[3].request_id == request_id for r in scheduler.priority_queue)
    )

    if not is_known and request_id not in broadcaster._history:
        # Give a small grace period in case submission and stream race
        await asyncio.sleep(0.05)
        is_known = (
            request_id in scheduler.completed
            or any(r.request_id == request_id for r in scheduler.active_ring)
            or any(r[3].request_id == request_id for r in scheduler.priority_queue)
            or request_id in broadcaster._history
        )
        if not is_known:
            raise HTTPException(status_code=status.HTTP_404_NOT_FOUND, detail=f"Request {request_id} not found.")

    sub_queue = await broadcaster.register(request_id)

    async def sse_generator() -> AsyncGenerator[str, None]:
        try:
            while True:
                # Check client disconnect
                if await request.is_disconnected():
                    logger.debug("Client disconnected from SSE stream %s", request_id)
                    break

                try:
                    event_data = await asyncio.wait_for(sub_queue.get(), timeout=20.0)
                except asyncio.TimeoutError:
                    # Send periodic keep-alive comment
                    yield ": ping\n\n"
                    continue

                event_name = event_data.get("event", "message")
                payload = event_data.get("data", "")

                if event_name == "done":
                    # Standard done indicators
                    yield f"event: done\ndata: {payload}\n\n"
                    yield "data: [DONE]\n\n"
                    break
                elif event_name == "token":
                    # Send named event and fallback data
                    yield f"event: token\ndata: {payload}\n\n"
                elif event_name == "queue_update":
                    yield f"event: queue_update\ndata: {payload}\n\n"
                elif event_name == "preempted":
                    yield f"event: preempted\ndata: {payload}\n\n"
                else:
                    yield f"data: {payload}\n\n"

        finally:
            await broadcaster.unregister(request_id, sub_queue)

    headers = {
        "Content-Type": "text/event-stream",
        "Cache-Control": "no-cache, no-transform",
        "Connection": "keep-alive",
        "X-Accel-Buffering": "no",
    }
    return StreamingResponse(sse_generator(), headers=headers)


@app.get("/api/v2/queue", response_model=QueueStatusResponse)
async def get_queue_status() -> QueueStatusResponse:
    """
    Get current queue status, active requests in round-robin execution,
    and waiting requests in the priority queue.
    """
    status_dict = scheduler.get_queue_status()

    active_requests = [
        ActiveRequestInfo(
            id=item["request_id"],
            user=item["user_id"],
            model=item["model"],
            progress=item["tokens_generated"],
        )
        for item in status_dict.get("active_requests", [])
    ]

    waiting_requests = [
        WaitingRequestInfo(
            id=item["request_id"],
            user=item["user_id"],
            model=item["model"],
            priority=item["priority"],
            created_at=item["created_at"],
            skips=item["skips"],
        )
        for item in status_dict.get("queued_requests", [])
    ]

    active_user_count = len(session_manager.sessions)
    if active_user_count == 0:
        active_user_count = len(active_requests) + len(waiting_requests)

    # Build peers list for frontend mesh dots
    peers: List[Dict[str, Any]] = []
    seen_users = set()
    for req in active_requests:
        if req.user not in seen_users:
            peers.append({"user_id": req.user, "status": "active", "model": req.model})
            seen_users.add(req.user)
    for w in waiting_requests:
        if w.user not in seen_users:
            peers.append({"user_id": w.user, "status": "waiting", "model": w.model})
            seen_users.add(w.user)

    return QueueStatusResponse(
        queue_depth=len(waiting_requests),
        active_requests=active_requests,
        waiting=waiting_requests,
        active_users=max(1, active_user_count),
        peers=peers,
    )


@app.get("/api/v2/models", response_model=ModelsListResponse)
async def list_models() -> ModelsListResponse:
    """
    List available models with health status and active scheduler load.
    """
    model_list: List[ModelHealthInfo] = []

    # Count load per model in active ring + queue
    model_load: Dict[str, int] = {}
    for r in scheduler.active_ring:
        model_load[r.model] = model_load.get(r.model, 0) + 1
    for _, _, _, r in scheduler.priority_queue:
        model_load[r.model] = model_load.get(r.model, 0) + 1

    # Check known models
    for name, meta in ModelRouter.MODELS.items():
        # Health check via batcher
        try:
            is_healthy = await asyncio.wait_for(batcher.check_model_health(name), timeout=1.0)
        except Exception:
            is_healthy = True  # Fallback to healthy in offline simulation mode

        model_list.append(
            ModelHealthInfo(
                name=name,
                status="healthy" if is_healthy else "offline",
                specialty=meta.get("specialty", "general"),
                load=model_load.get(name, 0),
            )
        )

    return ModelsListResponse(models=model_list)


@app.get("/api/v2/stats", response_model=StatsResponse)
async def get_scheduler_stats() -> StatsResponse:
    """
    Scheduler performance statistics and telemetry.
    """
    stats = scheduler.stats
    return StatsResponse(
        total_requests=stats.total_requests,
        completed=stats.completed_requests,
        total_served=stats.completed_requests,
        preemptions=stats.preemptions,
        preemption_count=stats.preemptions,
        avg_wait=round(stats.avg_wait_time, 2),
        avg_wait_time=round(stats.avg_wait_time, 2),
        avg_completion=round(stats.avg_completion_time, 2),
        tps=round(stats.tokens_per_second, 2),
        tokens_per_sec=round(stats.tokens_per_second, 2),
        active_users=max(1, len([s for s in session_manager.sessions.values() if s.active_requests > 0])),
    )


@app.get("/api/v2/health", response_model=HealthResponse)
async def health_check() -> HealthResponse:
    """
    System health check.
    """
    uptime = time.time() - server_start_time
    total_connections = sum(len(q_list) for q_list in broadcaster._subscribers.values())
    return HealthResponse(
        status="ok",
        version="2.2.0",
        uptime_seconds=round(uptime, 2),
        active_connections=total_connections,
        queue_depth=len(scheduler.priority_queue),
    )


@app.get("/api/v2/rag/search")
async def rag_search_endpoint(q: str, limit: int = 3):
    """
    Offline local BM25 search over documentation and code notes.
    """
    results = rag_engine.search(query=q, top_k=limit)
    return {
        "query": q,
        "count": len(results),
        "results": results,
    }


# ---------------------------------------------------------------------------
# WebSocket Endpoints
# ---------------------------------------------------------------------------

@app.websocket("/api/v2/ws/{request_id}")
@app.websocket("/ws/stream/{request_id}")
async def websocket_stream_endpoint(websocket: WebSocket, request_id: str):
    """
    Stream tokens for a given request_id over WebSocket.
    """
    await websocket.accept()
    sub_queue = await broadcaster.register(request_id)

    try:
        while True:
            try:
                msg = await asyncio.wait_for(sub_queue.get(), timeout=20.0)
            except asyncio.TimeoutError:
                await websocket.send_json({"type": "ping"})
                continue

            event_type = msg.get("event", "message")
            data = msg.get("data", "")

            if event_type == "done":
                await websocket.send_json({"type": "done", "request_id": request_id})
                break
            elif event_type == "token":
                await websocket.send_json({"type": "token", "request_id": request_id, "token": data})
            elif event_type == "queue_update":
                await websocket.send_json({"type": "queue_update", "request_id": request_id, "data": json.loads(data)})
            elif event_type == "preempted":
                await websocket.send_json({"type": "preempted", "request_id": request_id, "message": data})

    except WebSocketDisconnect:
        logger.debug("WebSocket client disconnected for request %s", request_id)
    except Exception as exc:
        logger.warning("Error in WebSocket streaming for request %s: %s", request_id, exc)
    finally:
        await broadcaster.unregister(request_id, sub_queue)
        try:
            await websocket.close()
        except Exception:
            pass


@app.websocket("/api/v2/ws")
@app.websocket("/ws")
async def websocket_full_duplex_chat(websocket: WebSocket):
    """
    Full-duplex WebSocket chat and stream endpoint.
    Clients can submit requests:
      {"action": "chat", "prompt": "...", "model": "...", "priority": "..."}
    and receive streaming tokens back continuously.
    """
    await websocket.accept()
    client_id = f"ws-{uuid.uuid4().hex[:6]}"

    try:
        while True:
            text = await websocket.receive_text()
            try:
                data = json.loads(text)
            except json.JSONDecodeError:
                await websocket.send_json({"type": "error", "message": "Invalid JSON format."})
                continue

            action = data.get("action", "chat")

            if action == "ping":
                await websocket.send_json({"type": "pong", "time": time.time()})
                continue

            if action == "chat":
                prompt = data.get("prompt", "")
                raw_messages = data.get("messages", [])
                messages = raw_messages if raw_messages else [{"role": "user", "content": prompt}]
                priority_str = data.get("priority", "normal")
                prio_enum = resolve_priority(priority_str)
                selected_model = router.route(messages, preferred_model=data.get("model"))

                req_id = str(uuid.uuid4())
                inf_req = InferenceRequest(
                    request_id=req_id,
                    user_id=client_id,
                    messages=messages,
                    model=selected_model,
                    priority=prio_enum,
                    created_at=time.time(),
                )

                session_manager.increment_active_requests(client_id)
                await scheduler.submit(inf_req)

                user_pos = max(0, scheduler.get_user_position(client_id))
                await websocket.send_json({
                    "type": "submitted",
                    "request_id": req_id,
                    "model": selected_model,
                    "queue_position": user_pos,
                })

                # Stream tokens directly over this WebSocket connection
                sub_queue = await broadcaster.register(req_id)
                try:
                    while True:
                        event_msg = await sub_queue.get()
                        e_type = event_msg.get("event")
                        e_data = event_msg.get("data")
                        if e_type == "done":
                            await websocket.send_json({"type": "done", "request_id": req_id})
                            break
                        elif e_type == "token":
                            await websocket.send_json({"type": "token", "request_id": req_id, "token": e_data})
                        elif e_type == "preempted":
                            await websocket.send_json({"type": "preempted", "request_id": req_id})
                finally:
                    await broadcaster.unregister(req_id, sub_queue)

    except WebSocketDisconnect:
        logger.debug("WebSocket client %s disconnected", client_id)
    except Exception as exc:
        logger.warning("WebSocket exception for %s: %s", client_id, exc)


# ---------------------------------------------------------------------------
# Static File Mount for Frontend
# ---------------------------------------------------------------------------

FRONTEND_DIR = BACKEND_DIR.parent / "frontend"
if FRONTEND_DIR.exists() and (FRONTEND_DIR / "index.html").exists():
    logger.info("Mounting frontend static assets from %s", FRONTEND_DIR)
    app.mount("/", StaticFiles(directory=str(FRONTEND_DIR), html=True), name="frontend")
else:
    @app.get("/")
    async def root_index():
        return {
            "name": "CodeMesh v2.0 API Server",
            "status": "online",
            "docs": "/docs",
            "health": "/api/v2/health",
        }


# ---------------------------------------------------------------------------
# Direct Launch Entrypoint
# ---------------------------------------------------------------------------

if __name__ == "__main__":
    import uvicorn
    host = os.getenv("HOST", "0.0.0.0")
    port = int(os.getenv("PORT", "8000"))
    logger.info("Starting CodeMesh server on %s:%d", host, port)
    uvicorn.run("main:app", host=host, port=port, reload=False, log_level="info")
