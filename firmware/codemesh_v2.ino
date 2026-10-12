/*
 * =========================================================================================
 *  CodeMesh v2.0 - Multi-User Offline WiFi Bridge Firmware
 * =========================================================================================
 *  Hardware Target: ESP8266 NodeMCU V3 (ESP-12E / ESP-12F)
 *  Architecture:
 *    - Standalone WiFi Access Point + Station Dual Mode (AP+STA) or AP Only
 *    - In-Memory Client & Multi-User Registry (up to 8 clients, tracking IP -> U001..U008)
 *    - Priority-Aware HTTP Proxying to Laptop FastAPI Backend (e.g., http://192.168.4.2:8000)
 *    - Chunked SSE / NDJSON Real-Time Streaming Relay with Client Disconnect & Heap Guards
 *    - Optional I2C OLED SSD1306 Display for Telemetry
 *    - Embedded PROGMEM Dark-Themed Web Dashboard (Auto-refreshing)
 *
 *  Dependencies:
 *    - ESP8266WiFi, ESP8266WebServer, WiFiClient (ESP8266 Arduino Core)
 *    - ArduinoJson (v6.x or v7.x compatible)
 *    - (Optional) Wire.h, Adafruit_GFX.h, Adafruit_SSD1306.h
 * =========================================================================================
 */

#include <ESP8266WiFi.h>
#include <ESP8266WebServer.h>
#include <WiFiClient.h>
#include <ArduinoJson.h>

// =========================================================================================
//  FEATURE FLAGS & HARDWARE CONFIGURATION
// =========================================================================================
#define ENABLE_OLED 0  // Set to 1 if 0.96" SSD1306 OLED (I2C 0x3C) is attached

#if ENABLE_OLED
  #include <Wire.h>
  #include <Adafruit_GFX.h>
  #include <Adafruit_SSD1306.h>
  #define SCREEN_WIDTH 128
  #define SCREEN_HEIGHT 64
  #define OLED_RESET -1
  #define OLED_ADDR 0x3C
  Adafruit_SSD1306 display(SCREEN_WIDTH, SCREEN_HEIGHT, &Wire, OLED_RESET);
#endif

// =========================================================================================
//  NETWORK & BACKEND CONFIGURATION DEFAULTS
// =========================================================================================
const char* AP_SSID = "CodeMesh-AP";
const char* AP_PASS = "codemesh123";

// Upstream FastAPI server running on the host machine
IPAddress backendIP(192, 168, 4, 2);  // Default: first DHCP client connecting to AP
uint16_t backendPort = 8000;

// Safety & Limits
const size_t MIN_FREE_HEAP = 10240;             // 10 KB Heap Guard threshold
const unsigned long BACKEND_CONNECT_TIMEOUT_MS = 10000;  // 10s timeout
const unsigned long STREAM_TIMEOUT_MS = 120000;         // 2 minutes max per stream
const unsigned long OLED_UPDATE_INTERVAL_MS = 2000;     // 2s OLED refresh
const unsigned long USER_INACTIVE_TIMEOUT_MS = 300000;  // 5m client session expiry

ESP8266WebServer server(80);

// =========================================================================================
//  MULTI-USER TRACKING REGISTRY
// =========================================================================================
#define MAX_USERS 8

struct ClientUser {
  bool active;
  IPAddress ip;
  char userId[8];         // "U001", "U002", etc.
  unsigned long firstSeen;
  unsigned long lastSeen;
  uint32_t requestCount;
};

ClientUser userRegistry[MAX_USERS];
uint16_t nextUserSequence = 1;
uint32_t totalProxyRequests = 0;
uint32_t totalStreamRequests = 0;

// =========================================================================================
//  EMBEDDED MINI DASHBOARD HTML (PROGMEM)
// =========================================================================================
const char DASHBOARD_HTML[] PROGMEM = R"rawliteral(
<!DOCTYPE html>
<html lang="en">
<head>
  <meta charset="UTF-8">
  <meta name="viewport" content="width=device-width, initial-scale=1.0">
  <title>CodeMesh v2.0 | NodeMCU Bridge</title>
  <style>
    :root {
      --bg: #0d1117;
      --card-bg: #161b22;
      --border: #30363d;
      --text: #c9d1d9;
      --accent: #58a6ff;
      --green: #3fb950;
      --amber: #d29922;
      --red: #f85149;
    }
    * { box-sizing: border-box; margin: 0; padding: 0; font-family: -apple-system, BlinkMacSystemFont, "Segoe UI", Roboto, monospace; }
    body { background-color: var(--bg); color: var(--text); padding: 20px; line-height: 1.5; }
    .container { max-width: 900px; margin: 0 auto; }
    header { display: flex; justify-content: space-between; align-items: center; border-bottom: 1px solid var(--border); padding-bottom: 16px; margin-bottom: 20px; }
    h1 { color: var(--accent); font-size: 1.6rem; display: flex; align-items: center; gap: 8px; }
    .badge { background: #1f6feb26; color: var(--accent); border: 1px solid #1f6feb; border-radius: 12px; font-size: 0.75rem; padding: 2px 8px; }
    .grid { display: grid; grid-template-columns: repeat(auto-fit, minmax(200px, 1fr)); gap: 16px; margin-bottom: 24px; }
    .card { background: var(--card-bg); border: 1px solid var(--border); border-radius: 8px; padding: 16px; }
    .card-title { font-size: 0.8rem; text-transform: uppercase; letter-spacing: 0.05em; color: #8b949e; margin-bottom: 8px; }
    .card-value { font-size: 1.5rem; font-weight: bold; color: #f0f6fc; }
    table { width: 100%; border-collapse: collapse; margin-top: 12px; }
    th, td { text-align: left; padding: 8px 12px; border-bottom: 1px solid var(--border); font-size: 0.85rem; }
    th { color: #8b949e; background: #0d111755; }
    tr:hover { background: #21262d; }
    .links { display: flex; gap: 12px; flex-wrap: wrap; margin-top: 20px; }
    .btn { display: inline-block; background: #21262d; border: 1px solid var(--border); color: var(--text); padding: 8px 14px; border-radius: 6px; text-decoration: none; font-size: 0.85rem; transition: all 0.2s; }
    .btn:hover { background: #30363d; border-color: #8b949e; color: #fff; }
    .status-dot { display: inline-block; width: 8px; height: 8px; border-radius: 50%; background: var(--green); margin-right: 6px; }
    .footer { text-align: center; margin-top: 30px; font-size: 0.75rem; color: #8b949e; }
  </style>
</head>
<body>
  <div class="container">
    <header>
      <h1>CodeMesh v2.0 <span class="badge">Offline WiFi Bridge</span></h1>
      <div><span class="status-dot"></span><span id="uptime">Loading...</span></div>
    </header>

    <div class="grid">
      <div class="card">
        <div class="card-title">Free Heap Memory</div>
        <div class="card-value" id="freeHeap">-- KB</div>
      </div>
      <div class="card">
        <div class="card-title">Active Clients</div>
        <div class="card-value" id="activeUsers">0 / 8</div>
      </div>
      <div class="card">
        <div class="card-title">Backend Target</div>
        <div class="card-value" style="font-size: 1rem; word-break: break-all;" id="backendTarget">--</div>
      </div>
      <div class="card">
        <div class="card-title">Total Handled Requests</div>
        <div class="card-value" id="totalRequests">0</div>
      </div>
    </div>

    <div class="card" style="margin-bottom: 24px;">
      <div class="card-title">Connected Multi-User Registry</div>
      <table>
        <thead>
          <tr>
            <th>User ID</th>
            <th>Client IP</th>
            <th>Requests</th>
            <th>Last Active</th>
            <th>Status</th>
          </tr>
        </thead>
        <tbody id="userTableBody">
          <tr><td colspan="5" style="text-align:center; color:#8b949e;">Scanning connected clients...</td></tr>
        </tbody>
      </table>
    </div>

    <div class="links">
      <a class="btn" href="/status" target="_blank">ESP8266 /status</a>
      <a class="btn" href="/queue" target="_blank">Proxy /queue</a>
      <a class="btn" href="/models" target="_blank">Proxy /models</a>
      <a class="btn" href="/stats" target="_blank">Proxy /stats</a>
    </div>

    <div class="footer">
      NodeMCU V3 ESP8266 &bull; CodeMesh v2.0 Real-Time SSE Relay &bull; Auto-refresh every 5s
    </div>
  </div>

  <script>
    async function refreshStatus() {
      try {
        const res = await fetch('/status');
        if (!res.ok) throw new Error('Status fetch failed');
        const data = await res.json();

        document.getElementById('freeHeap').innerText = (data.free_heap / 1024).toFixed(1) + ' KB';
        document.getElementById('activeUsers').innerText = data.connected_users_count + ' / 8';
        document.getElementById('backendTarget').innerText = data.backend_host + ':' + data.backend_port;
        document.getElementById('totalRequests').innerText = data.total_proxy_requests;
        
        const sec = Math.floor(data.uptime_ms / 1000);
        const hrs = Math.floor(sec / 3600);
        const mins = Math.floor((sec % 3600) / 60);
        const s = sec % 60;
        document.getElementById('uptime').innerText = `Up ${hrs}h ${mins}m ${s}s`;

        const tbody = document.getElementById('userTableBody');
        if (data.users && data.users.length > 0) {
          tbody.innerHTML = data.users.map(u => `
            <tr>
              <td><strong>${u.user_id}</strong></td>
              <td><code>${u.ip}</code></td>
              <td>${u.requests}</td>
              <td>${Math.round((data.uptime_ms - u.last_seen_ms) / 1000)}s ago</td>
              <td><span style="color:${u.active ? 'var(--green)' : 'var(--amber)'}">● ${u.active ? 'Active' : 'Idle'}</span></td>
            </tr>
          `).join('');
        } else {
          tbody.innerHTML = '<tr><td colspan="5" style="text-align:center; color:#8b949e;">No active clients registered yet</td></tr>';
        }
      } catch (err) {
        console.error('Refresh error:', err);
      }
    }

    refreshStatus();
    setInterval(refreshStatus, 5000);
  </script>
</body>
</html>
)rawliteral";

// =========================================================================================
//  HELPER FUNCTIONS: CORS, REGISTRY, SAFETY
// =========================================================================================

void sendCorsHeaders() {
  server.sendHeader("Access-Control-Allow-Origin", "*");
  server.sendHeader("Access-Control-Allow-Methods", "GET, POST, OPTIONS, PUT, DELETE");
  server.sendHeader("Access-Control-Allow-Headers", "Content-Type, Authorization, X-User-Id, X-Priority, X-Client-IP");
}

bool checkHeapSafety() {
  uint32_t freeH = ESP.getFreeHeap();
  if (freeH < MIN_FREE_HEAP) {
    Serial.printf("[SAFETY] Heap critically low: %u bytes (< %u limit). Aborting request.\n", freeH, MIN_FREE_HEAP);
    sendCorsHeaders();
    server.send(503, "application/json", "{\"error\":\"ESP8266 Memory Guard: Free heap below safe threshold (10KB)\"}");
    return false;
  }
  return true;
}

// Look up or register client IP to track multi-user sessions
ClientUser* getOrRegisterClient(IPAddress clientIp) {
  unsigned long now = millis();
  int emptySlot = -1;
  int oldestSlot = 0;
  unsigned long oldestTime = 0xFFFFFFFF;

  for (int i = 0; i < MAX_USERS; i++) {
    if (userRegistry[i].active && userRegistry[i].ip == clientIp) {
      userRegistry[i].lastSeen = now;
      userRegistry[i].requestCount++;
      return &userRegistry[i];
    }
    if (!userRegistry[i].active && emptySlot == -1) {
      emptySlot = i;
    }
    if (userRegistry[i].active && userRegistry[i].lastSeen < oldestTime) {
      oldestTime = userRegistry[i].lastSeen;
      oldestSlot = i;
    }
  }

  // Not found: allocate new user
  int slotToUse = (emptySlot != -1) ? emptySlot : oldestSlot;
  
  userRegistry[slotToUse].active = true;
  userRegistry[slotToUse].ip = clientIp;
  snprintf(userRegistry[slotToUse].userId, sizeof(userRegistry[slotToUse].userId), "U%03u", nextUserSequence++);
  userRegistry[slotToUse].firstSeen = now;
  userRegistry[slotToUse].lastSeen = now;
  userRegistry[slotToUse].requestCount = 1;

  Serial.printf("[REGISTRY] Registered new client: %s -> %s (Slot %d)\n",
                clientIp.toString().c_str(), userRegistry[slotToUse].userId, slotToUse);

  return &userRegistry[slotToUse];
}

// Clean up stale client connections
void pruneStaleClients() {
  unsigned long now = millis();
  for (int i = 0; i < MAX_USERS; i++) {
    if (userRegistry[i].active && (now - userRegistry[i].lastSeen > USER_INACTIVE_TIMEOUT_MS)) {
      Serial.printf("[REGISTRY] Client %s (%s) inactive for >5m, marking idle\n",
                    userRegistry[i].userId, userRegistry[i].ip.toString().c_str());
      userRegistry[i].active = false;
    }
  }
}

// Count active connected users
uint8_t countActiveUsers() {
  uint8_t count = 0;
  for (int i = 0; i < MAX_USERS; i++) {
    if (userRegistry[i].active) count++;
  }
  return count;
}

// =========================================================================================
//  OLED DISPLAY SUBSYSTEM
// =========================================================================================
#if ENABLE_OLED
unsigned long lastOledUpdate = 0;
String activeModelName = "qwen2.5-coder";

void setupOLED() {
  Wire.begin(4, 5); // SDA = D2 (GPIO4), SCL = D1 (GPIO5) on NodeMCU
  if (!display.begin(SSD1306_SWITCHCAPVCC, OLED_ADDR)) {
    Serial.println(F("[OLED] SSD1306 allocation failed"));
  } else {
    display.clearDisplay();
    display.setTextSize(1);
    display.setTextColor(SSD1306_WHITE);
    display.setCursor(0, 0);
    display.println(F("CodeMesh v2.0"));
    display.println(F("Initializing..."));
    display.display();
  }
}

void updateOLED() {
  unsigned long now = millis();
  if (now - lastOledUpdate < OLED_UPDATE_INTERVAL_MS) return;
  lastOledUpdate = now;

  display.clearDisplay();
  display.setTextSize(1);
  display.setTextColor(SSD1306_WHITE);

  // Line 1: Header
  display.setCursor(0, 0);
  display.print(F("CodeMesh v2.0 [AP]"));

  // Line 2: IP
  display.setCursor(0, 12);
  display.print(F("IP: "));
  display.print(WiFi.softAPIP().toString());

  // Line 3: Users & Heap
  display.setCursor(0, 24);
  display.printf("Users: %u/%u | H:%uk", countActiveUsers(), MAX_USERS, ESP.getFreeHeap() / 1024);

  // Line 4: Requests
  display.setCursor(0, 36);
  display.printf("Reqs: %lu | Str:%lu", totalProxyRequests, totalStreamRequests);

  // Line 5: Active Model
  display.setCursor(0, 48);
  display.printf("Model: %.14s", activeModelName.c_str());

  display.display();
}
#endif

// =========================================================================================
//  HTTP ROUTE HANDLERS
// =========================================================================================

// Handle CORS Pre-flight requests
void handleOptions() {
  sendCorsHeaders();
  server.send(204);
}

// GET / -> Mini Status Dashboard
void handleRoot() {
  sendCorsHeaders();
  server.send_P(200, "text/html", DASHBOARD_HTML);
}

// GET /status -> Local ESP8266 health & user metrics
void handleLocalStatus() {
  if (!checkHeapSafety()) return;

  ClientUser* client = getOrRegisterClient(server.client().remoteIP());
  
  DynamicJsonDocument doc(2048);
  doc["status"] = "online";
  doc["firmware"] = "CodeMesh v2.0";
  doc["free_heap"] = ESP.getFreeHeap();
  doc["uptime_ms"] = millis();
  doc["wifi_ap_ip"] = WiFi.softAPIP().toString();
  doc["backend_host"] = backendIP.toString();
  doc["backend_port"] = backendPort;
  doc["total_proxy_requests"] = totalProxyRequests;
  doc["total_stream_requests"] = totalStreamRequests;
  doc["connected_users_count"] = countActiveUsers();
  doc["caller_user_id"] = client ? client->userId : "Unknown";

  JsonArray usersArr = doc.createNestedArray("users");
  for (int i = 0; i < MAX_USERS; i++) {
    if (userRegistry[i].active) {
      JsonObject u = usersArr.createNestedObject();
      u["user_id"] = userRegistry[i].userId;
      u["ip"] = userRegistry[i].ip.toString();
      u["requests"] = userRegistry[i].requestCount;
      u["first_seen_ms"] = userRegistry[i].firstSeen;
      u["last_seen_ms"] = userRegistry[i].lastSeen;
      u["active"] = (millis() - userRegistry[i].lastSeen < USER_INACTIVE_TIMEOUT_MS);
    }
  }

  String output;
  serializeJson(doc, output);

  sendCorsHeaders();
  server.send(200, "application/json", output);
}

// POST /config -> Update backend IP and Port dynamically
void handleConfig() {
  if (!checkHeapSafety()) return;

  if (!server.hasArg("plain")) {
    sendCorsHeaders();
    server.send(400, "application/json", "{\"error\":\"Missing JSON body\"}");
    return;
  }

  DynamicJsonDocument doc(512);
  DeserializationError err = deserializeJson(doc, server.arg("plain"));
  if (err) {
    sendCorsHeaders();
    server.send(400, "application/json", "{\"error\":\"Malformed JSON\"}");
    return;
  }

  if (doc.containsKey("backend_ip")) {
    String ipStr = doc["backend_ip"].as<String>();
    IPAddress newIp;
    if (newIp.fromString(ipStr)) {
      backendIP = newIp;
    }
  }

  if (doc.containsKey("backend_port")) {
    backendPort = doc["backend_port"].as<uint16_t>();
  }

  Serial.printf("[CONFIG] Backend updated to %s:%u\n", backendIP.toString().c_str(), backendPort);

  sendCorsHeaders();
  server.send(200, "application/json", "{\"status\":\"updated\"}");
}

// Generic Proxy Helper for Standard JSON GET Endpoints (/queue, /models, /stats)
void proxyGetToBackend(const char* backendEndpoint) {
  if (!checkHeapSafety()) return;

  IPAddress clientIp = server.client().remoteIP();
  ClientUser* client = getOrRegisterClient(clientIp);
  totalProxyRequests++;

  WiFiClient backendClient;
  backendClient.setTimeout(BACKEND_CONNECT_TIMEOUT_MS);

  if (!backendClient.connect(backendIP, backendPort)) {
    Serial.printf("[PROXY] Connection failed to backend %s:%u\n", backendIP.toString().c_str(), backendPort);
    sendCorsHeaders();
    server.send(502, "application/json", "{\"error\":\"Failed to connect to backend server\"}");
    return;
  }

  // Forward GET request with user headers
  backendClient.printf("GET %s HTTP/1.1\r\n", backendEndpoint);
  backendClient.printf("Host: %s:%u\r\n", backendIP.toString().c_str(), backendPort);
  backendClient.printf("X-User-Id: %s\r\n", client ? client->userId : "U000");
  backendClient.printf("X-Client-IP: %s\r\n", clientIp.toString().c_str());
  backendClient.print("Connection: close\r\n\r\n");

  // Read response status
  unsigned long startWait = millis();
  while (!backendClient.available() && millis() - startWait < BACKEND_CONNECT_TIMEOUT_MS) {
    yield();
  }

  if (!backendClient.available()) {
    backendClient.stop();
    sendCorsHeaders();
    server.send(504, "application/json", "{\"error\":\"Gateway timeout reading backend headers\"}");
    return;
  }

  // Parse HTTP Status code
  String statusLine = backendClient.readStringUntil('\n');
  int statusCode = 200;
  if (statusLine.startsWith("HTTP/1.")) {
    statusCode = statusLine.substring(9, 12).toInt();
  }

  // Skip HTTP headers
  while (backendClient.connected()) {
    String line = backendClient.readStringUntil('\n');
    if (line == "\r" || line.length() == 0) break;
  }

  // Read response body safely into buffer
  String responseBody = "";
  while (backendClient.available() && ESP.getFreeHeap() > MIN_FREE_HEAP) {
    responseBody += (char)backendClient.read();
    yield();
  }
  backendClient.stop();

  sendCorsHeaders();
  server.send(statusCode, "application/json", responseBody);
}

// GET /queue
void handleProxyQueue() {
  proxyGetToBackend("/api/v2/queue");
}

// GET /models
void handleProxyModels() {
  proxyGetToBackend("/api/v2/models");
}

// GET /stats
void handleProxyStats() {
  proxyGetToBackend("/api/v2/stats");
}

// POST /chat -> Forward to FastAPI backend POST /api/v2/chat with Priority & User Headers
void handleChatForward() {
  if (!checkHeapSafety()) return;

  IPAddress clientIp = server.client().remoteIP();
  ClientUser* client = getOrRegisterClient(clientIp);
  totalProxyRequests++;

  if (!server.hasArg("plain")) {
    sendCorsHeaders();
    server.send(400, "application/json", "{\"error\":\"Request body is required\"}");
    return;
  }

  String requestBody = server.arg("plain");

  // Extract requested priority level (header takes precedence, then JSON payload, default: NORMAL)
  String priority = "normal";
  if (server.hasHeader("X-Priority")) {
    priority = server.header("X-Priority");
  } else {
    DynamicJsonDocument parseDoc(512);
    if (!deserializeJson(parseDoc, requestBody)) {
      if (parseDoc.containsKey("priority")) {
        priority = parseDoc["priority"].as<String>();
      }
    }
  }

  Serial.printf("[CHAT] Proxying request from %s (%s) | Priority: %s\n",
                client->userId, clientIp.toString().c_str(), priority.c_str());

  WiFiClient backendClient;
  backendClient.setTimeout(BACKEND_CONNECT_TIMEOUT_MS);

  if (!backendClient.connect(backendIP, backendPort)) {
    Serial.printf("[CHAT] Backend connection failed %s:%u\n", backendIP.toString().c_str(), backendPort);
    sendCorsHeaders();
    server.send(502, "application/json", "{\"error\":\"FastAPI backend unreachable\"}");
    return;
  }

  // Send forwarded POST request
  backendClient.print("POST /api/v2/chat HTTP/1.1\r\n");
  backendClient.printf("Host: %s:%u\r\n", backendIP.toString().c_str(), backendPort);
  backendClient.print("Content-Type: application/json\r\n");
  backendClient.printf("Content-Length: %u\r\n", requestBody.length());
  backendClient.printf("X-User-Id: %s\r\n", client->userId);
  backendClient.printf("X-Priority: %s\r\n", priority.c_str());
  backendClient.printf("X-Client-IP: %s\r\n", clientIp.toString().c_str());
  backendClient.print("Connection: close\r\n\r\n");
  backendClient.print(requestBody);

  // Read response
  unsigned long startWait = millis();
  while (!backendClient.available() && millis() - startWait < BACKEND_CONNECT_TIMEOUT_MS) {
    yield();
  }

  if (!backendClient.available()) {
    backendClient.stop();
    sendCorsHeaders();
    server.send(504, "application/json", "{\"error\":\"Backend response timeout\"}");
    return;
  }

  String statusLine = backendClient.readStringUntil('\n');
  int statusCode = 200;
  if (statusLine.startsWith("HTTP/1.")) {
    statusCode = statusLine.substring(9, 12).toInt();
  }

  // Skip HTTP response headers
  while (backendClient.connected()) {
    String line = backendClient.readStringUntil('\n');
    if (line == "\r" || line.length() == 0) break;
  }

  String backendResponse = "";
  while (backendClient.available() && ESP.getFreeHeap() > MIN_FREE_HEAP) {
    backendResponse += (char)backendClient.read();
    yield();
  }
  backendClient.stop();

  sendCorsHeaders();
  server.send(statusCode, "application/json", backendResponse);
}

// GET /stream/{request_id} -> Chunked Real-Time SSE/NDJSON Stream Proxy
void handleStreamRelay(String requestId) {
  if (!checkHeapSafety()) return;

  IPAddress clientIp = server.client().remoteIP();
  ClientUser* client = getOrRegisterClient(clientIp);
  totalStreamRequests++;

  Serial.printf("[STREAM] Initiating stream proxy for ReqId: %s (User %s)\n",
                requestId.c_str(), client ? client->userId : "U000");

  WiFiClient backendClient;
  backendClient.setTimeout(BACKEND_CONNECT_TIMEOUT_MS);

  if (!backendClient.connect(backendIP, backendPort)) {
    sendCorsHeaders();
    server.send(502, "application/json", "{\"error\":\"Failed to connect to backend stream\"}");
    return;
  }

  // Forward GET stream request
  String streamUri = "/api/v2/stream/" + requestId;
  backendClient.printf("GET %s HTTP/1.1\r\n", streamUri.c_str());
  backendClient.printf("Host: %s:%u\r\n", backendIP.toString().c_str(), backendPort);
  backendClient.printf("X-User-Id: %s\r\n", client ? client->userId : "U000");
  backendClient.printf("X-Client-IP: %s\r\n", clientIp.toString().c_str());
  backendClient.print("Accept: text/event-stream\r\n");
  backendClient.print("Connection: close\r\n\r\n");

  unsigned long startWait = millis();
  while (!backendClient.available() && millis() - startWait < BACKEND_CONNECT_TIMEOUT_MS) {
    yield();
  }

  if (!backendClient.available()) {
    backendClient.stop();
    sendCorsHeaders();
    server.send(504, "application/json", "{\"error\":\"Timeout waiting for backend stream\"}");
    return;
  }

  // Check backend response code
  String statusLine = backendClient.readStringUntil('\n');
  int statusCode = 200;
  if (statusLine.startsWith("HTTP/1.")) {
    statusCode = statusLine.substring(9, 12).toInt();
  }

  if (statusCode != 200) {
    backendClient.stop();
    sendCorsHeaders();
    server.send(statusCode, "application/json", "{\"error\":\"Upstream stream returned non-200 code\"}");
    return;
  }

  // Skip backend headers
  while (backendClient.connected()) {
    String line = backendClient.readStringUntil('\n');
    if (line == "\r" || line.length() == 0) break;
  }

  // Begin chunked SSE streaming to the client
  WiFiClient clientSocket = server.client();
  sendCorsHeaders();
  server.sendHeader("Content-Type", "text/event-stream");
  server.sendHeader("Cache-Control", "no-cache");
  server.sendHeader("Connection", "keep-alive");
  server.sendHeader("X-Accel-Buffering", "no");
  server.setContentLength(CONTENT_LENGTH_UNKNOWN);
  server.send(200, "text/event-stream", "");

  // Buffer for stream relaying
  uint8_t buffer[256];
  unsigned long streamStartTime = millis();

  while (backendClient.connected() || backendClient.available()) {
    // 1. Client Disconnect Safety Guard
    if (!clientSocket.connected()) {
      Serial.println(F("[STREAM] Client disconnected abruptly. Stopping stream relay."));
      break;
    }

    // 2. Heap Memory Safety Guard
    if (ESP.getFreeHeap() < MIN_FREE_HEAP) {
      Serial.println(F("[STREAM] Safety limit reached: Heap < 10KB. Aborting stream."));
      clientSocket.print("event: error\ndata: {\"error\": \"ESP8266 Memory Guard Throttled Stream\"}\n\n");
      break;
    }

    // 3. Stream Timeout Guard (Max 2 minutes)
    if (millis() - streamStartTime > STREAM_TIMEOUT_MS) {
      Serial.println(F("[STREAM] Stream reached max safety duration (120s). Terminating."));
      clientSocket.print("event: error\ndata: {\"error\": \"Stream duration exceeded limit\"}\n\n");
      break;
    }

    // 4. Relay available data chunks
    int availableBytes = backendClient.available();
    if (availableBytes > 0) {
      int bytesToRead = (availableBytes > (int)sizeof(buffer)) ? sizeof(buffer) : availableBytes;
      int bytesRead = backendClient.read(buffer, bytesToRead);
      if (bytesRead > 0) {
        clientSocket.write(buffer, bytesRead);
        clientSocket.flush();
      }
    } else {
      delay(5);  // Brief sleep to avoid 100% spinlock while waiting for next token
    }

    // 5. Watchdog reset
    yield();
  }

  backendClient.stop();
  clientSocket.stop();
  Serial.printf("[STREAM] Stream finished for ReqId: %s\n", requestId.c_str());
}

// Router for Dynamic URLs (e.g. /stream/{request_id}) and 404 Fallback
void handleDynamicRouting() {
  String uri = server.uri();

  // Route: GET /stream/<id>
  if (uri.startsWith("/stream/")) {
    String requestId = uri.substring(8);
    requestId.trim();
    if (requestId.length() > 0) {
      handleStreamRelay(requestId);
      return;
    }
  }

  // Fallback 404
  sendCorsHeaders();
  server.send(404, "application/json", "{\"error\":\"Route not found on CodeMesh v2.0 bridge\"}");
}

// =========================================================================================
//  SETUP & MAIN LOOP
// =========================================================================================

void setup() {
  Serial.begin(115200);
  delay(500);
  Serial.println();
  Serial.println(F("=================================================="));
  Serial.println(F("   CodeMesh v2.0 Multi-User Offline WiFi Bridge   "));
  Serial.println(F("=================================================="));

  // Initialize User Registry
  for (int i = 0; i < MAX_USERS; i++) {
    userRegistry[i].active = false;
    userRegistry[i].requestCount = 0;
    userRegistry[i].firstSeen = 0;
    userRegistry[i].lastSeen = 0;
  }

#if ENABLE_OLED
  setupOLED();
#endif

  // Configure SoftAP Network
  WiFi.mode(WIFI_AP);
  bool apSuccess = WiFi.softAP(AP_SSID, AP_PASS);
  if (apSuccess) {
    Serial.printf("[WIFI] Access Point '%s' initialized.\n", AP_SSID);
    Serial.printf("[WIFI] Gateway IP: %s\n", WiFi.softAPIP().toString().c_str());
  } else {
    Serial.println(F("[WIFI] Failed to start SoftAP!"));
  }

  // Register WebServer Endpoints
  server.on("/", HTTP_GET, handleRoot);
  server.on("/status", HTTP_GET, handleLocalStatus);
  server.on("/config", HTTP_POST, handleConfig);
  server.on("/chat", HTTP_POST, handleChatForward);
  server.on("/queue", HTTP_GET, handleProxyQueue);
  server.on("/models", HTTP_GET, handleProxyModels);
  server.on("/stats", HTTP_GET, handleProxyStats);

  // CORS OPTIONS handlers
  server.on("/", HTTP_OPTIONS, handleOptions);
  server.on("/status", HTTP_OPTIONS, handleOptions);
  server.on("/config", HTTP_OPTIONS, handleOptions);
  server.on("/chat", HTTP_OPTIONS, handleOptions);
  server.on("/queue", HTTP_OPTIONS, handleOptions);
  server.on("/models", HTTP_OPTIONS, handleOptions);
  server.on("/stats", HTTP_OPTIONS, handleOptions);

  // Dynamic router for /stream/{request_id} and 404
  server.onNotFound(handleDynamicRouting);

  // Collect custom headers
  const char* headerKeys[] = {"X-Priority", "X-User-Id", "Content-Type"};
  server.collectHeaders(headerKeys, 3);

  server.begin();
  Serial.println(F("[SERVER] HTTP Server listening on port 80"));
  Serial.printf("[SYSTEM] Initial Free Heap: %u bytes\n", ESP.getFreeHeap());
}

unsigned long lastPruneTime = 0;

void loop() {
  // Handle HTTP client requests
  server.handleClient();

  // Periodic user registry prune (every 30 seconds)
  unsigned long now = millis();
  if (now - lastPruneTime > 30000) {
    lastPruneTime = now;
    pruneStaleClients();
  }

#if ENABLE_OLED
  updateOLED();
#endif

  yield();
}
