const API_BASE = '/api/v1';
const WS_BASE = `ws://${window.location.host}/ws`;

// ─── Managed WebSocket ─────────────────────────────────────────
// Handles reconnection with linear backoff and proper cleanup
// via AbortSignal. Prevents the recursive WS leak pattern.

const WS_INITIAL_DELAY = 3_000;
const WS_MAX_DELAY = 30_000;
const WS_BACKOFF_STEP = 3_000;

export interface ManagedSocket {
  close(): void;
}

export type WsState = 'connecting' | 'open' | 'closed';

interface ManagedSocketOptions {
  onMessage: (data: Record<string, unknown>) => void;
  onStateChange?: (state: WsState) => void;
  signal: AbortSignal;
}

export function createMetricsSocket(options: ManagedSocketOptions): ManagedSocket {
  const { onMessage, onStateChange, signal } = options;

  let ws: WebSocket | null = null;
  let reconnectTimer: ReturnType<typeof setTimeout> | null = null;
  let currentDelay = WS_INITIAL_DELAY;
  let disposed = false;

  function cleanup() {
    disposed = true;
    if (reconnectTimer !== null) {
      clearTimeout(reconnectTimer);
      reconnectTimer = null;
    }
    if (ws) {
      // Remove handlers before closing to prevent onclose from firing reconnect
      ws.onopen = null;
      ws.onmessage = null;
      ws.onerror = null;
      ws.onclose = null;
      ws.close();
      ws = null;
    }
    onStateChange?.('closed');
  }

  function connect() {
    if (disposed || signal.aborted) return;

    onStateChange?.('connecting');

    ws = new WebSocket(`${WS_BASE}/metrics`);

    ws.onopen = () => {
      currentDelay = WS_INITIAL_DELAY; // reset backoff on success
      onStateChange?.('open');
    };

    ws.onmessage = (event) => {
      if (disposed) return;
      try {
        const data = JSON.parse(event.data);
        onMessage(data);
      } catch { /* ignore parse errors */ }
    };

    ws.onerror = () => {
      // onerror is always followed by onclose; let onclose handle reconnect
    };

    ws.onclose = () => {
      if (disposed || signal.aborted) return;
      onStateChange?.('closed');
      scheduleReconnect();
    };
  }

  function scheduleReconnect() {
    if (disposed || signal.aborted) return;
    if (reconnectTimer !== null) return; // already scheduled

    reconnectTimer = setTimeout(() => {
      reconnectTimer = null;
      currentDelay = Math.min(currentDelay + WS_BACKOFF_STEP, WS_MAX_DELAY);
      connect();
    }, currentDelay);
  }

  // Wire up abort for full cleanup
  signal.addEventListener('abort', cleanup, { once: true });

  // Start initial connection
  connect();

  return { close: cleanup };
}

// ─── REST API ──────────────────────────────────────────────────

export const api = {
  async getTopology() {
    const res = await fetch(`${API_BASE}/topology`);
    return res.json();
  },

  async getCurrentMetrics() {
    const res = await fetch(`${API_BASE}/metrics/current`);
    return res.json();
  },

  async getMetricHistory(name: string, from?: string, to?: string, tier?: string) {
    const params = new URLSearchParams({ name });
    if (from) params.set('from', from);
    if (to) params.set('to', to);
    if (tier) params.set('tier', tier);
    const res = await fetch(`${API_BASE}/metrics/history?${params}`);
    return res.json();
  },

  async getMetricTiers() {
    const res = await fetch(`${API_BASE}/metrics/tiers`);
    return res.json();
  },

  async getHealth() {
    const res = await fetch(`${API_BASE}/health`);
    return res.json();
  },

  async getEvents(limit = 50) {
    const res = await fetch(`${API_BASE}/events?limit=${limit}`);
    return res.json();
  },

  async getTunnelRuntime() {
    const res = await fetch(`${API_BASE}/tunnel/runtime`);
    if (!res.ok) return null;
    return res.json();
  },

  async getTraces(params?: Record<string, string>) {
    const query = params ? '?' + new URLSearchParams(params).toString() : '';
    const res = await fetch(`${API_BASE}/traces${query}`);
    return res.json();
  },

  async getTrace(traceId: string) {
    const res = await fetch(`${API_BASE}/traces/${traceId}`);
    return res.json();
  },
};
