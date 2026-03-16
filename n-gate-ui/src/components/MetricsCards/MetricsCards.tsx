import { useState, useMemo, useEffect } from 'react';
import {
  AreaChart,
  Area,
  ResponsiveContainer,
  Tooltip,
  XAxis,
} from 'recharts';
import { Activity, Gauge, AlertTriangle, Zap, ChevronDown } from 'lucide-react';
import { api } from '../../api';
import type { MetricData } from '../../types';
import './MetricsCards.css';

interface Props {
  metrics: Record<string, MetricData>;
}

interface CardData {
  label: string;
  value: string;
  unit: string;
  icon: React.ReactNode;
  color: 'accent' | 'success' | 'warning' | 'error';
  metricName: string;
}

interface SparklinePoint {
  time: string;
  value: number;
}

export function MetricsCards({ metrics }: Props) {
  const [expandedCard, setExpandedCard] = useState<string | null>(null);
  const [sparkData, setSparkData] = useState<SparklinePoint[]>([]);
  const [sparkLoading, setSparkLoading] = useState(false);

  const cards = useMemo<CardData[]>(() => {
    const totalRequests = findMetricValue(metrics, 'ngate.requests.total', 'count') ?? 0;
    const meanLatency = findMetricValue(metrics, 'ngate.request.duration', 'mean') ?? 0;
    const maxLatency = findMetricValue(metrics, 'ngate.request.duration', 'max') ?? 0;
    const errorCount = findMetricValue(metrics, 'ngate.requests.total', 'count', { status: '5' }) ?? 0;
    const rateLimitRejects = findMetricValue(metrics, 'ngate.ratelimit.total', 'count', { result: 'REJECTED' }) ?? 0;
    const activeThreads = findMetricValue(metrics, 'jvm.threads.live', 'value') ?? 0;

    return [
      {
        label: 'Requests',
        value: formatNumber(totalRequests),
        unit: 'total',
        icon: <Activity size={16} />,
        color: 'accent' as const,
        metricName: 'ngate.requests.total',
      },
      {
        label: 'Latência Média',
        value: meanLatency < 1 ? '<1' : formatNumber(meanLatency),
        unit: 'ms',
        icon: <Gauge size={16} />,
        color: meanLatency > 500 ? 'warning' as const : 'success' as const,
        metricName: 'ngate.request.duration',
      },
      {
        label: 'Latência Max',
        value: formatNumber(maxLatency),
        unit: 'ms',
        icon: <Zap size={16} />,
        color: maxLatency > 2000 ? 'error' as const : 'accent' as const,
        metricName: 'ngate.request.duration',
      },
      {
        label: 'Erros 5xx',
        value: formatNumber(errorCount),
        unit: 'total',
        icon: <AlertTriangle size={16} />,
        color: errorCount > 0 ? 'error' as const : 'success' as const,
        metricName: 'ngate.requests.total',
      },
      {
        label: 'Rate Limited',
        value: formatNumber(rateLimitRejects),
        unit: 'rejected',
        icon: <AlertTriangle size={16} />,
        color: rateLimitRejects > 0 ? 'warning' as const : 'success' as const,
        metricName: 'ngate.ratelimit.total',
      },
      {
        label: 'Threads',
        value: formatNumber(activeThreads),
        unit: 'ativos',
        icon: <Activity size={16} />,
        color: 'accent' as const,
        metricName: 'jvm.threads.live',
      },
    ];
  }, [metrics]);

  // Fetch sparkline data when a card is expanded
  useEffect(() => {
    if (!expandedCard) {
      setSparkData([]);
      return;
    }

    const fetchSparkline = async () => {
      setSparkLoading(true);
      try {
        const now = new Date();
        const from = new Date(now.getTime() - 3600 * 1000); // last 1h
        const response = await api.getMetricHistory(
          expandedCard,
          from.toISOString(),
          now.toISOString()
        );
        // Handle new RRD format: { tier, points, data: [...] }
        const records = response?.data || response || [];
        // eslint-disable-next-line @typescript-eslint/no-explicit-any
        const points: SparklinePoint[] = records.map((r: any) => {
          const ts = r.timestamp || r.bucket_ts;
          return {
            time: new Date(ts).toLocaleTimeString('pt-BR', {
              hour: '2-digit',
              minute: '2-digit',
            }),
            value: Math.round((r.avg ?? r.val_avg ?? r.value ?? 0) * 100) / 100,
          };
        });
        setSparkData(points);
      } catch {
        setSparkData([]);
      } finally {
        setSparkLoading(false);
      }
    };

    fetchSparkline();
    const id = setInterval(fetchSparkline, 30000);
    return () => clearInterval(id);
  }, [expandedCard]);

  const handleCardClick = (metricName: string) => {
    setExpandedCard((prev) => (prev === metricName ? null : metricName));
  };

  const colorMap: Record<string, string> = {
    accent: '#748FFC',
    success: '#51CF66',
    warning: '#FCC419',
    error: '#FF6B6B',
  };

  return (
    <div className="metrics-cards-container">
      <div className="metrics-cards">
        {cards.map((card) => {
          const isExpanded = expandedCard === card.metricName;
          return (
            <div
              key={card.label}
              className={`metric-card metric-card-${card.color} ${isExpanded ? 'metric-card-expanded' : ''}`}
              onClick={() => handleCardClick(card.metricName)}
            >
              <div className="metric-card-header">
                <span className="metric-card-icon">{card.icon}</span>
                <span className="metric-card-label">{card.label}</span>
                <ChevronDown
                  size={12}
                  className={`metric-card-chevron ${isExpanded ? 'chevron-up' : ''}`}
                />
              </div>
              <div className="metric-card-body">
                <span className="metric-card-value">{card.value}</span>
                <span className="metric-card-unit">{card.unit}</span>
              </div>
            </div>
          );
        })}
      </div>

      {/* ─── Sparkline Expansion ──────────────────────────── */}
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
                    <linearGradient id="sparkGradient" x1="0" y1="0" x2="0" y2="1">
                      <stop
                        offset="5%"
                        stopColor={colorMap[cards.find(c => c.metricName === expandedCard)?.color ?? 'accent']}
                        stopOpacity={0.3}
                      />
                      <stop
                        offset="95%"
                        stopColor={colorMap[cards.find(c => c.metricName === expandedCard)?.color ?? 'accent']}
                        stopOpacity={0}
                      />
                    </linearGradient>
                  </defs>
                  <XAxis
                    dataKey="time"
                    stroke="#5C5F66"
                    fontSize={9}
                    fontFamily="var(--font-mono)"
                    tickLine={false}
                    axisLine={false}
                    interval="preserveStartEnd"
                  />
                  <Tooltip
                    contentStyle={{
                      background: '#25262B',
                      border: '1px solid rgba(255,255,255,0.1)',
                      borderRadius: '6px',
                      fontSize: '11px',
                      fontFamily: 'var(--font-mono)',
                      color: '#C1C2C5',
                      padding: '4px 8px',
                    }}
                    labelStyle={{ color: '#909296', fontSize: '10px' }}
                  />
                  <Area
                    type="monotone"
                    dataKey="value"
                    stroke={colorMap[cards.find(c => c.metricName === expandedCard)?.color ?? 'accent']}
                    strokeWidth={2}
                    fill="url(#sparkGradient)"
                    dot={false}
                    activeDot={{ r: 3, strokeWidth: 0 }}
                  />
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
  metrics: Record<string, MetricData>,
  prefix: string,
  field: 'value' | 'count' | 'mean' | 'max',
  tagFilter?: Record<string, string>
): number | undefined {
  for (const [key, data] of Object.entries(metrics)) {
    if (!key.startsWith(prefix)) continue;
    if (tagFilter && data.tags) {
      const matches = Object.entries(tagFilter).every(
        ([k, v]) => data.tags?.[k]?.startsWith(v)
      );
      if (!matches) continue;
    }
    if (field === 'value' && data.value !== undefined) return data.value;
    if (field === 'count' && data.count !== undefined) return data.count;
    if (field === 'mean' && data.mean !== undefined) return data.mean;
    if (field === 'max' && data.max !== undefined) return data.max;
  }
  return undefined;
}

function formatNumber(n: number): string {
  if (n >= 1_000_000) return (n / 1_000_000).toFixed(1) + 'M';
  if (n >= 1_000) return (n / 1_000).toFixed(1) + 'K';
  return Math.round(n).toLocaleString();
}
