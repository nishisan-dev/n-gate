const API_BASE = '/api/v1';
const WS_BASE = `ws://${window.location.host}/ws`;

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

  connectMetricsWs(onMessage: (data: Record<string, unknown>) => void) {
    const ws = new WebSocket(`${WS_BASE}/metrics`);
    ws.onmessage = (event) => {
      try {
        const data = JSON.parse(event.data);
        onMessage(data);
      } catch { /* ignore parse errors */ }
    };
    ws.onerror = () => {
      console.warn('Dashboard WebSocket error, reconnecting...');
      setTimeout(() => api.connectMetricsWs(onMessage), 5000);
    };
    ws.onclose = () => {
      setTimeout(() => api.connectMetricsWs(onMessage), 5000);
    };
    return ws;
  }
};
