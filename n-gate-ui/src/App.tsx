import { useState } from 'react';
import { ReactFlowProvider } from '@xyflow/react';
import { MetricsCards } from './components/MetricsCards/MetricsCards';
import { TopologyView } from './components/TopologyView/TopologyView';
import { EventTimeline } from './components/EventTimeline/EventTimeline';
import { LatencyChart } from './components/LatencyChart/LatencyChart';
import { TracesPanel } from './components/TracesPanel/TracesPanel';
import { useMetrics, useTopology, useHealth, useEvents } from './hooks/useDashboard';
import { Activity, Server, Shield, Map, TrendingUp, Layers, Clock } from 'lucide-react';
import './App.css';

type TabId = 'topology' | 'latency' | 'traces';

function App() {
  const metrics = useMetrics();
  const { topology, loading: topoLoading } = useTopology();
  const health = useHealth();
  const events = useEvents();
  const [activeTab, setActiveTab] = useState<TabId>('topology');

  const tabs: { id: TabId; label: string; icon: React.ReactNode }[] = [
    { id: 'topology', label: 'Topologia', icon: <Map size={13} /> },
    { id: 'latency', label: 'Latência', icon: <TrendingUp size={13} /> },
    { id: 'traces', label: 'Traces', icon: <Layers size={13} /> },
  ];

  return (
    <div className="dashboard">
      {/* ─── Sidebar ──────────────────────────────────────── */}
      <aside className="sidebar">
        <div className="sidebar-brand">
          <Shield size={20} className="brand-icon" />
          <div className="brand-text">
            <span className="brand-name">n-gate</span>
            <span className="brand-sub">observability</span>
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
          <div className="status-item">
            <Activity size={12} />
            <span className="status-label">{health?.listeners ?? 0} listeners</span>
          </div>
        </div>

        <EventTimeline events={events} />
      </aside>

      {/* ─── Main Content ─────────────────────────────────── */}
      <main className="main">
        <header className="main-header">
          <h1>Dashboard</h1>
          <span className="header-timestamp mono">
            {new Date().toLocaleTimeString('pt-BR')}
          </span>
        </header>

        <section className="main-metrics">
          <MetricsCards metrics={metrics} />
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
              <TopologyView data={topology} loading={topoLoading} />
            </ReactFlowProvider>
          )}

          {activeTab === 'latency' && (
            <div className="latency-section">
              <LatencyChart
                metricName="ngate.request.duration"
                title="Request Duration"
              />
              <LatencyChart
                metricName="ngate.upstream.duration"
                title="Upstream Duration"
              />
            </div>
          )}

          {activeTab === 'traces' && (
            <TracesPanel enabled={true} />
          )}
        </section>
      </main>
    </div>
  );
}

export default App;
