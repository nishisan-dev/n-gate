import { useState, useEffect, useRef, useCallback } from 'react';
import { api } from '../api';
import type { TopologyData, HealthInfo, EventLog, MetricData } from '../types';

// ─── useMetrics: WebSocket + polling fallback ───────────────────

export function useMetrics(intervalMs = 5000) {
  const [metrics, setMetrics] = useState<Record<string, MetricData>>({});
  const wsRef = useRef<WebSocket | null>(null);

  useEffect(() => {
    // Tenta WebSocket primeiro
    wsRef.current = api.connectMetricsWs((data) => {
      setMetrics(data as Record<string, MetricData>);
    });

    // Polling como fallback enquanto WebSocket não conecta
    const pollId = setInterval(async () => {
      try {
        const data = await api.getCurrentMetrics();
        setMetrics(data);
      } catch { /* silent */ }
    }, intervalMs);

    return () => {
      clearInterval(pollId);
      wsRef.current?.close();
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
    const id = setInterval(fetchTopology, intervalMs);
    return () => clearInterval(id);
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
    const id = setInterval(fetchHealth, intervalMs);
    return () => clearInterval(id);
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
    const id = setInterval(fetchEvents, intervalMs);
    return () => clearInterval(id);
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
    const id = setInterval(fetchRuntime, intervalMs);
    return () => clearInterval(id);
  }, [enabled, intervalMs]);

  return runtime;
}
