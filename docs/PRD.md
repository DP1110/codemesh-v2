# Ralph Product Requirements Document (PRD)
## Project: CodeMesh v2.2 — Unified Multi-Tenant Autonomous Edge LLM Mesh

### 1. Executive Summary & Vision
CodeMesh v2.2 elevates the existing ESP8266 + local Ollama mesh architecture into a fully autonomous, production-grade edge inference system. It integrates:
1. **Adaptive Time-Quantum Engine**: Dynamically scales time slices (128 to 1024 tokens) based on active peer count, network jitter, and queue pressure.
2. **Speculative Model Pre-warming & Fallback Switching**: Predictively pre-warms candidate models in Ollama and executes instant fallback if inference latency exceeds deadlines.
3. **Android Jetpack Compose Real-Time Telemetry & Priority Controls**: Native UI badges showing real-time queue ordinal positions, priority switches (Critical/High/Normal/Low), and live streaming cursor.
4. **Offline Semantic RAG & Note Search**: Lightweight local vector/BM25 retrieval for local offline documentation and code snippets.

---

### 2. User Personas & Problem Statement
- **Students & Edge Developers**: Working in offline labs or areas with zero internet connectivity who need responsive, shared LLM access across multiple laptops and Android phones.
- **The Core Problem**: Fixed round-robin time quanta either cause sluggish interactivity when many clients connect or wasteful context switching when only one user is querying. Mobile clients lacked native visibility into queue placement.

---

### 3. Detailed Functional Requirements

#### 3.1 Adaptive Time-Quantum (ATQ) Engine
- **FR-1.1**: The scheduler must calculate dynamic quantum $Q_t = \text{clamp}(Q_{\text{base}} \times \frac{1}{\sqrt{N_{\text{active}}}}, 128, 1024)$.
- **FR-1.2**: Starvation-mitigating burst credits: long-waiting tasks accrue token bonus multipliers.
- **FR-1.3**: Automatic quantum shrinkage during peak concurrency to guarantee first-token response within $< 1.5\text{s}$.

#### 3.2 Speculative Model Pre-Warming & Latency Watchdog
- **FR-2.1**: When a prompt arrives, classify model candidate and trigger background `/api/generate` with zero-length ping to warm weights in VRAM/RAM.
- **FR-2.2**: If primary model TTFT (Time-To-First-Token) exceeds 4500ms, seamlessly divert stream to a lightweight fallback model (`llama3:8b` or quantized equivalent).

#### 3.3 Native Android Real-Time Telemetry & Priority Controls
- **FR-3.1**: Extend `BridgeClient.kt` to parse `X-Queue-Position`, `X-Quantum-Remaining`, and SSE `queue_update` events.
- **FR-3.2**: Add interactive Priority Selector pill inside Jetpack Compose `ChatScreen.kt` (Critical, High, Normal).
- **FR-3.3**: Display real-time animated queue status badge in TopBar.

#### 3.4 Embedded Offline Semantic Search (RAG)
- **FR-4.1**: Fast, zero-dependency offline BM25 + keyword chunk indexer for local codebases and markdown documentation.
- **FR-4.2**: `/api/v2/rag/search` endpoint allowing Monaco/Android client to inject relevant offline snippets into context.

---

### 4. Non-Functional Requirements & Security
- **NFR-1 (Resilience)**: Zero crash on client disconnect; gracefully release locks and return unused token slices to the scheduler pool.
- **NFR-2 (Memory Guard)**: Memory footprint on ESP8266 must not exceed 60KB heap. On the backend, response cache bounded by LRU 500 items.
- **NFR-3 (Offline First)**: 100% operation without WAN or internet access.

---

### 5. Acceptance Criteria & Test Matrix
- [x] Adaptive quantum recalculates on every tick as active users scale from 1 to 8.
- [x] Preemption triggers instantly when a `CRITICAL` request is dispatched.
- [x] Android client successfully displays queue position and priority options.
- [x] Local RAG indexer returns ranked snippets in $< 25\text{ms}$.
- [x] Unit test suite covering all new scheduler, router, and RAG modules passes 100%.
