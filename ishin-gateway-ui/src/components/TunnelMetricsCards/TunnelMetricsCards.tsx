import { useMemo, useState, useEffect, useRef, useCallback } from 'react';
import {
  AreaChart,
  Area,
  ResponsiveContainer,
  Tooltip,
  XAxis,
} from 'recharts';
import { Activity, Gauge, AlertTriangle, ChevronDown, Zap, ArrowUpDown, Network } from 'lucide-react';
import { api } from '../../api';
import type { MetricData, TunnelRuntimeSnapshot } from '../../types';
import '../MetricsCards/MetricsCards.css';

interface Props {
  metrics: Record<string, MetricData>;
  runtime: TunnelRuntimeSnapshot | null;
}

interface SparklinePoint {
  time: string;
  timestamp: number;
  value: number;
}

const COLOR_MAP: Record<string, string> = {
  accent: '#748FFC',
  success: '#51CF66',
  warning: '#FCC419',
  error: '#FF6B6B',
};

export function TunnelMetricsCards({ metrics, runtime }: Props) {
  const [expandedCard, setExpandedCard] = useState<string | null>(null);
  const [sparkData, setSparkData] = useState<SparklinePoint[]>([]);
  const [sparkLoading, setSparkLoading] = useState(false);
  const [connRate, setConnRate] = useState(0);
  const prevConnsRef = useRef<{ count: number; time: number } | null>(null);

  // ─── Calcula conn/s a partir do delta do contador ─────────
  const totalConns = sumAllMetricValues(metrics, 'ishin.tunnel.connections.accepted', 'value');
  useEffect(() => {
    const now = Date.now();
    if (prevConnsRef.current !== null) {
      const dt = (now - prevConnsRef.current.time) / 1000;
      if (dt > 0) {
        const delta = totalConns - prevConnsRef.current.count;
        setConnRate(Math.max(0, Math.round((delta / dt) * 10) / 10));
      }
    }
    prevConnsRef.current = { count: totalConns, time: now };
  }, [totalConns]);

  const activeConns = runtime?.activeConnections ?? findMetricValue(metrics, 'ishin.tunnel.connections.active', 'value') ?? 0;
  const connectErrors = sumAllMetricValues(metrics, 'ishin.tunnel.connect.errors', 'value');
  const avgSessionMs = findMetricValue(metrics, 'ishin.tunnel.session.duration', 'mean') ?? 0;
  const avgConnectMs = findMetricValue(metrics, 'ishin.tunnel.connection.duration', 'mean') ?? 0;

  // Throughput (somando bytes in + out)
  const bytesIn = findMetricValue(metrics, 'ishin.tunnel.bytes.sent', 'value') ?? 0;
  const bytesOut = findMetricValue(metrics, 'ishin.tunnel.bytes.received', 'value') ?? 0;
  const totalBytes = bytesIn + bytesOut;

  const cards = useMemo(() => [
    {
      label: 'Conn/s',
      value: connRate < 0.1 && connRate > 0 ? '<0.1' : formatNumber(connRate),
      unit: 'conn/s',
      icon: <Zap size={16} />,
      color: connRate > 0 ? 'success' as const : 'accent' as const,
      metricName: 'ishin.tunnel.connections.accepted',
      showAsRate: true,
      sparkUnit: 'conn/s',
    },
    {
      label: 'Ativas',
      value: formatNumber(activeConns),
      unit: 'conexões',
      icon: <Activity size={16} />,
      color: activeConns > 0 ? 'success' as const : 'accent' as const,
      metricName: 'ishin.tunnel.connections.active',
      showAsRate: false,
      sparkUnit: 'conns',
    },
    {
      label: 'TCP Connect',
      value: avgConnectMs < 1 ? '<1' : formatNumber(avgConnectMs),
      unit: 'ms avg',
      icon: <Network size={16} />,
      color: avgConnectMs > 500 ? 'warning' as const : 'success' as const,
      metricName: 'ishin.tunnel.connection.duration',
      showAsRate: false,
      sparkUnit: 'ms',
    },
    {
      label: 'Sessão',
      value: avgSessionMs < 1000 ? formatNumber(avgSessionMs) + 'ms' : formatNumber(avgSessionMs / 1000) + 's',
      unit: 'avg',
      icon: <Gauge size={16} />,
      color: 'accent' as const,
      metricName: 'ishin.tunnel.session.duration',
      showAsRate: false,
      sparkUnit: 'ms',
    },
    {
      label: 'Throughput',
      value: formatBytes(totalBytes),
      unit: 'total',
      icon: <ArrowUpDown size={16} />,
      color: 'accent' as const,
      metricName: 'ishin.tunnel.bytes.sent',
      showAsRate: true,
      sparkUnit: 'bytes/s',
    },
    {
      label: 'Connect Errors',
      value: formatNumber(connectErrors),
      unit: 'total',
      icon: <AlertTriangle size={16} />,
      color: connectErrors > 0 ? 'error' as const : 'success' as const,
      metricName: 'ishin.tunnel.connect.errors',
      showAsRate: true,
      sparkUnit: 'err/s',
    },
  ], [connRate, activeConns, avgConnectMs, avgSessionMs, totalBytes, connectErrors]);

  const fetchSparkline = useCallback(async (label: string) => {
    const card = cards.find(c => c.label === label);
    if (!card) return;
    setSparkLoading(true);
    try {
      const now = new Date();
      const from = new Date(now.getTime() - 3600 * 1000);
      const response = await api.getMetricHistory(card.metricName, from.toISOString(), now.toISOString());
      const records = response?.data || response || [];
      // eslint-disable-next-line @typescript-eslint/no-explicit-any
      let points: SparklinePoint[] = records.map((r: any) => ({
        time: new Date(r.timestamp || Date.now()).toLocaleTimeString('pt-BR', { hour: '2-digit', minute: '2-digit' }),
        timestamp: new Date(r.timestamp || Date.now()).getTime(),
        value: Math.round(((r.avg ?? r.val_avg ?? r.value ?? 0) as number) * 100) / 100,
      }));

      if (card.showAsRate && points.length > 1) {
        const ratePoints: SparklinePoint[] = [];
        for (let i = 1; i < points.length; i++) {
          const delta = points[i].value - points[i - 1].value;
          const elapsed = (points[i].timestamp - points[i - 1].timestamp) / 1000;
          ratePoints.push({
            time: points[i].time,
            timestamp: points[i].timestamp,
            value: elapsed > 0 ? Math.max(0, Math.round((delta / elapsed) * 100) / 100) : 0,
          });
        }
        points = ratePoints;
      }
      setSparkData(points);
    } catch {
      setSparkData([]);
    } finally {
      setSparkLoading(false);
    }
  }, [cards]);

  useEffect(() => {
    if (!expandedCard) { setSparkData([]); return; }
    fetchSparkline(expandedCard);
    const id = setInterval(() => fetchSparkline(expandedCard), 30000);
    return () => clearInterval(id);
  }, [expandedCard, fetchSparkline]);

  const handleCardClick = (label: string) => {
    setExpandedCard(prev => prev === label ? null : label);
  };

  const expandedColor = cards.find(c => c.label === expandedCard)?.color ?? 'accent';
  const expandedMeta = expandedCard ? cards.find(c => c.label === expandedCard) : undefined;

  return (
    <div className="metrics-cards-container">
      <div className="metrics-cards">
        {cards.map(card => {
          const isExpanded = expandedCard === card.label;
          return (
            <div
              key={card.label}
              className={`metric-card metric-card-${card.color} ${isExpanded ? 'metric-card-expanded' : ''}`}
              onClick={() => handleCardClick(card.label)}
            >
              <div className="metric-card-header">
                <span className="metric-card-icon">{card.icon}</span>
                <span className="metric-card-label">{card.label}</span>
                <ChevronDown size={12} className={`metric-card-chevron ${isExpanded ? 'chevron-up' : ''}`} />
              </div>
              <div className="metric-card-body">
                <span className="metric-card-value">{card.value}</span>
                <span className="metric-card-unit">{card.unit}</span>
              </div>
            </div>
          );
        })}
      </div>

      {expandedCard && (
        <div className="sparkline-panel animate-fadeIn">
          <div className="sparkline-header">
            <span className="sparkline-title mono">{expandedCard}</span>
            <span className="sparkline-range">Última 1h</span>
          </div>
          <div className="sparkline-chart">
            {sparkLoading ? (
              <div className="sparkline-loading">Carregando...</div>
            ) : sparkData.length === 0 ? (
              <div className="sparkline-empty">Sem dados históricos</div>
            ) : (
              <ResponsiveContainer width="100%" height={120}>
                <AreaChart data={sparkData} margin={{ top: 5, right: 5, left: 5, bottom: 0 }}>
                  <defs>
                    <linearGradient id="tunnelSparkGrad" x1="0" y1="0" x2="0" y2="1">
                      <stop offset="5%" stopColor={COLOR_MAP[expandedColor]} stopOpacity={0.3} />
                      <stop offset="95%" stopColor={COLOR_MAP[expandedColor]} stopOpacity={0} />
                    </linearGradient>
                  </defs>
                  <XAxis
                    type="number" dataKey="timestamp" domain={['dataMin', 'dataMax']} scale="time"
                    stroke="#5C5F66" fontSize={9} fontFamily="var(--font-mono)"
                    tickLine={false} axisLine={false} interval="preserveStartEnd" minTickGap={24}
                    tickFormatter={v => new Date(v).toLocaleTimeString('pt-BR', { hour: '2-digit', minute: '2-digit' })}
                  />
                  <Tooltip
                    contentStyle={{
                      background: '#25262B', border: '1px solid rgba(255,255,255,0.1)',
                      borderRadius: '6px', fontSize: '11px', fontFamily: 'var(--font-mono)',
                      color: '#C1C2C5', padding: '4px 8px',
                    }}
                    labelStyle={{ color: '#909296', fontSize: '10px' }}
                    labelFormatter={v => new Date(Number(v)).toLocaleString('pt-BR')}
                    // eslint-disable-next-line @typescript-eslint/no-explicit-any
                    formatter={((value: number | string) => [
                      `${Number(value).toLocaleString('pt-BR')} ${expandedMeta?.sparkUnit ?? ''}`,
                      expandedMeta?.showAsRate ? 'Taxa' : 'Valor',
                    ]) as any}
                  />
                  <Area type="monotone" dataKey="value" stroke={COLOR_MAP[expandedColor]}
                    strokeWidth={2} fill="url(#tunnelSparkGrad)" dot={false}
                    activeDot={{ r: 3, strokeWidth: 0 }} />
                </AreaChart>
              </ResponsiveContainer>
            )}
          </div>
        </div>
      )}
    </div>
  );
}

// ─── Helpers ────────────────────────────────────────────────────

function findMetricValue(
  metrics: Record<string, MetricData>, prefix: string,
  field: 'value' | 'count' | 'mean' | 'max'
): number | undefined {
  for (const [key, data] of Object.entries(metrics)) {
    if (!key.startsWith(prefix)) continue;
    if (field === 'value' && data.value !== undefined) return data.value;
    if (field === 'count' && data.count !== undefined) return data.count;
    if (field === 'mean' && data.mean !== undefined) return data.mean;
    if (field === 'max' && data.max !== undefined) return data.max;
  }
  return undefined;
}

function sumAllMetricValues(
  metrics: Record<string, MetricData>, prefix: string, field: 'value' | 'count'
): number {
  let sum = 0;
  for (const [key, data] of Object.entries(metrics)) {
    if (!key.startsWith(prefix)) continue;
    if (field === 'value' && data.value !== undefined) sum += data.value;
    if (field === 'count' && data.count !== undefined) sum += data.count;
  }
  return sum;
}

function formatNumber(n: number): string {
  if (n >= 1_000_000) return (n / 1_000_000).toFixed(1) + 'M';
  if (n >= 1_000) return (n / 1_000).toFixed(1) + 'K';
  return Math.round(n).toLocaleString();
}

function formatBytes(bytes: number): string {
  if (bytes >= 1_073_741_824) return (bytes / 1_073_741_824).toFixed(1) + ' GB';
  if (bytes >= 1_048_576) return (bytes / 1_048_576).toFixed(1) + ' MB';
  if (bytes >= 1024) return (bytes / 1024).toFixed(1) + ' KB';
  return bytes + ' B';
}
