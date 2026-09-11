# CodeMesh v2

> Claude Desktop–class offline LLM chat app powered by ESP8266 + Ollama

<p align="center">
  <img src="https://img.shields.io/badge/Android-Kotlin-7F52FF?logo=kotlin&logoColor=white" />
  <img src="https://img.shields.io/badge/Jetpack_Compose-Material3-4285F4?logo=jetpackcompose&logoColor=white" />
  <img src="https://img.shields.io/badge/ESP8266-Arduino-00979D?logo=arduino&logoColor=white" />
  <img src="https://img.shields.io/badge/Ollama-LLM-000000?logo=ollama&logoColor=white" />
</p>

---

## What is CodeMesh?

CodeMesh is a **fully offline** AI coding assistant that runs on your phone. It connects to a local LLM (via [Ollama](https://ollama.com)) through an ESP8266 Wi-Fi bridge — no internet required.

**Think Claude Desktop, but running entirely on your local network.**

### Features

- 🔥 **Live streaming** — tokens appear as they're generated, with blinking cursor
- 💬 **Multi-turn chat** — full conversation history stored locally (Room DB)
- 📝 **Markdown rendering** — headings, tables, bold/italic via Markwon
- 💻 **Code blocks** — syntax-highlighted with copy-to-clipboard buttons
- 📂 **Session management** — create, rename, delete chat sessions (Claude-style sidebar)
- ⚙️ **Configurable** — bridge IP, laptop IP, model, system prompt, history depth
- 🌙 **Dark theme** — cyberpunk-inspired dark UI with cyan/green accents
- 🛑 **Stop generation** — cancel mid-stream by closing the HTTP connection

---

## Architecture

```
📱 Phone App                  📡 ESP8266 Bridge              💻 Laptop
(Kotlin/Compose)              (Arduino firmware)              (Ollama)
     │                              │                            │
     │── POST /chat ──────────────►│── POST /api/chat ─────────►│
     │                              │   (stream: true)           │
     │◄── NDJSON line ─────────────│◄── NDJSON line ────────────│
     │◄── NDJSON line ─────────────│◄── NDJSON line ────────────│
     │◄── {done:true} ────────────│◄── {done:true} ────────────│
     │                              │                            │
     │  Wi-Fi AP: LLM-Bridge       │      USB/Ethernet          │
     │  192.168.4.x                │      192.168.4.2            │
```

The ESP8266 acts as a **dumb NDJSON relay** — it never buffers the full response (max 512B per line). This means RAM stays constant regardless of how long the LLM answer is.

---

## Quick Start

### Prerequisites

- ESP8266 board (NodeMCU, Wemos D1 Mini, etc.)
- Android phone (API 26+ / Android 8.0+)
- Laptop with [Ollama](https://ollama.com) installed
- Arduino IDE (for firmware)
- Android Studio (for APK, or use prebuilt)

### 1. Flash the Firmware

1. Open `firmware/llm_bridge_v2.ino` in Arduino IDE
2. Install **ESP8266** board package (Tools → Board Manager → search "esp8266")
3. Install **ArduinoJson** library (Tools → Manage Libraries)
4. Select your ESP8266 board and flash via USB

### 2. Build the Android App

```bash
# Clone the repo
git clone https://github.com/DP1110/codemesh-v2.git
cd codemesh-v2

# Build (requires Android SDK)
./gradlew assembleDebug

# Install
adb install app/build/outputs/apk/debug/app-debug.apk
```

Or open the project in Android Studio and run directly.

### 3. Connect Everything

1. **Start Ollama** on your laptop:
   ```bash
   OLLAMA_HOST=0.0.0.0 ollama serve
   ```
2. **Pull a model** (if you haven't already):
   ```bash
   ollama pull llama3
   ```
3. **Power on** the ESP8266 bridge
4. **Connect both** your phone and laptop to Wi-Fi: `LLM-Bridge` (password: `llm12345`)
5. **Open CodeMesh** on your phone — it will auto-detect the bridge
6. **Start chatting!** 🎉

---

## Project Structure

```
codemesh-v2/
├── firmware/
│   └── llm_bridge_v2.ino           # ESP8266 NDJSON streaming relay
├── app/src/main/java/com/codemesh/app/
│   ├── data/                        # Room DB: Session, Message, DAOs
│   ├── model/                       # ChatChunk, Status, ModelTag
│   ├── network/                     # OkHttp client + NDJSON parser
│   ├── viewmodel/                   # ChatViewModel, SettingsViewModel
│   └── ui/                          # Jetpack Compose screens
│       ├── ChatScreen.kt            # Main chat with streaming
│       ├── SessionSidebar.kt        # Claude-style session drawer
│       ├── SettingsScreen.kt        # Configuration
│       ├── SetupScreen.kt           # Connection wizard
│       ├── MarkdownRenderer.kt      # Markwon + code blocks
│       └── Theme.kt                 # Dark cyberpunk theme
└── docs/
    └── WORKFLOW_v2.md               # Specification
```

## Bridge API

| Endpoint | Method | Description |
|----------|--------|-------------|
| `/chat` | POST | Relay chat to Ollama, stream NDJSON back |
| `/status` | GET | Bridge status (model, IP, free memory) |
| `/config` | POST | Update laptop IP and model |
| `/models` | GET | List available Ollama models |

---

## Tech Stack

| Component | Technology |
|-----------|-----------|
| Android UI | Jetpack Compose + Material 3 |
| Database | Room 2.6.1 |
| HTTP | OkHttp 4.12.0 |
| Markdown | Markwon 4.6.2 + Prism4j |
| Firmware | Arduino (ESP8266) |
| LLM Backend | Ollama |

---

## License

MIT
