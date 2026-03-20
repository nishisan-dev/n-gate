import { useState, useMemo, useEffect } from 'react';
import { ReactFlowProvider } from '@xyflow/react';
import { MetricsCards } from './components/MetricsCards/MetricsCards';
import { TunnelMetricsCards } from './components/TunnelMetricsCards/TunnelMetricsCards';
import { TopologyView } from './components/TopologyView/TopologyView';
import { TunnelTopologyView } from './components/TunnelTopologyView/TunnelTopologyView';
import { TunnelMembersPanel } from './components/TunnelMembersPanel/TunnelMembersPanel';
import { TunnelChartsPanel } from './components/TunnelChartsPanel/TunnelChartsPanel';
import { EventTimeline } from './components/EventTimeline/EventTimeline';
import { LatencyChart } from './components/LatencyChart/LatencyChart';
import { TracesPanel } from './components/TracesPanel/TracesPanel';
import { useMetrics, useTopology, useHealth, useEvents, useTunnelRuntime } from './hooks/useDashboard';
import { Activity, Server, Shield, Map, TrendingUp, Layers, Clock, Network, Users } from 'lucide-react';
import './App.css';

type TabId = 'topology' | 'latency' | 'traces' | 'members';

function App() {
  const metrics = useMetrics();
  const { topology, loading: topoLoading } = useTopology();
  const health = useHealth();
  const events = useEvents();

  const isTunnelMode = health?.mode === 'tunnel';
  const runtime = useTunnelRuntime(isTunnelMode);

  const [activeTab, setActiveTab] = useState<TabId>('topology');

  // ─── Clock state (avoids inline new Date() on every render) ───
  const [clockTime, setClockTime] = useState(() => new Date().toLocaleTimeString('pt-BR'));
  useEffect(() => {
    let timerId: ReturnType<typeof setInterval> | null = null;

    function start() {
      if (timerId) return;
      timerId = setInterval(() => setClockTime(new Date().toLocaleTimeString('pt-BR')), 1000);
    }
    function stop() {
      if (timerId) { clearInterval(timerId); timerId = null; }
    }
    function onVisibility() {
      if (document.hidden) { stop(); } else { setClockTime(new Date().toLocaleTimeString('pt-BR')); start(); }
    }

    document.addEventListener('visibilitychange', onVisibility);
    start();
    return () => { stop(); document.removeEventListener('visibilitychange', onVisibility); };
  }, []);

  // Tabs mode-aware: tunnel mode esconde Traces, adiciona Members
  const tabs = useMemo(() => {
    if (isTunnelMode) {
      return [
        { id: 'topology' as TabId, label: 'Topologia', icon: <Map size={13} /> },
        { id: 'members' as TabId, label: 'Members', icon: <Users size={13} /> },
        { id: 'latency' as TabId, label: 'Métricas', icon: <TrendingUp size={13} /> },
      ];
    }
    return [
      { id: 'topology' as TabId, label: 'Topologia', icon: <Map size={13} /> },
      { id: 'latency' as TabId, label: 'Latência', icon: <TrendingUp size={13} /> },
      { id: 'traces' as TabId, label: 'Traces', icon: <Layers size={13} /> },
    ];
  }, [isTunnelMode]);

  return (
    <div className="dashboard">
      {/* ─── Sidebar ──────────────────────────────────────── */}
      <aside className="sidebar">
        <div className="sidebar-brand">
          <Shield size={20} className="brand-icon" />
          <div className="brand-text">
            <span className="brand-name">{isTunnelMode ? 'ishin' : 'n-gate'}</span>
            <span className="brand-sub">{isTunnelMode ? 'tunnel' : 'observability'}</span>
          </div>
          <span className="brand-version mono">{health?.version ?? '...'}</span>
        </div>

        <div className="sidebar-status">
          <div className="status-item">
            <span className={`status-dot ${health?.status === 'UP' ? 'status-dot-success' : 'status-dot-error'}`} />
            <span className="status-label">{health?.status ?? '...'}</span>
          </div>
          <div className="status-item">
            <Server size={12} />
            <span className="status-label">{health?.mode ?? '...'}</span>
          </div>
          <div className="status-item">
            <Clock size={12} />
            <span className="status-label">{health?.uptime ?? '...'}</span>
          </div>
          {isTunnelMode ? (
            <>
              <div className="status-item">
                <Network size={12} />
                <span className="status-label">{health?.virtualListeners ?? 0} listeners</span>
              </div>
              <div className="status-item">
                <Users size={12} />
                <span className="status-label">{health?.tunnelMembers ?? 0} members</span>
              </div>
              <div className="status-item">
                <Activity size={12} />
                <span className="status-label">{health?.activeConnections ?? 0} conns</span>
              </div>
            </>
          ) : (
            <div className="status-item">
              <Activity size={12} />
              <span className="status-label">{health?.listeners ?? 0} listeners</span>
            </div>
          )}
        </div>

        <EventTimeline events={events} />
      </aside>

      {/* ─── Main Content ─────────────────────────────────── */}
      <main className="main">
        <header className="main-header">
          <h1>{isTunnelMode ? 'Tunnel Dashboard' : 'Dashboard'}</h1>
          <span className="header-timestamp mono">
            {clockTime}
          </span>
        </header>

        <section className="main-metrics">
          {isTunnelMode ? (
            <TunnelMetricsCards metrics={metrics} runtime={runtime} />
          ) : (
            <MetricsCards metrics={metrics} />
          )}
        </section>

        {/* ─── Tab Navigation ──────────────────────────────── */}
        <nav className="tab-nav">
          {tabs.map((tab) => (
            <button
              key={tab.id}
              className={`tab-btn ${activeTab === tab.id ? 'tab-active' : ''}`}
              onClick={() => setActiveTab(tab.id)}
            >
              {tab.icon}
              {tab.label}
            </button>
          ))}
        </nav>

        {/* ─── Tab Content ─────────────────────────────────── */}
        <section className="main-content">
          {activeTab === 'topology' && (
            <ReactFlowProvider>
              {isTunnelMode ? (
                <TunnelTopologyView data={topology} loading={topoLoading} metrics={metrics} />
              ) : (
                <TopologyView data={topology} loading={topoLoading} metrics={metrics} />
              )}
            </ReactFlowProvider>
          )}

          {activeTab === 'latency' && (
            <div className="latency-section">
              {isTunnelMode ? (
                <TunnelChartsPanel />
              ) : (
                <>
                  <LatencyChart
                    metricName="ishin.request.duration"
                    title="Request Duration"
                  />
                  <LatencyChart
                    metricName="ishin.upstream.duration"
                    title="Upstream Duration"
                  />
                </>
              )}
            </div>
          )}

          {activeTab === 'members' && isTunnelMode && (
            <TunnelMembersPanel runtime={runtime} />
          )}

          {activeTab === 'traces' && !isTunnelMode && (
            <TracesPanel enabled={true} />
          )}
        </section>
      </main>
    </div>
  );
}

export default App;
