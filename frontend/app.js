/**
 * CodeMesh v2.0 - High Performance Offline Mesh Frontend
 * Queue visualization, real-time SSE token streaming, and scheduler telemetry.
 */

class CodeMeshApp {
    constructor() {
        this.currentRequestId = null;
        this.eventSource = null;
        this.queuePollInterval = null;
        this.selectedPriority = 'normal';
        this.selectedModel = 'codellama-7b';
        this.isGenerating = false;
        this.userQueuePos = 0;
        
        // Active stream DOM elements
        this.currentStreamBubble = null;
        this.currentStreamContentEl = null;
        this.currentCursorEl = null;
        this.tokenBuffer = '';
        this.tokenCount = 0;
        this.streamStartTime = null;

        // Settings (persisted to localStorage)
        this.settings = this.loadSettings();

        // UI Element References
        this.dom = {
            chatForm: document.getElementById('chatForm'),
            chatInput: document.getElementById('chatInput'),
            sendBtn: document.getElementById('sendBtn'),
            chatContainer: document.getElementById('chatMessagesContainer'),
            modelSelect: document.getElementById('modelSelect'),
            priorityButtons: document.querySelectorAll('.priority-btn'),
            composerPriorityPill: document.getElementById('composerPriorityPill'),
            clearChatBtn: document.getElementById('clearChatBtn'),
            
            // Queue & Dashboard Elements
            queueStatusCard: document.getElementById('queueStatusCard'),
            queueStateBadge: document.getElementById('queueStateBadge'),
            queuePosNumber: document.getElementById('queuePosNumber'),
            queuePosTotal: document.getElementById('queuePosTotal'),
            queueProgressBar: document.getElementById('queueProgressBar'),
            estWaitTime: document.getElementById('estWaitTime'),
            activeModelBadge: document.getElementById('activeModelBadge'),
            activeUsersCount: document.getElementById('activeUsersCount'),
            meshDotsContainer: document.getElementById('meshDotsContainer'),
            
            // Stats Elements
            statServed: document.getElementById('statServed'),
            statAvgWait: document.getElementById('statAvgWait'),
            statTokensPerSec: document.getElementById('statTokensPerSec'),
            statPreemptions: document.getElementById('statPreemptions'),
            queueDepthLabel: document.getElementById('queueDepthLabel'),
            queueDepthFill: document.getElementById('queueDepthFill'),
            dashboardCard: document.getElementById('schedulerStatsCard'),
            dashboardToggleBtn: document.getElementById('dashboardToggleBtn'),
            
            // Status & Banners
            backendStatusBadge: document.getElementById('backendStatusBadge'),
            backendStatusText: document.getElementById('backendStatusText'),
            latencyText: document.getElementById('latencyText'),
            liveActionBanner: document.getElementById('liveActionBanner'),
            actionStatusText: document.getElementById('actionStatusText'),
            abortRequestBtn: document.getElementById('abortRequestBtn'),
            
            // Settings Modal
            openSettingsBtn: document.getElementById('openSettingsBtn'),
            closeSettingsBtn: document.getElementById('closeSettingsBtn'),
            cancelSettingsBtn: document.getElementById('cancelSettingsBtn'),
            saveSettingsBtn: document.getElementById('saveSettingsBtn'),
            settingsModal: document.getElementById('settingsModal'),
            settingHost: document.getElementById('settingHost'),
            settingPort: document.getElementById('settingPort'),
            settingProtocol: document.getElementById('settingProtocol'),
            settingMockFallback: document.getElementById('settingMockFallback'),
            testConnectionBtn: document.getElementById('testConnectionBtn'),
            testConnectionResult: document.getElementById('testConnectionResult'),
            openStatsBtn: document.getElementById('openStatsBtn'),
            toastContainer: document.getElementById('toastContainer')
        };

        this.init();
    }

    /**
     * Initialize app state, event listeners, and start queue polling
     */
    init() {
        this.bindEvents();
        this.populateSettingsForm();
        this.startQueuePolling();
        this.testBackendHealth(true);
    }

    /**
     * Load settings from localStorage with default fallbacks
     */
    loadSettings() {
        const defaults = {
            host: window.location.hostname || 'localhost',
            port: window.location.port || '8000',
            protocol: window.location.protocol || 'http:',
            mockFallback: true
        };
        try {
            const saved = localStorage.getItem('codemesh_settings');
            if (saved) {
                return { ...defaults, ...JSON.parse(saved) };
            }
        } catch (e) {
            console.warn('Failed to read settings from localStorage', e);
        }
        return defaults;
    }

    saveSettings() {
        try {
            localStorage.setItem('codemesh_settings', JSON.stringify(this.settings));
            this.showToast('Settings saved successfully', 'success');
        } catch (e) {
            console.error('Failed to save settings', e);
        }
    }

    getBaseApiUrl() {
        const portPart = this.settings.port ? `:${this.settings.port}` : '';
        return `${this.settings.protocol}//${this.settings.host}${portPart}`;
    }

    /**
     * Wire DOM events
     */
    bindEvents() {
        // Form submission
        this.dom.chatForm.addEventListener('submit', (e) => {
            e.preventDefault();
            this.handleSend();
        });

        // Textarea enter key (Shift+Enter for newline)
        this.dom.chatInput.addEventListener('keydown', (e) => {
            if (e.key === 'Enter' && !e.shiftKey) {
                e.preventDefault();
                this.handleSend();
            }
        });

        // Priority selector buttons
        this.dom.priorityButtons.forEach(btn => {
            btn.addEventListener('click', () => {
                this.dom.priorityButtons.forEach(b => b.classList.remove('active'));
                btn.classList.add('active');
                this.selectedPriority = btn.dataset.priority;
                this.dom.composerPriorityPill.textContent = `${this.selectedPriority.toUpperCase()} Priority`;
                
                // Colorize composer pill
                if (this.selectedPriority === 'critical') {
                    this.dom.composerPriorityPill.style.color = 'var(--red-accent)';
                    this.dom.composerPriorityPill.style.borderColor = 'rgba(255, 77, 109, 0.4)';
                    this.dom.composerPriorityPill.style.background = 'rgba(255, 77, 109, 0.15)';
                } else if (this.selectedPriority === 'high') {
                    this.dom.composerPriorityPill.style.color = 'var(--amber-accent)';
                    this.dom.composerPriorityPill.style.borderColor = 'rgba(255, 183, 3, 0.4)';
                    this.dom.composerPriorityPill.style.background = 'rgba(255, 183, 3, 0.15)';
                } else {
                    this.dom.composerPriorityPill.style.color = 'var(--teal-accent)';
                    this.dom.composerPriorityPill.style.borderColor = 'rgba(0, 212, 255, 0.25)';
                    this.dom.composerPriorityPill.style.background = 'rgba(0, 212, 255, 0.12)';
                }
            });
        });

        // Model selector change
        this.dom.modelSelect.addEventListener('change', (e) => {
            this.selectedModel = e.target.value;
            const modelName = e.target.options[e.target.selectedIndex].text.split('(')[0].trim();
            this.dom.activeModelBadge.textContent = modelName;
        });

        // Clear chat
        this.dom.clearChatBtn.addEventListener('click', () => {
            if (confirm('Clear all conversation messages?')) {
                this.dom.chatContainer.innerHTML = '';
                this.showToast('Chat history cleared', 'info');
            }
        });

        // Quick Prompt Chips
        document.querySelectorAll('.chip-btn').forEach(chip => {
            chip.addEventListener('click', () => {
                const prompt = chip.dataset.prompt;
                this.dom.chatInput.value = prompt + ' ';
                this.dom.chatInput.focus();
            });
        });

        // Scheduler Dashboard collapse/expand
        this.dom.dashboardToggleBtn.addEventListener('click', () => {
            this.dom.dashboardCard.classList.toggle('collapsed');
        });

        // Top nav Telemetry button scrolls & focuses dashboard
        this.dom.openStatsBtn.addEventListener('click', () => {
            this.dom.dashboardCard.classList.remove('collapsed');
            this.dom.dashboardCard.scrollIntoView({ behavior: 'smooth' });
        });

        // Abort / Cancel request
        this.dom.abortRequestBtn.addEventListener('click', () => {
            this.abortCurrentRequest();
        });

        // Settings Modal controls
        this.dom.openSettingsBtn.addEventListener('click', () => {
            this.openSettingsModal();
        });
        this.dom.closeSettingsBtn.addEventListener('click', () => {
            this.closeSettingsModal();
        });
        this.dom.cancelSettingsBtn.addEventListener('click', () => {
            this.closeSettingsModal();
        });
        this.dom.saveSettingsBtn.addEventListener('click', () => {
            this.saveSettingsFromModal();
        });
        this.dom.testConnectionBtn.addEventListener('click', () => {
            this.testBackendHealth(false);
        });

        // Close modal on outside click
        this.dom.settingsModal.addEventListener('click', (e) => {
            if (e.target === this.dom.settingsModal) {
                this.closeSettingsModal();
            }
        });
    }

    populateSettingsForm() {
        this.dom.settingHost.value = this.settings.host;
        this.dom.settingPort.value = this.settings.port;
        this.dom.settingProtocol.value = this.settings.protocol;
        this.dom.settingMockFallback.checked = this.settings.mockFallback;
    }

    openSettingsModal() {
        this.populateSettingsForm();
        this.dom.testConnectionResult.textContent = '';
        this.dom.settingsModal.classList.remove('hidden');
    }

    closeSettingsModal() {
        this.dom.settingsModal.classList.add('hidden');
    }

    saveSettingsFromModal() {
        this.settings.host = this.dom.settingHost.value.trim() || 'localhost';
        this.settings.port = this.dom.settingPort.value.trim();
        this.settings.protocol = this.dom.settingProtocol.value;
        this.settings.mockFallback = this.dom.settingMockFallback.checked;
        this.saveSettings();
        this.closeSettingsModal();
        this.testBackendHealth(true);
    }

    /**
     * Test connection to FastAPI backend
     */
    async testBackendHealth(silent = false) {
        const start = performance.now();
        const base = this.getBaseApiUrl();
        const url = `${base}/health`;
        
        try {
            const resp = await fetch(url, { method: 'GET', signal: AbortSignal.timeout(3500) });
            const roundTrip = Math.round(performance.now() - start);
            
            if (resp.ok) {
                this.dom.backendStatusText.textContent = 'Online';
                this.dom.backendStatusText.style.color = 'var(--green-accent)';
                this.dom.backendStatusBadge.querySelector('.status-dot').className = 'status-dot green';
                this.dom.latencyText.textContent = `${roundTrip}ms`;
                if (!silent) {
                    this.dom.testConnectionResult.textContent = `Connected! Latency: ${roundTrip}ms`;
                    this.dom.testConnectionResult.style.color = 'var(--green-accent)';
                }
                return true;
            } else {
                throw new Error(`HTTP ${resp.status}`);
            }
        } catch (err) {
            this.dom.backendStatusText.textContent = this.settings.mockFallback ? 'Mock Mode' : 'Offline';
            this.dom.backendStatusText.style.color = this.settings.mockFallback ? 'var(--teal-accent)' : 'var(--red-accent)';
            this.dom.backendStatusBadge.querySelector('.status-dot').className = this.settings.mockFallback ? 'status-dot teal' : 'status-dot red';
            this.dom.latencyText.textContent = '--';
            if (!silent) {
                this.dom.testConnectionResult.textContent = `Failed: ${err.message}. Using fallback.`;
                this.dom.testConnectionResult.style.color = 'var(--amber-accent)';
            }
            return false;
        }
    }

    /**
     * Handle Send click or Enter trigger
     */
    handleSend() {
        if (this.isGenerating) return;
        const text = this.dom.chatInput.value.trim();
        if (!text) return;

        this.dom.chatInput.value = '';
        this.appendUserMessage(text);
        this.sendMessage(text, this.selectedPriority, this.selectedModel);
    }

    /**
     * 1. POST to /api/v2/chat or /chat
     * 2. Get request_id back
     * 3. Open SSE connection to /api/v2/stream/{request_id}
     * 4. Stream tokens character by character with a blinking cursor
     * 5. Show queue position while waiting
     */
    async sendMessage(text, priority = 'normal', model = 'auto') {
        this.isGenerating = true;
        this.dom.sendBtn.disabled = true;
        this.tokenBuffer = '';
        this.tokenCount = 0;
        this.streamStartTime = performance.now();

        // Show Queue Status Card as active
        this.dom.queueStateBadge.textContent = 'Queueing';
        this.dom.queueStateBadge.className = 'active-badge active';
        this.dom.liveActionBanner.classList.remove('hidden');
        this.dom.actionStatusText.textContent = `Dispatching request (${priority.toUpperCase()} priority)...`;

        // Create the AI Response message bubble in "Thinking / Queueing" state
        const aiMessageObj = this.appendAIMessagePlaceholder(model);
        this.currentStreamBubble = aiMessageObj.bubble;
        this.currentStreamContentEl = aiMessageObj.contentEl;

        const base = this.getBaseApiUrl();
        const payload = {
            prompt: text,
            priority: priority,
            model: model,
            client_id: 'browser-' + Math.random().toString(36).substring(2, 7)
        };

        try {
            const resp = await fetch(`${base}/api/v2/chat`, {
                method: 'POST',
                headers: { 'Content-Type': 'application/json' },
                body: JSON.stringify(payload),
                signal: AbortSignal.timeout(8000)
            });

            if (!resp.ok) {
                throw new Error(`Server returned HTTP ${resp.status}`);
            }

            const data = await resp.json();
            const requestId = data.request_id || data.id;
            this.currentRequestId = requestId;

            // Update queue position from response if available
            if (data.queue_position !== undefined) {
                this.setQueuePosition(data.queue_position, data.estimated_wait_seconds);
            }

            // Connect to real-time SSE stream
            this.connectToStream(requestId);

        } catch (err) {
            console.warn('Real backend call failed, checking fallback:', err);
            if (this.settings.mockFallback) {
                // Run full realistic simulation for demo / offline experience
                this.runMockStreamingSimulation(text, priority, model);
            } else {
                this.handleStreamError(`Failed to schedule request: ${err.message}. Check backend connection in settings.`);
            }
        }
    }

    /**
     * Connect to SSE stream: /api/v2/stream/{request_id}
     */
    connectToStream(requestId) {
        const base = this.getBaseApiUrl();
        const streamUrl = `${base}/api/v2/stream/${requestId}`;
        
        if (this.eventSource) {
            this.eventSource.close();
        }

        this.eventSource = new EventSource(streamUrl);

        // Queue update events while waiting
        this.eventSource.addEventListener('queue_update', (e) => {
            try {
                const data = JSON.parse(e.data);
                this.setQueuePosition(data.position, data.estimated_wait);
            } catch (err) {
                console.error('Error parsing queue_update event', err);
            }
        });

        // Token stream events
        this.eventSource.addEventListener('token', (e) => {
            this.onStreamToken(e.data);
        });

        // Fallback standard message event
        this.eventSource.onmessage = (e) => {
            if (e.data === '[DONE]') {
                this.onStreamDone();
            } else {
                this.onStreamToken(e.data);
            }
        };

        // Done event
        this.eventSource.addEventListener('done', (e) => {
            this.onStreamDone(e.data);
        });

        // Preempted event
        this.eventSource.addEventListener('preempted', (e) => {
            this.dom.actionStatusText.textContent = 'Request temporarily paused by high priority task...';
            this.showToast('Request pre-empted by critical mesh task', 'info');
        });

        // Error event
        this.eventSource.addEventListener('error', (e) => {
            console.error('SSE Stream error:', e);
            if (this.isGenerating && this.tokenCount === 0) {
                this.handleStreamError('Connection to inference stream lost or timed out.');
            } else {
                this.onStreamDone();
            }
        });
    }

    /**
     * Handle incoming streaming token
     */
    onStreamToken(tokenChunk) {
        if (!tokenChunk) return;

        // Transition from Queueing to Processing if needed
        if (this.dom.queueStateBadge.textContent !== 'Processing') {
            this.dom.queueStateBadge.textContent = 'Processing';
            this.dom.queueStateBadge.className = 'active-badge processing';
            this.setQueuePosition(0, 0);
            this.dom.actionStatusText.textContent = 'Generating real-time response tokens...';
            // Remove typing bounce dots if present
            const typingEl = this.currentStreamContentEl.querySelector('.typing-indicator-box');
            if (typingEl) typingEl.remove();

            // Insert cursor
            this.ensureCursor();
        }

        this.tokenBuffer += tokenChunk;
        this.tokenCount++;

        // Render buffer with formatted markdown / code blocks and cursor
        this.renderStreamBuffer();
    }

    /**
     * Complete streaming turn
     */
    onStreamDone(finalData = null) {
        if (this.eventSource) {
            this.eventSource.close();
            this.eventSource = null;
        }

        // Remove cursor
        if (this.currentCursorEl) {
            this.currentCursorEl.remove();
            this.currentCursorEl = null;
        }

        // Final rich markdown rendering with copy code buttons
        this.currentStreamContentEl.innerHTML = this.formatMarkdown(this.tokenBuffer);
        this.attachCodeBlockCopyButtons(this.currentStreamContentEl);

        // Update stats
        const durationSec = Math.max(0.1, (performance.now() - this.streamStartTime) / 1000);
        const tps = (this.tokenCount / durationSec).toFixed(1);
        
        // Update bubble header with generation metrics
        const metaEl = this.currentStreamBubble.querySelector('.message-meta');
        if (metaEl) {
            metaEl.textContent = `${this.tokenCount} tokens • ${tps} t/s • ${(durationSec).toFixed(1)}s`;
        }

        // Reset App State
        this.isGenerating = false;
        this.dom.sendBtn.disabled = false;
        this.dom.liveActionBanner.classList.add('hidden');
        this.dom.queueStateBadge.textContent = 'Idle';
        this.dom.queueStateBadge.className = 'active-badge';
        this.setQueuePosition(0, 0);

        this.scrollToBottom();
    }

    handleStreamError(errMsg) {
        if (this.eventSource) {
            this.eventSource.close();
            this.eventSource = null;
        }

        if (this.currentStreamContentEl) {
            this.currentStreamContentEl.innerHTML = `<p style="color: var(--red-accent);">⚠️ <strong>Error:</strong> ${errMsg}</p>`;
        }

        this.isGenerating = false;
        this.dom.sendBtn.disabled = false;
        this.dom.liveActionBanner.classList.add('hidden');
        this.dom.queueStateBadge.textContent = 'Idle';
        this.dom.queueStateBadge.className = 'active-badge';
        this.setQueuePosition(0, 0);
        this.showToast(errMsg, 'error');
    }

    abortCurrentRequest() {
        if (!this.isGenerating) return;
        if (this.eventSource) {
            this.eventSource.close();
            this.eventSource = null;
        }
        if (this.currentCursorEl) this.currentCursorEl.remove();
        if (this.currentStreamContentEl) {
            this.currentStreamContentEl.innerHTML += `<p style="color: var(--amber-accent); font-size: 0.8rem; margin-top: 6px;">[Generation cancelled by user]</p>`;
        }
        this.isGenerating = false;
        this.dom.sendBtn.disabled = false;
        this.dom.liveActionBanner.classList.add('hidden');
        this.dom.queueStateBadge.textContent = 'Idle';
        this.dom.queueStateBadge.className = 'active-badge';
        this.setQueuePosition(0, 0);
        this.showToast('Request cancelled', 'info');
    }

    /**
     * Ensure blinking cursor is positioned at the end of the streaming text
     */
    ensureCursor() {
        if (!this.currentCursorEl) {
            this.currentCursorEl = document.createElement('span');
            this.currentCursorEl.className = 'streaming-cursor';
            this.currentStreamContentEl.appendChild(this.currentCursorEl);
        }
    }

    renderStreamBuffer() {
        // Quick formatting preserving code blocks while streaming
        this.currentStreamContentEl.innerHTML = this.formatMarkdown(this.tokenBuffer);
        this.ensureCursor();
        this.scrollToBottom();
    }

    /**
     * Start background polling for queue state and scheduler telemetry
     */
    startQueuePolling() {
        if (this.queuePollInterval) {
            clearInterval(this.queuePollInterval);
        }

        // Poll every 2 seconds as specified
        this.queuePollInterval = setInterval(async () => {
            await this.pollQueueAndStats();
        }, 2000);

        // Run immediate initial check
        this.pollQueueAndStats();
    }

    async pollQueueAndStats() {
        const base = this.getBaseApiUrl();
        try {
            const [queueRes, statsRes] = await Promise.allSettled([
                fetch(`${base}/api/v2/queue`, { signal: AbortSignal.timeout(1800) }),
                fetch(`${base}/api/v2/stats`, { signal: AbortSignal.timeout(1800) })
            ]);

            if (queueRes.status === 'fulfilled' && queueRes.value.ok) {
                const queueData = await queueRes.value.json();
                this.updateQueueDisplay(queueData);
            } else if (this.settings.mockFallback && !this.isGenerating) {
                // Simulate periodic realistic mesh peer changes
                this.simulateMeshPeers();
            }

            if (statsRes.status === 'fulfilled' && statsRes.value.ok) {
                const statsData = await statsRes.value.json();
                this.updateStats(statsData);
            }
        } catch (e) {
            // Backend offline, fallback handles display gracefully
        }
    }

    /**
     * Update Queue Visualization UI
     */
    updateQueueDisplay(data) {
        // Expected data schema: { active_users: 5, queue_depth: 3, user_position: #, wait_time: 2.1, peers: [...] }
        const activeCount = data.active_users || (data.queue ? data.queue.length : 1);
        this.dom.activeUsersCount.textContent = `${activeCount} Active`;

        if (!this.isGenerating) {
            const depth = data.queue_depth !== undefined ? data.queue_depth : 0;
            this.dom.queueDepthLabel.textContent = `${depth} / 16 Max`;
            const fillPct = Math.min(100, Math.round((depth / 16) * 100));
            this.dom.queueDepthFill.style.width = `${fillPct}%`;
        }

        // Render Mesh Peer Dots
        if (data.peers && Array.isArray(data.peers)) {
            this.renderMeshDots(data.peers);
        }
    }

    /**
     * Update Scheduler Stats Dashboard
     */
    updateStats(data) {
        // Expected data schema: { total_served: 142, avg_wait_time: 1.8, tokens_per_sec: 38.4, preemption_count: 0 }
        if (data.total_served !== undefined) this.dom.statServed.textContent = data.total_served;
        if (data.avg_wait_time !== undefined) this.dom.statAvgWait.textContent = `${Number(data.avg_wait_time).toFixed(1)}s`;
        if (data.tokens_per_sec !== undefined) this.dom.statTokensPerSec.textContent = `${Number(data.tokens_per_sec).toFixed(1)} t/s`;
        if (data.preemption_count !== undefined) this.dom.statPreemptions.textContent = data.preemption_count;
    }

    /**
     * Update position progress bar and indicators
     */
    setQueuePosition(position, estWaitSeconds = 0) {
        this.userQueuePos = position;
        this.dom.queuePosNumber.textContent = position;

        if (position <= 0) {
            this.dom.queuePosTotal.textContent = this.isGenerating ? 'active' : 'idle';
            this.dom.queueProgressBar.style.width = this.isGenerating ? '100%' : '0%';
            this.dom.estWaitTime.textContent = '0s';
            if (this.isGenerating) {
                this.dom.actionStatusText.textContent = 'Generating tokens...';
            }
        } else {
            this.dom.queuePosTotal.textContent = 'in line';
            this.dom.estWaitTime.textContent = `${Math.round(estWaitSeconds || position * 1.5)}s`;
            // Visual progress: #1 is 75%, #2 is 50%, #3 is 25%
            const pct = Math.max(10, Math.min(90, Math.round(100 - (position * 20))));
            this.dom.queueProgressBar.style.width = `${pct}%`;
            this.dom.actionStatusText.textContent = `Queue Position: #${position} (Est. ${this.dom.estWaitTime.textContent})...`;
        }
    }

    /**
     * Render the mesh users visual dot matrix
     */
    renderMeshDots(peers) {
        this.dom.meshDotsContainer.innerHTML = '';
        peers.forEach(peer => {
            const dotItem = document.createElement('div');
            dotItem.className = `mesh-dot-item ${peer.isYou ? 'current-user' : ''}`;
            dotItem.title = `${peer.name || peer.id} (${peer.priority || 'normal'})`;

            const colorClass = peer.priority === 'critical' ? 'red' : (peer.priority === 'high' ? 'amber' : 'blue');
            const pulseClass = peer.active ? 'pulse' : '';

            dotItem.innerHTML = `
                <span class="dot-indicator ${colorClass} ${pulseClass}"></span>
                <span class="dot-label">${peer.isYou ? 'You' : (peer.name || 'Peer')}</span>
            `;
            this.dom.meshDotsContainer.appendChild(dotItem);
        });
    }

    simulateMeshPeers() {
        const mockPeers = [
            { id: 'usr-1', name: 'You', isYou: true, priority: this.selectedPriority, active: this.isGenerating },
            { id: 'usr-2', name: 'ESP-Node1', isYou: false, priority: 'normal', active: false },
            { id: 'usr-3', name: 'Dev-Laptop', isYou: false, priority: 'high', active: true },
            { id: 'usr-4', name: 'IoT-Client', isYou: false, priority: 'normal', active: false }
        ];
        this.renderMeshDots(mockPeers);
    }

    /**
     * Full Realistic Simulation Fallback for offline testing / demo
     */
    runMockStreamingSimulation(userPrompt, priority, model) {
        // Step 1: Simulate Queue Wait
        let queuePos = priority === 'critical' ? 1 : (priority === 'high' ? 2 : 3);
        const estWait = queuePos * 1.5;
        this.setQueuePosition(queuePos, estWait);

        const queueTimer = setInterval(() => {
            queuePos--;
            if (queuePos > 0) {
                this.setQueuePosition(queuePos, queuePos * 1.5);
            } else {
                clearInterval(queueTimer);
                // Step 2: Stream tokens
                this.executeMockTokenStream(userPrompt, model);
            }
        }, 1200);
    }

    executeMockTokenStream(userPrompt, model) {
        // Pre-composed intelligent code copilot responses based on prompt keywords
        let mockResponse = "";
        const lower = userPrompt.toLowerCase();

        if (lower.includes('audit') || lower.includes('security') || lower.includes('vulnerability')) {
            mockResponse = `### 🛡️ Security Audit Report

I analyzed your code snippet for memory safety and concurrency race conditions:

\`\`\`python
# Optimized & Thread-Safe Implementation
import threading
from typing import Optional

class SecureMeshBuffer:
    def __init__(self, capacity: int = 1024):
        self._lock = threading.Lock()
        self._buffer = bytearray(capacity)
        self._pointer = 0

    def append_safe(self, data: bytes) -> bool:
        with self._lock:  # Eliminates race conditions
            if self._pointer + len(data) > len(self._buffer):
                return False  # Bounds checked buffer overflow protection
            self._buffer[self._pointer : self._pointer + len(data)] = data
            self._pointer += len(data)
            return True
\`\`\`

**Key Findings:**
1. **Thread Contention:** Added mutual exclusion lock to guarantee atomic byte copies across ESP8266 mesh workers.
2. **Buffer Bounds:** Verified memory boundary conditions to prevent heap corruption on embedded targets.`;
        } else if (lower.includes('test') || lower.includes('unit test')) {
            mockResponse = `### 🧪 Automated Unit Test Suite

Here is a comprehensive test suite covering edge cases and zero-length boundaries:

\`\`\`typescript
import { describe, it, expect } from 'vitest';

describe('MeshPacketRouter', () => {
    it('should drop malformed CRC packets gracefully', () => {
        const router = new MeshPacketRouter();
        const corruptedPacket = new Uint8Array([0xFF, 0x00, 0xAA]);
        expect(router.validatePacket(corruptedPacket)).toBe(false);
    });

    it('should prioritize critical preemption headers', () => {
        const queue = new PriorityQueue();
        queue.push({ id: 1, priority: 'normal' });
        queue.push({ id: 2, priority: 'critical' });
        expect(queue.pop().id).toBe(2);
    });
});
\`\`\`

All test cases are self-contained and run zero-dependency.`;
        } else {
            mockResponse = `### ⚡ CodeMesh v2.0 Engine Response

Here is the clean, production-grade implementation for: **"${userPrompt}"**:

\`\`\`python
import time
from collections import deque

class FastMeshQueue:
    """Zero-allocation Ring Buffer for Air-Gapped LLM Queuing."""
    def __init__(self, maxsize: int = 64):
        self.queue = deque(maxlen=maxsize)
        
    def enqueue(self, item: dict, priority: str = "normal") -> int:
        if priority == "critical":
            self.queue.appendleft(item)
            return 1
        self.queue.append(item)
        return len(self.queue)

# Initialized for offline inference
mesh = FastMeshQueue()
print(f"Mesh Initialized with model {model} (TPS: 42.1 t/s)")
\`\`\`

**Performance Highlights:**
- **Zero Internet Dependence**: Processed locally on node.
- **Latency**: Sub-25ms dispatch latency.
- **Preemption Ready**: Critical tasks jump to the front of the queue immediately.`;
        }

        // Stream tokens character by character / token chunks
        const tokens = mockResponse.match(/.{1,4}/g) || [mockResponse];
        let index = 0;

        const streamInterval = setInterval(() => {
            if (index < tokens.length) {
                this.onStreamToken(tokens[index]);
                index++;
            } else {
                clearInterval(streamInterval);
                this.onStreamDone();
            }
        }, 22);
    }

    /**
     * Markdown and Code Block Formatter
     */
    formatMarkdown(text) {
        if (!text) return '';
        
        let html = text;

        // Escape dangerous HTML but keep markdown structure
        html = html
            .replace(/&/g, '&amp;')
            .replace(/</g, '&lt;')
            .replace(/>/g, '&gt;');

        // Fenced code blocks ```language ... ```
        html = html.replace(/```([a-zA-Z0-9_\-\+]*)\n([\s\S]*?)```/g, (match, lang, code) => {
            const language = lang || 'code';
            return `
                <div class="code-block-container">
                    <div class="code-block-header">
                        <span>${language.toUpperCase()}</span>
                        <button class="copy-code-btn" type="button">Copy</button>
                    </div>
                    <pre><code class="language-${language}">${code.trim()}</code></pre>
                </div>
            `;
        });

        // Inline code `code`
        html = html.replace(/`([^`]+)`/g, '<code>$1</code>');

        // Headers
        html = html.replace(/^### (.*$)/gim, '<h4 style="color: var(--teal-accent); margin: 8px 0 4px 0; font-size: 0.95rem;">$1</h4>');
        html = html.replace(/^## (.*$)/gim, '<h3 style="color: #ffffff; margin: 10px 0 6px 0; font-size: 1.05rem;">$1</h3>');

        // Bold & Italic
        html = html.replace(/\*\*([^*]+)\*\*/g, '<strong>$1</strong>');
        html = html.replace(/\*([^*]+)\*/g, '<em>$1</em>');

        // Bullet lists
        html = html.replace(/^\s*-\s+(.*$)/gim, '<li>$1</li>');
        html = html.replace(/(<li>.*<\/li>)/gms, '<ul>$1</ul>');

        // Line breaks (preserving paragraphs outside of pre/code)
        const parts = html.split(/(<div class="code-block-container">[\s\S]*?<\/div>)/);
        for (let i = 0; i < parts.length; i++) {
            if (!parts[i].startsWith('<div class="code-block-container">')) {
                parts[i] = parts[i].replace(/\n\n/g, '</p><p>').replace(/\n/g, '<br>');
            }
        }
        html = `<p>${parts.join('')}</p>`;

        // Clean up empty paragraphs
        html = html.replace(/<p>\s*<\/p>/g, '');

        return html;
    }

    attachCodeBlockCopyButtons(container) {
        const copyBtns = container.querySelectorAll('.copy-code-btn');
        copyBtns.forEach(btn => {
            btn.onclick = () => {
                const code = btn.closest('.code-block-container').querySelector('code').textContent;
                navigator.clipboard.writeText(code).then(() => {
                    const orig = btn.textContent;
                    btn.textContent = 'Copied!';
                    btn.style.color = 'var(--green-accent)';
                    setTimeout(() => {
                        btn.textContent = orig;
                        btn.style.color = 'var(--teal-accent)';
                    }, 2000);
                });
            };
        });
    }

    /**
     * Append User Message Bubble
     */
    appendUserMessage(text) {
        const timeStr = new Date().toLocaleTimeString([], { hour: '2-digit', minute: '2-digit' });
        const row = document.createElement('div');
        row.className = 'message-row user-row';
        row.innerHTML = `
            <div class="avatar user-avatar">👤</div>
            <div class="message-bubble user-bubble">
                <div class="bubble-header">
                    <span class="sender-name">You (${this.selectedPriority.toUpperCase()})</span>
                    <span class="message-meta">${timeStr}</span>
                </div>
                <div class="bubble-content">
                    <p>${this.escapeHtml(text).replace(/\n/g, '<br>')}</p>
                </div>
            </div>
        `;
        this.dom.chatContainer.appendChild(row);
        this.scrollToBottom();
    }

    /**
     * Append AI Placeholder Bubble with Bouncing Dots
     */
    appendAIMessagePlaceholder(model) {
        const timeStr = new Date().toLocaleTimeString([], { hour: '2-digit', minute: '2-digit' });
        const row = document.createElement('div');
        row.className = 'message-row ai-row';
        
        row.innerHTML = `
            <div class="avatar ai-avatar">⚡</div>
            <div class="message-bubble ai-bubble">
                <div class="bubble-header">
                    <span class="sender-name">${model || 'CodeMesh AI'}</span>
                    <span class="message-meta">Queued • ${timeStr}</span>
                </div>
                <div class="bubble-content">
                    <div class="typing-indicator-box">
                        <span class="typing-dot"></span>
                        <span class="typing-dot"></span>
                        <span class="typing-dot"></span>
                    </div>
                </div>
            </div>
        `;
        this.dom.chatContainer.appendChild(row);
        this.scrollToBottom();

        return {
            row: row,
            bubble: row.querySelector('.ai-bubble'),
            contentEl: row.querySelector('.bubble-content')
        };
    }

    escapeHtml(str) {
        const div = document.createElement('div');
        div.textContent = str;
        return div.innerHTML;
    }

    scrollToBottom() {
        this.dom.chatContainer.scrollTop = this.dom.chatContainer.scrollHeight;
    }

    showToast(message, type = 'info') {
        const toast = document.createElement('div');
        toast.className = `toast ${type}`;
        toast.textContent = message;
        this.dom.toastContainer.appendChild(toast);
        setTimeout(() => {
            toast.style.opacity = '0';
            toast.style.transition = 'opacity 0.3s ease';
            setTimeout(() => toast.remove(), 300);
        }, 3200);
    }
}

// Initialize on DOM Ready
document.addEventListener('DOMContentLoaded', () => {
    window.codeMeshApp = new CodeMeshApp();
});
