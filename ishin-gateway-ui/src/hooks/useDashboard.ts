import { useState, useEffect, useCallback } from 'react';
import { api, createMetricsSocket, type WsState } from '../api';
import type { TopologyData, HealthInfo, EventLog, MetricData } from '../types';

// ─── Page Visibility helper ────────────────────────────────────
// Pauses and resumes an interval based on document visibility.
// Returns a cleanup function.

function createVisibilityAwareInterval(
  callback: () => void,
  intervalMs: number,
): () => void {
  let timerId: ReturnType<typeof setInterval> | null = null;

  function start() {
    if (timerId !== null) return;
    timerId = setInterval(callback, intervalMs);
  }

  function stop() {
    if (timerId !== null) {
      clearInterval(timerId);
      timerId = null;
    }
  }

  function onVisibilityChange() {
    if (document.hidden) {
      stop();
    } else {
      callback(); // fetch immediately on resume
      start();
    }
  }

  document.addEventListener('visibilitychange', onVisibilityChange);

  // Start immediately if visible
  if (!document.hidden) {
    start();
  }

  return () => {
    stop();
    document.removeEventListener('visibilitychange', onVisibilityChange);
  };
}

// ─── useMetrics: WebSocket + polling fallback ───────────────────

export function useMetrics(intervalMs = 5000) {
  const [metrics, setMetrics] = useState<Record<string, MetricData>>({});

  useEffect(() => {
    const controller = new AbortController();
    let pollCleanup: (() => void) | null = null;
    let wsConnected = false;

    // Polling fallback — only runs when WS is not connected
    function startPolling() {
      if (pollCleanup) return; // already polling
      pollCleanup = createVisibilityAwareInterval(async () => {
        if (wsConnected) return; // WS took over
        try {
          const data = await api.getCurrentMetrics();
          setMetrics(data);
        } catch { /* silent */ }
      }, intervalMs);
    }

    function stopPolling() {
      pollCleanup?.();
      pollCleanup = null;
    }

    // WebSocket with managed lifecycle
    const socket = createMetricsSocket({
      signal: controller.signal,
      onMessage: (data) => {
        setMetrics(data as Record<string, MetricData>);
      },
      onStateChange: (state: WsState) => {
        if (state === 'open') {
          wsConnected = true;
          stopPolling(); // WS is live, kill polling
        } else if (state === 'closed' || state === 'connecting') {
          wsConnected = false;
          startPolling(); // WS down, use polling fallback
        }
      },
    });

    // Start polling immediately as fallback until WS connects
    startPolling();

    return () => {
      controller.abort(); // stops WS + reconnection chain
      socket.close();
      stopPolling();
    };
  }, [intervalMs]);

  return metrics;
}

// ─── useTopology ────────────────────────────────────────────────

export function useTopology(intervalMs = 30000) {
  const [topology, setTopology] = useState<TopologyData | null>(null);
  const [loading, setLoading] = useState(true);

  const fetchTopology = useCallback(async () => {
    try {
      const data = await api.getTopology();
      setTopology(data);
    } catch (err) {
      console.error('Failed to fetch topology:', err);
    } finally {
      setLoading(false);
    }
  }, []);

  useEffect(() => {
    fetchTopology();
    const cleanup = createVisibilityAwareInterval(fetchTopology, intervalMs);
    return cleanup;
  }, [fetchTopology, intervalMs]);

  return { topology, loading, refetch: fetchTopology };
}

// ─── useHealth ──────────────────────────────────────────────────

export function useHealth(intervalMs = 10000) {
  const [health, setHealth] = useState<HealthInfo | null>(null);

  useEffect(() => {
    const fetchHealth = async () => {
      try {
        const data = await api.getHealth();
        setHealth(data);
      } catch { /* silent */ }
    };
    fetchHealth();
    const cleanup = createVisibilityAwareInterval(fetchHealth, intervalMs);
    return cleanup;
  }, [intervalMs]);

  return health;
}

// ─── useEvents ──────────────────────────────────────────────────

export function useEvents(limit = 50, intervalMs = 10000) {
  const [events, setEvents] = useState<EventLog[]>([]);

  useEffect(() => {
    const fetchEvents = async () => {
      try {
        const data = await api.getEvents(limit);
        setEvents(data);
      } catch { /* silent */ }
    };
    fetchEvents();
    const cleanup = createVisibilityAwareInterval(fetchEvents, intervalMs);
    return cleanup;
  }, [limit, intervalMs]);

  return events;
}

// ─── useTunnelRuntime ───────────────────────────────────────────

export function useTunnelRuntime(enabled: boolean, intervalMs = 5000) {
  const [runtime, setRuntime] = useState<import('../types').TunnelRuntimeSnapshot | null>(null);

  useEffect(() => {
    if (!enabled) {
      setRuntime(null);
      return;
    }

    const fetchRuntime = async () => {
      try {
        const data = await api.getTunnelRuntime();
        setRuntime(data);
      } catch { /* silent */ }
    };
    fetchRuntime();
    const cleanup = createVisibilityAwareInterval(fetchRuntime, intervalMs);
    return cleanup;
  }, [enabled, intervalMs]);

  return runtime;
}
