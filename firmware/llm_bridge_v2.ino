#include <ESP8266WiFi.h>
#include <ESP8266WebServer.h>
#include <ESP8266HTTPClient.h>
#include <WiFiClient.h>
#include <ArduinoJson.h>

const char* ssid = "LLM-Bridge";
const char* pass = "llm12345";

String laptopIP = "192.168.4.2";
String ollamaModel = "llama3";
const int ollamaPort = 11434;
const int MIN_HEAP_THRESHOLD = 10240;

ESP8266WebServer server(80);

void sendCorsHeaders() {
    server.sendHeader("Access-Control-Allow-Origin", "*");
    server.sendHeader("Access-Control-Allow-Methods", "POST, GET, OPTIONS");
    server.sendHeader("Access-Control-Allow-Headers", "Content-Type");
}

bool checkHeap() {
    if (ESP.getFreeHeap() < MIN_HEAP_THRESHOLD) {
        sendCorsHeaders();
        server.send(503, "application/json", "{\"error\":\"insufficient memory\"}");
        return false;
    }
    return true;
}

void handleOptions() {
    sendCorsHeaders();
    server.send(204);
}

void handleChat() {
    if (server.method() == HTTP_OPTIONS) {
        handleOptions();
        return;
    }
    if (!checkHeap()) return;

    if (!server.hasArg("plain")) {
        sendCorsHeaders();
        server.send(400, "application/json", "{\"error\":\"missing body\"}");
        return;
    }

    String payload = server.arg("plain");
    
    // Inject "stream": true
    DynamicJsonDocument doc(payload.length() + 256);
    DeserializationError error = deserializeJson(doc, payload);
    if (error) {
        sendCorsHeaders();
        server.send(400, "application/json", "{\"error\":\"invalid json\"}");
        return;
    }
    
    doc["stream"] = true;
    if (!doc.containsKey("model")) {
        doc["model"] = ollamaModel;
    }
    
    String outPayload;
    serializeJson(doc, outPayload);

    WiFiClient client;
    client.setTimeout(180000);
    if (!client.connect(laptopIP.c_str(), ollamaPort)) {
        sendCorsHeaders();
        server.send(502, "application/x-ndjson", "{\"error\":\"cannot reach ollama\",\"done\":true}\n");
        return;
    }

    client.print("POST /api/chat HTTP/1.1\r\n");
    client.print("Host: " + laptopIP + ":" + String(ollamaPort) + "\r\n");
    client.print("Content-Type: application/json\r\n");
    client.print("Content-Length: " + String(outPayload.length()) + "\r\n");
    client.print("\r\n");
    client.print(outPayload);

    // Skip HTTP headers
    while (client.connected()) {
        String line = client.readStringUntil('\n');
        if (line == "\r") {
            break;
        }
    }

    sendCorsHeaders();
    server.setContentLength(CONTENT_LENGTH_UNKNOWN);
    server.sendHeader("Content-Type", "application/x-ndjson");
    server.send(200);

    while (client.connected() || client.available()) {
        if (!server.client().connected()) {
            client.stop();
            break;
        }
        if (client.available()) {
            String line = client.readStringUntil('\n');
            if (line.length() > 0) {
                server.sendContent(line + "\n");
                yield();
            }
        }
        yield();
    }
    
    server.sendContent("");
    client.stop();
}

void handleStatus() {
    if (server.method() == HTTP_OPTIONS) {
        handleOptions();
        return;
    }
    
    DynamicJsonDocument doc(256);
    doc["model"] = ollamaModel;
    doc["ip"] = laptopIP;
    doc["free_mem"] = ESP.getFreeHeap();
    
    String response;
    serializeJson(doc, response);
    
    sendCorsHeaders();
    server.send(200, "application/json", response);
}

void handleConfig() {
    if (server.method() == HTTP_OPTIONS) {
        handleOptions();
        return;
    }
    
    if (server.hasArg("ip")) {
        laptopIP = server.arg("ip");
    }
    if (server.hasArg("model")) {
        ollamaModel = server.arg("model");
    }
    
    sendCorsHeaders();
    server.send(200, "text/plain", "OK");
}

void handleModels() {
    if (server.method() == HTTP_OPTIONS) {
        handleOptions();
        return;
    }
    
    WiFiClient client;
    HTTPClient http;
    
    String url = "http://" + laptopIP + ":" + String(ollamaPort) + "/api/tags";
    if (http.begin(client, url)) {
        int httpCode = http.GET();
        if (httpCode > 0) {
            if (httpCode == HTTP_CODE_OK) {
                String payload = http.getString();
                sendCorsHeaders();
                server.send(200, "application/json", payload);
            } else {
                sendCorsHeaders();
                server.send(502, "application/json", "{\"models\":[],\"error\":\"Ollama error\"}");
            }
        } else {
            sendCorsHeaders();
            server.send(502, "application/json", "{\"models\":[],\"error\":\"Cannot reach Ollama\"}");
        }
        http.end();
    } else {
        sendCorsHeaders();
        server.send(502, "application/json", "{\"models\":[],\"error\":\"Cannot reach Ollama\"}");
    }
}

void setup() {
    Serial.begin(115200);
    WiFi.mode(WIFI_AP);
    WiFi.softAP(ssid, pass);
    
    Serial.println("");
    Serial.print("AP IP address: ");
    Serial.println(WiFi.softAPIP());
    Serial.print("Free heap: ");
    Serial.println(ESP.getFreeHeap());

    server.on("/chat", HTTP_POST, handleChat);
    server.on("/chat", HTTP_OPTIONS, handleOptions);
    server.on("/status", HTTP_GET, handleStatus);
    server.on("/status", HTTP_OPTIONS, handleOptions);
    server.on("/config", HTTP_POST, handleConfig);
    server.on("/config", HTTP_OPTIONS, handleOptions);
    server.on("/models", HTTP_GET, handleModels);
    server.on("/models", HTTP_OPTIONS, handleOptions);
    
    server.begin();
    Serial.println("HTTP server started");
}

void loop() {
    server.handleClient();
    yield();
}
