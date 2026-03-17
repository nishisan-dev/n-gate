import { useState, useMemo, useEffect, useRef, useCallback } from 'react';
import {
  AreaChart,
  Area,
  ResponsiveContainer,
  Tooltip,
  XAxis,
} from 'recharts';
import { Activity, Gauge, AlertTriangle, ChevronDown, TrendingUp } from 'lucide-react';
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
  showAsRate?: boolean;
}

interface SparklinePoint {
  time: string;
  value: number;
}

// ─── Mapa estático: label → { metricName, showAsRate } ────────
// Usado pelo sparkline para evitar dependência de `cards` (que muda a cada push)
const CARD_META: Record<string, { metricName: string; showAsRate: boolean }> = {
  'Requests':     { metricName: 'ngate.requests.total', showAsRate: true },
  'Req/s':        { metricName: 'ngate.requests.total', showAsRate: true },
  'Latência Média': { metricName: 'ngate.request.duration', showAsRate: false },
  'Erros 5xx':    { metricName: 'ngate.request.errors', showAsRate: true },
  'Rate Limited': { metricName: 'ngate.ratelimit.total', showAsRate: true },
  'Threads':      { metricName: 'jvm.threads.live', showAsRate: false },
};

const COLOR_MAP: Record<string, string> = {
  accent: '#748FFC',
  success: '#51CF66',
  warning: '#FCC419',
  error: '#FF6B6B',
};

export function MetricsCards({ metrics }: Props) {
  const [expandedCard, setExpandedCard] = useState<string | null>(null);
  const [sparkData, setSparkData] = useState<SparklinePoint[]>([]);
  const [sparkLoading, setSparkLoading] = useState(false);
  const [requestRate, setRequestRate] = useState(0);
  const prevRequestsRef = useRef<{ count: number; time: number } | null>(null);

  // ─── Calcula req/s a partir do delta do contador ────────────
  const totalRequests = sumAllMetricValues(metrics, 'ngate.requests.total', 'value');
  useEffect(() => {
    const now = Date.now();
    if (prevRequestsRef.current !== null) {
      const dt = (now - prevRequestsRef.current.time) / 1000;
      if (dt > 0) {
        const delta = totalRequests - prevRequestsRef.current.count;
        setRequestRate(Math.max(0, Math.round((delta / dt) * 10) / 10));
      }
    }
    prevRequestsRef.current = { count: totalRequests, time: now };
  }, [totalRequests]);

  // ─── Cards ─────────────────────────────────────────────────
  const cards = useMemo<CardData[]>(() => {
    const meanLatency = findMetricValue(metrics, 'ngate.request.duration', 'mean') ?? 0;
    const errorCount = sumMetricValues(metrics, 'ngate.requests.total', 'value', { status: '5' });
    const rateLimitRejects = sumMetricValues(metrics, 'ngate.ratelimit.total', 'value', { result: 'REJECTED' });
    const activeThreads = findMetricValue(metrics, 'jvm.threads.live', 'value') ?? 0;

    return [
      {
        label: 'Requests',
        value: formatNumber(totalRequests),
        unit: 'total',
        icon: <Activity size={16} />,
        color: 'accent' as const,
        metricName: 'ngate.requests.total',
        showAsRate: true,
      },
      {
        label: 'Req/s',
        value: requestRate < 0.1 && requestRate > 0 ? '<0.1' : formatNumber(requestRate),
        unit: 'req/s',
        icon: <TrendingUp size={16} />,
        color: requestRate > 0 ? 'success' as const : 'accent' as const,
        metricName: 'ngate.requests.total',
        showAsRate: true,
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
        label: 'Erros 5xx',
        value: formatNumber(errorCount),
        unit: 'total',
        icon: <AlertTriangle size={16} />,
        color: errorCount > 0 ? 'error' as const : 'success' as const,
        metricName: 'ngate.request.errors',
        showAsRate: true,
      },
      {
        label: 'Rate Limited',
        value: formatNumber(rateLimitRejects),
        unit: 'rejected',
        icon: <AlertTriangle size={16} />,
        color: rateLimitRejects > 0 ? 'warning' as const : 'success' as const,
        metricName: 'ngate.ratelimit.total',
        showAsRate: true,
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
  }, [metrics, totalRequests, requestRate]);

  // ─── Sparkline fetch (estável, não depende de `cards`) ─────
  const fetchSparkline = useCallback(async (label: string) => {
    const meta = CARD_META[label];
    if (!meta) return;

    setSparkLoading(true);
    try {
      const now = new Date();
      const from = new Date(now.getTime() - 3600 * 1000);
      const response = await api.getMetricHistory(
        meta.metricName,
        from.toISOString(),
        now.toISOString()
      );
      const records = response?.data || response || [];
      // eslint-disable-next-line @typescript-eslint/no-explicit-any
      let points: SparklinePoint[] = records.map((r: any) => {
        const ts = r.timestamp || r.bucket_ts;
        return {
          time: new Date(ts).toLocaleTimeString('pt-BR', {
            hour: '2-digit',
            minute: '2-digit',
          }),
          value: Math.round((r.avg ?? r.val_avg ?? r.value ?? 0) * 100) / 100,
        };
      });

      // Para counters, computa rate (delta entre pontos consecutivos)
      if (meta.showAsRate && points.length > 1) {
        const ratePoints: SparklinePoint[] = [];
        for (let i = 1; i < points.length; i++) {
          const delta = points[i].value - points[i - 1].value;
          ratePoints.push({
            time: points[i].time,
            value: Math.max(0, Math.round(delta * 100) / 100),
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
  }, []);

  // Só re-executa quando o card expandido muda (não a cada push de métricas)
  useEffect(() => {
    if (!expandedCard) {
      setSparkData([]);
      return;
    }

    fetchSparkline(expandedCard);
    const id = setInterval(() => fetchSparkline(expandedCard), 30000);
    return () => clearInterval(id);
  }, [expandedCard, fetchSparkline]);

  // ─── Handlers ──────────────────────────────────────────────
  const handleCardClick = (label: string) => {
    setExpandedCard((prev) => (prev === label ? null : label));
  };

  // Cor do card expandido
  const expandedColor = cards.find((c) => c.label === expandedCard)?.color ?? 'accent';

  return (
    <div className="metrics-cards-container">
      <div className="metrics-cards">
        {cards.map((card) => {
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
                        stopColor={COLOR_MAP[expandedColor]}
                        stopOpacity={0.3}
                      />
                      <stop
                        offset="95%"
                        stopColor={COLOR_MAP[expandedColor]}
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
                    stroke={COLOR_MAP[expandedColor]}
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

function sumMetricValues(
  metrics: Record<string, MetricData>,
  prefix: string,
  field: 'value' | 'count' | 'mean' | 'max',
  tagFilter: Record<string, string>
): number {
  let sum = 0;
  for (const [key, data] of Object.entries(metrics)) {
    if (!key.startsWith(prefix)) continue;
    if (data.tags) {
      const matches = Object.entries(tagFilter).every(
        ([k, v]) => data.tags?.[k]?.startsWith(v)
      );
      if (!matches) continue;
    } else {
      continue;
    }
    if (field === 'value' && data.value !== undefined) sum += data.value;
    if (field === 'count' && data.count !== undefined) sum += data.count;
    if (field === 'mean' && data.mean !== undefined) sum += data.mean;
    if (field === 'max' && data.max !== undefined) sum += data.max;
  }
  return sum;
}

function sumAllMetricValues(
  metrics: Record<string, MetricData>,
  prefix: string,
  field: 'value' | 'count'
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
