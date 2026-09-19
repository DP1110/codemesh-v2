/*
 * CodeMesh v2 — ESP8266 NDJSON Streaming Bridge
 * 
 * Creates a Wi-Fi AP ("LLM-Bridge") and acts as a transparent
 * NDJSON streaming relay between the CodeMesh Android app and
 * Ollama running on a laptop/PC.
 * 
 * Endpoints:
 *   POST /chat     → Relay to Ollama /api/chat with stream:true, return NDJSON
 *   GET  /status   → Bridge health + config info
 *   POST /config   → Update laptopIP and ollamaModel at runtime
 *   GET  /models   → Proxy Ollama /api/tags (list available models)
 *
 * Hardware: ESP8266 (NodeMCU, Wemos D1 Mini, etc.)
 * Libraries: ESP8266WiFi, ESP8266WebServer, ESP8266HTTPClient, ArduinoJson (v6)
 * 
 * Install in Arduino IDE:
 *   1. File → Preferences → Board Manager URLs: 
 *      http://arduino.esp8266.com/stable/package_esp8266com_index.json
 *   2. Tools → Board → Board Manager → search "esp8266" → Install
 *   3. Tools → Manage Libraries → search "ArduinoJson" → Install (v6.x)
 *   4. Tools → Board → NodeMCU 1.0 (or your ESP8266 variant)
 *   5. Upload
 */

#include <ESP8266WiFi.h>
#include <ESP8266WebServer.h>
#include <ESP8266HTTPClient.h>
#include <WiFiClient.h>
#include <ArduinoJson.h>

// ─── Network Configuration ──────────────────────────────────────
const char* AP_SSID     = "LLM-Bridge";
const char* AP_PASS     = "llm12345";
const int   SERVER_PORT = 80;

// ─── Ollama Defaults (changeable via /config) ───────────────────
String laptopIP     = "192.168.4.2";
int    ollamaPort   = 11434;
String ollamaModel  = "llama3";

// ─── Safety Limits ──────────────────────────────────────────────
#define MIN_HEAP_THRESHOLD  10240   // refuse requests below this
#define MAX_JSON_SIZE       8192    // max incoming JSON body parse
#define OLLAMA_TIMEOUT      180000  // 3 min for long code generation
#define CONNECT_TIMEOUT     10000   // 10s to connect to Ollama
#define LINE_TIMEOUT        120000  // 2 min max wait for a single line

// ─── Global Objects ─────────────────────────────────────────────
ESP8266WebServer server(SERVER_PORT);
unsigned long lastRequestTime = 0;
unsigned long requestCount = 0;

// ─── CORS Headers ───────────────────────────────────────────────
void sendCORS() {
  server.sendHeader("Access-Control-Allow-Origin", "*");
  server.sendHeader("Access-Control-Allow-Methods", "GET, POST, OPTIONS");
  server.sendHeader("Access-Control-Allow-Headers", "Content-Type, Accept");
}

void handleOptions() {
  sendCORS();
  server.send(204);
}

// ─── Heap Guard ─────────────────────────────────────────────────
bool checkHeap() {
  if (ESP.getFreeHeap() < MIN_HEAP_THRESHOLD) {
    Serial.printf("[WARN] Low heap: %u bytes\n", ESP.getFreeHeap());
    sendCORS();
    server.send(503, "application/json",
      "{\"error\":\"ESP8266 low memory, try again\",\"done\":true}");
    return false;
  }
  return true;
}

// ─── Ollama Ping (quick connectivity check) ─────────────────────
bool pingOllama() {
  WiFiClient client;
  HTTPClient http;
  String url = "http://" + laptopIP + ":" + String(ollamaPort) + "/api/tags";
  http.begin(client, url);
  http.setTimeout(5000);
  int code = http.GET();
  http.end();
  return (code == 200);
}

// ═══════════════════════════════════════════════════════════════
// POST /chat — The core NDJSON streaming relay
// ═══════════════════════════════════════════════════════════════
void handleChat() {
  if (server.method() == HTTP_OPTIONS) {
    handleOptions();
    return;
  }
  if (!checkHeap()) return;

  // ── 1. Read & validate incoming JSON body ──
  if (!server.hasArg("plain")) {
    sendCORS();
    server.send(400, "application/json", "{\"error\":\"missing request body\",\"done\":true}");
    return;
  }

  String body = server.arg("plain");
  if (body.length() < 10 || body.length() > MAX_JSON_SIZE) {
    sendCORS();
    server.send(400, "application/json", "{\"error\":\"body too small or too large\",\"done\":true}");
    return;
  }

  Serial.printf("\n[CHAT] Incoming request (%u bytes), heap: %u\n", body.length(), ESP.getFreeHeap());

  // ── 2. Parse JSON and inject stream:true ──
  DynamicJsonDocument doc(MAX_JSON_SIZE);
  DeserializationError jsonErr = deserializeJson(doc, body);
  if (jsonErr) {
    Serial.printf("[CHAT] JSON parse error: %s\n", jsonErr.c_str());
    sendCORS();
    server.send(400, "application/json", "{\"error\":\"invalid JSON\",\"done\":true}");
    return;
  }

  doc["stream"] = true;
  if (!doc.containsKey("model") || doc["model"].as<String>().length() == 0) {
    doc["model"] = ollamaModel;
  }

  String outPayload;
  serializeJson(doc, outPayload);
  doc.clear();  // Free RAM immediately

  Serial.printf("[CHAT] Forwarding to Ollama at %s:%d (model: %s)\n",
    laptopIP.c_str(), ollamaPort, doc["model"] | ollamaModel.c_str());

  // ── 3. Connect to Ollama via raw WiFiClient ──
  WiFiClient ollamaClient;
  ollamaClient.setTimeout(CONNECT_TIMEOUT);

  if (!ollamaClient.connect(laptopIP.c_str(), ollamaPort)) {
    Serial.println("[CHAT] ERROR: Cannot connect to Ollama");
    sendCORS();
    server.setContentLength(CONTENT_LENGTH_UNKNOWN);
    server.sendHeader("Content-Type", "application/x-ndjson");
    server.send(200);
    server.sendContent("{\"error\":\"cannot reach Ollama at " + laptopIP + ":" + String(ollamaPort) + "\",\"done\":true}\n");
    server.sendContent("");
    return;
  }

  // ── 4. Send raw HTTP POST to Ollama /api/chat ──
  ollamaClient.print("POST /api/chat HTTP/1.1\r\n");
  ollamaClient.print("Host: " + laptopIP + ":" + String(ollamaPort) + "\r\n");
  ollamaClient.print("Content-Type: application/json\r\n");
  ollamaClient.print("Content-Length: " + String(outPayload.length()) + "\r\n");
  ollamaClient.print("Connection: close\r\n");
  ollamaClient.print("\r\n");
  ollamaClient.print(outPayload);

  outPayload = "";  // Free RAM

  // ── 5. Wait for response and skip HTTP headers ──
  unsigned long headerStart = millis();
  bool headersSkipped = false;

  while (ollamaClient.connected() && (millis() - headerStart < CONNECT_TIMEOUT)) {
    if (ollamaClient.available()) {
      String headerLine = ollamaClient.readStringUntil('\n');
      headerLine.trim();

      // Check for HTTP error status
      if (headerLine.startsWith("HTTP/") && !headerLine.indexOf("200")) {
        if (headerLine.indexOf("200") < 0) {
          Serial.printf("[CHAT] Ollama HTTP error: %s\n", headerLine.c_str());
          sendCORS();
          server.setContentLength(CONTENT_LENGTH_UNKNOWN);
          server.sendHeader("Content-Type", "application/x-ndjson");
          server.send(200);
          server.sendContent("{\"error\":\"Ollama returned: " + headerLine + "\",\"done\":true}\n");
          server.sendContent("");
          ollamaClient.stop();
          return;
        }
      }

      if (headerLine.length() == 0) {
        headersSkipped = true;
        break;
      }
    }
    yield();
  }

  if (!headersSkipped) {
    Serial.println("[CHAT] ERROR: Timeout waiting for Ollama response headers");
    sendCORS();
    server.setContentLength(CONTENT_LENGTH_UNKNOWN);
    server.sendHeader("Content-Type", "application/x-ndjson");
    server.send(200);
    server.sendContent("{\"error\":\"Ollama response timeout\",\"done\":true}\n");
    server.sendContent("");
    ollamaClient.stop();
    return;
  }

  // ── 6. Begin streaming NDJSON to phone ──
  sendCORS();
  server.setContentLength(CONTENT_LENGTH_UNKNOWN);
  server.sendHeader("Content-Type", "application/x-ndjson");
  server.send(200);

  unsigned long lastDataTime = millis();
  unsigned long totalBytes = 0;
  unsigned long lineCount = 0;

  while (ollamaClient.connected() || ollamaClient.available()) {
    // Check if the phone/app disconnected
    if (!server.client().connected()) {
      Serial.println("[CHAT] Phone client disconnected, aborting stream");
      ollamaClient.stop();
      break;
    }

    // Check for line-level timeout
    if (millis() - lastDataTime > LINE_TIMEOUT) {
      Serial.println("[CHAT] ERROR: Line read timeout");
      server.sendContent("{\"error\":\"stream timeout\",\"done\":true}\n");
      break;
    }

    if (ollamaClient.available()) {
      String line = ollamaClient.readStringUntil('\n');
      line.trim();

      if (line.length() > 0) {
        server.sendContent(line + "\n");
        totalBytes += line.length() + 1;
        lineCount++;
        lastDataTime = millis();

        // Check for done:true to break early
        if (line.indexOf("\"done\":true") >= 0 || line.indexOf("\"done\": true") >= 0) {
          Serial.printf("[CHAT] Stream complete: %lu lines, %lu bytes\n", lineCount, totalBytes);
          break;
        }
      }
    }
    yield();  // Prevent watchdog reset
  }

  // ── 7. Finalize chunked transfer ──
  server.sendContent("");
  ollamaClient.stop();

  requestCount++;
  lastRequestTime = millis();
  Serial.printf("[CHAT] Done. Heap: %u, total requests: %lu\n", ESP.getFreeHeap(), requestCount);
}

// ═══════════════════════════════════════════════════════════════
// GET /status — Bridge health check
// ═══════════════════════════════════════════════════════════════
void handleStatus() {
  if (server.method() == HTTP_OPTIONS) {
    handleOptions();
    return;
  }

  bool ollamaOnline = pingOllama();

  DynamicJsonDocument doc(512);
  doc["model"]        = ollamaModel;
  doc["ip"]           = laptopIP;
  doc["port"]         = ollamaPort;
  doc["free_mem"]     = ESP.getFreeHeap();
  doc["uptime_sec"]   = millis() / 1000;
  doc["requests"]     = requestCount;
  doc["clients"]      = WiFi.softAPgetStationNum();
  doc["ollama_online"] = ollamaOnline;

  String response;
  serializeJson(doc, response);

  sendCORS();
  server.send(200, "application/json", response);
}

// ═══════════════════════════════════════════════════════════════
// POST /config — Runtime configuration update
// ═══════════════════════════════════════════════════════════════
void handleConfig() {
  if (server.method() == HTTP_OPTIONS) {
    handleOptions();
    return;
  }

  bool changed = false;

  if (server.hasArg("ip") && server.arg("ip").length() > 0) {
    String newIP = server.arg("ip");
    if (newIP != laptopIP) {
      laptopIP = newIP;
      changed = true;
      Serial.printf("[CONFIG] Laptop IP updated to: %s\n", laptopIP.c_str());
    }
  }
  if (server.hasArg("model") && server.arg("model").length() > 0) {
    String newModel = server.arg("model");
    if (newModel != ollamaModel) {
      ollamaModel = newModel;
      changed = true;
      Serial.printf("[CONFIG] Model updated to: %s\n", ollamaModel.c_str());
    }
  }
  if (server.hasArg("port")) {
    int newPort = server.arg("port").toInt();
    if (newPort > 0 && newPort < 65536 && newPort != ollamaPort) {
      ollamaPort = newPort;
      changed = true;
      Serial.printf("[CONFIG] Port updated to: %d\n", ollamaPort);
    }
  }

  DynamicJsonDocument doc(256);
  doc["status"]  = "OK";
  doc["changed"] = changed;
  doc["ip"]      = laptopIP;
  doc["model"]   = ollamaModel;
  doc["port"]    = ollamaPort;

  String response;
  serializeJson(doc, response);

  sendCORS();
  server.send(200, "application/json", response);
}

// ═══════════════════════════════════════════════════════════════
// GET /models — Proxy Ollama /api/tags
// ═══════════════════════════════════════════════════════════════
void handleModels() {
  if (server.method() == HTTP_OPTIONS) {
    handleOptions();
    return;
  }

  WiFiClient client;
  HTTPClient http;

  String url = "http://" + laptopIP + ":" + String(ollamaPort) + "/api/tags";
  Serial.printf("[MODELS] Fetching from %s\n", url.c_str());

  if (!http.begin(client, url)) {
    sendCORS();
    server.send(502, "application/json", "{\"models\":[],\"error\":\"Cannot connect to Ollama\"}");
    return;
  }

  http.setTimeout(10000);
  int httpCode = http.GET();

  if (httpCode == HTTP_CODE_OK) {
    String payload = http.getString();
    sendCORS();
    server.send(200, "application/json", payload);
    Serial.printf("[MODELS] Success, %u bytes\n", payload.length());
  } else if (httpCode > 0) {
    sendCORS();
    server.send(502, "application/json",
      "{\"models\":[],\"error\":\"Ollama returned HTTP " + String(httpCode) + "\"}");
    Serial.printf("[MODELS] Ollama error: HTTP %d\n", httpCode);
  } else {
    sendCORS();
    server.send(502, "application/json",
      "{\"models\":[],\"error\":\"Cannot reach Ollama at " + laptopIP + "\"}");
    Serial.printf("[MODELS] Connection failed: %s\n", http.errorToString(httpCode).c_str());
  }

  http.end();
}

// ═══════════════════════════════════════════════════════════════
// Setup & Loop
// ═══════════════════════════════════════════════════════════════
void setup() {
  Serial.begin(115200);
  delay(100);

  Serial.println("\n\n================================");
  Serial.println("  CodeMesh v2 — LLM Bridge");
  Serial.println("================================");

  // Start Wi-Fi Access Point
  WiFi.mode(WIFI_AP);
  WiFi.softAP(AP_SSID, AP_PASS);
  delay(500);

  Serial.printf("AP SSID:     %s\n", AP_SSID);
  Serial.printf("AP Password: %s\n", AP_PASS);
  Serial.printf("AP IP:       %s\n", WiFi.softAPIP().toString().c_str());
  Serial.printf("Laptop IP:   %s\n", laptopIP.c_str());
  Serial.printf("Ollama Port: %d\n", ollamaPort);
  Serial.printf("Model:       %s\n", ollamaModel.c_str());
  Serial.printf("Free Heap:   %u bytes\n", ESP.getFreeHeap());

  // Register routes
  server.on("/chat",    HTTP_POST,    handleChat);
  server.on("/chat",    HTTP_OPTIONS, handleOptions);
  server.on("/status",  HTTP_GET,     handleStatus);
  server.on("/status",  HTTP_OPTIONS, handleOptions);
  server.on("/config",  HTTP_POST,    handleConfig);
  server.on("/config",  HTTP_OPTIONS, handleOptions);
  server.on("/models",  HTTP_GET,     handleModels);
  server.on("/models",  HTTP_OPTIONS, handleOptions);

  server.begin();
  Serial.println("\nHTTP server started on port 80");
  Serial.println("Ready for connections!\n");
}

void loop() {
  server.handleClient();
  yield();
}
