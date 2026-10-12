# CodeMesh v2.0 — Multi-Model LLM Scheduler

> **CPU Scheduling Meets AI Inference:** Round Robin + Priority Queue for fair multi-user LLM access over an offline WiFi mesh.

---

## 🏗️ Architecture Overview

```
┌─────────────────────────────────────────────────────────┐
│  USERS (Phone / Tablet / Browser)                       │
│  ┌──────┐ ┌──────┐ ┌──────┐                           │
│  │ U001 │ │ U002 │ │ U003 │  ← up to 8 concurrent     │
│  └──┬───┘ └──┬───┘ └──┬───┘                           │
│     └────────┼────────┘                                │
│              ▼                                          │
│     ┌─────────────────┐                                │
│     │  ESP8266 Bridge  │  ← WiFi AP "LLM-Bridge"       │
│     │  Multi-User      │    Tracks users by IP          │
│     │  Priority Headers│    X-User-Id, X-Priority       │
│     └────────┬────────┘                                │
│              ▼                                          │
│     ┌─────────────────────────────────────────┐        │
│     │  FastAPI Backend (Laptop / PC)          │        │
│     │                                          │        │
│     │  ┌──────────────────────────────────┐   │        │
│     │  │     PRIORITY QUEUE               │   │        │
│     │  │  CRITICAL > HIGH > NORMAL > LOW  │   │        │
│     │  │  + Aging (starvation prevention) │   │        │
│     │  └────────────┬─────────────────────┘   │        │
│     │               ▼                          │        │
│     │  ┌──────────────────────────────────┐   │        │
│     │  │    ROUND ROBIN SCHEDULER         │   │        │
│     │  │  Time Quantum: 512 tokens/slice  │   │        │
│     │  │  Circular active ring            │   │        │
│     │  │  Preemption for CRITICAL         │   │        │
│     │  └────────────┬─────────────────────┘   │        │
│     │               ▼                          │        │
│     │  ┌──────────────────────────────────┐   │        │
│     │  │   CONTINUOUS BATCHER             │   │        │
│     │  │  Generate Q tokens per slice     │   │        │
│     │  │  Save/resume context per user    │   │        │
│     │  └────────────┬─────────────────────┘   │        │
│     │               ▼                          │        │
│     │  ┌──────────────────────────────────┐   │        │
│     │  │   MULTI-MODEL ROUTER             │   │        │
│     │  │  CodeLlama ← code queries        │   │        │
│     │  │  LLaMA 3   ← general Q&A        │   │        │
│     │  │  Mistral   ← reasoning/math      │   │        │
│     │  └──────────────────────────────────┘   │        │
│     │                                          │        │
│     │  + Response Cache (LRU + TTL)           │        │
│     │  + Session Manager (fair share 40%)     │        │
│     └─────────────────────────────────────────┘        │
│              ▼                                          │
│     ┌─────────────────┐                                │
│     │  Ollama Runtime  │  ← localhost:11434             │
│     │  3 Models Loaded │                                │
│     └─────────────────┘                                │
└─────────────────────────────────────────────────────────┘
```

## 📂 Project Structure

```
CodeMesh-v2/
├── firmware/
│   └── codemesh_v2.ino          # ESP8266 firmware (multi-user, priority headers, OLED)
├── backend/
│   ├── main.py                  # FastAPI server + SSE + WebSocket + scheduler loop
│   ├── requirements.txt
│   └── scheduler/
│       ├── __init__.py
│       ├── engine.py            # RoundRobinScheduler + PriorityQueue + SchedulerStats
│       ├── batcher.py           # ContinuousBatcher (Ollama token slice streaming)
│       ├── router.py            # ModelRouter (auto-route: code/general/reasoning)
│       ├── sessions.py          # SessionManager + UserSession (fair share)
│       └── cache.py             # ResponseCache (LRU + SHA-256 prompt hashing)
├── frontend/
│   ├── index.html               # Dark-theme UI with queue visualization
│   ├── styles.css               # Glassmorphism + animations
│   └── app.js                   # SSE streaming + queue polling + stats dashboard
└── README.md
```

## 🚀 Quick Start

### 1. Start Ollama with 3 Models
```bash
ollama pull llama3:8b
ollama pull codellama:7b
ollama pull mistral:7b
ollama serve
```

### 2. Start the Backend
```bash
cd backend
pip install -r requirements.txt
uvicorn main:app --host 0.0.0.0 --port 8000
```

### 3. Flash the ESP8266
1. Open `firmware/codemesh_v2.ino` in Arduino IDE.
2. Set Board: NodeMCU 1.0 (ESP-12E).
3. Upload.
4. The ESP creates WiFi AP "LLM-Bridge" (password: `llm12345`).

### 4. Connect & Use
1. Connect your phone/tablet to "LLM-Bridge" WiFi.
2. Connect your laptop (running Ollama + FastAPI) to "LLM-Bridge" WiFi.
3. Open `http://192.168.4.1` on your phone — the queue-aware chat UI loads.

## 🧠 How the Scheduler Works

### Round Robin (Not FCFS!)
Unlike traditional systems that serve requests first-come-first-served (blocking all others),
CodeMesh uses **Round Robin scheduling**:

1. Each user gets a **time quantum** of 512 tokens.
2. After generating 512 tokens for User A, the scheduler **rotates** to User B.
3. User A's generation is **paused** (context saved) and resumed later.
4. This ensures **fair, responsive access** for all connected users.

### Priority Queue
Requests enter a **priority queue** before the RR ring:

| Priority | Use Case | Behavior |
|----------|----------|----------|
| CRITICAL | Security audit, urgent fix | **Preempts** active requests |
| HIGH | Important code generation | Promoted ahead of NORMAL |
| NORMAL | Regular chat/code queries | Standard Round Robin |
| LOW | Background tasks | Runs when queue is empty |

### Starvation Prevention (Aging)
- After **5 skips**, LOW priority → NORMAL.
- After **10 skips**, NORMAL → HIGH.
- No request is starved forever.

### Continuous Batching
Instead of waiting for a full response:
- Generate tokens in **slices** (time quanta).
- Between slices, **yield to other users**.
- Resume from saved context when turn comes back.

## 🤖 Smart Model Router
Queries are automatically routed to the best model:

| Query Type | Model | Keywords |
|-----------|-------|----------|
| Code | `codellama:7b` | write, debug, function, class, implement, python |
| General | `llama3:8b` | explain, what, how, why, describe, help |
| Reasoning | `mistral:7b` | compare, analyze, logic, math, optimize |

## 📊 API Endpoints

| Endpoint | Method | Description |
|----------|--------|-------------|
| `/api/v2/chat` | POST | Submit chat request with priority |
| `/api/v2/stream/{id}` | GET | SSE token stream |
| `/api/v2/queue` | GET | Queue status + active requests |
| `/api/v2/models` | GET | Models + health + specialty |
| `/api/v2/stats` | GET | Scheduler telemetry |
| `/api/v2/preempt` | POST | Critical priority request |
| `/api/v2/health` | GET | Health check |

## 💡 Novel Features

1. **Response Caching** — Identical prompts served instantly from LRU cache.
2. **Fair Share Enforcement** — No user can consume >40% of total throughput.
3. **Adaptive Time Quantum** — Adjusts based on queue depth and user wait time.
4. **Speculative Model Switching** — If primary model is slow, tries a faster one.
5. **Request Coalescing** — Similar concurrent queries share one generation.

|

All software is free and open-source. Your existing laptop runs the LLM.
