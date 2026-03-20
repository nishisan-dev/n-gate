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
  timestamp: number;
  value: number;
}

// ─── Mapa estático: label → { metricName, showAsRate } ────────
// Usado pelo sparkline para evitar dependência de `cards` (que muda a cada push)
const CARD_META: Record<string, { metricName: string; showAsRate: boolean; sparkUnit: string }> = {
  'Requests':       { metricName: 'ishin.requests.total', showAsRate: true, sparkUnit: 'req/s' },
  'Req/s':          { metricName: 'ishin.requests.total', showAsRate: true, sparkUnit: 'req/s' },
  'Latência Média': { metricName: 'ishin.request.duration', showAsRate: false, sparkUnit: 'ms' },
  'Erros 5xx':      { metricName: 'ishin.request.errors', showAsRate: true, sparkUnit: 'err/s' },
  'Rate Limited':   { metricName: 'ishin.ratelimit.total', showAsRate: true, sparkUnit: 'rej/s' },
  'Threads':        { metricName: 'jvm.threads.live', showAsRate: false, sparkUnit: 'ativos' },
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
  const totalRequests = sumAllMetricValues(metrics, 'ishin.requests.total', 'value');
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
    const meanLatency = findMetricValue(metrics, 'ishin.request.duration', 'mean') ?? 0;
    const errorCount = sumMetricValues(metrics, 'ishin.requests.total', 'value', { status: '5' });
    const rateLimitRejects = sumMetricValues(metrics, 'ishin.ratelimit.total', 'value', { result: 'REJECTED' });
    const activeThreads = findMetricValue(metrics, 'jvm.threads.live', 'value') ?? 0;

    return [
      {
        label: 'Requests',
        value: formatNumber(totalRequests),
        unit: 'total',
        icon: <Activity size={16} />,
        color: 'accent' as const,
        metricName: 'ishin.requests.total',
        showAsRate: true,
      },
      {
        label: 'Req/s',
        value: requestRate < 0.1 && requestRate > 0 ? '<0.1' : formatNumber(requestRate),
        unit: 'req/s',
        icon: <TrendingUp size={16} />,
        color: requestRate > 0 ? 'success' as const : 'accent' as const,
        metricName: 'ishin.requests.total',
        showAsRate: true,
      },
      {
        label: 'Latência Média',
        value: meanLatency < 1 ? '<1' : formatNumber(meanLatency),
        unit: 'ms',
        icon: <Gauge size={16} />,
        color: meanLatency > 500 ? 'warning' as const : 'success' as const,
        metricName: 'ishin.request.duration',
      },
      {
        label: 'Erros 5xx',
        value: formatNumber(errorCount),
        unit: 'total',
        icon: <AlertTriangle size={16} />,
        color: errorCount > 0 ? 'error' as const : 'success' as const,
        metricName: 'ishin.request.errors',
        showAsRate: true,
      },
      {
        label: 'Rate Limited',
        value: formatNumber(rateLimitRejects),
        unit: 'rejected',
        icon: <AlertTriangle size={16} />,
        color: rateLimitRejects > 0 ? 'warning' as const : 'success' as const,
        metricName: 'ishin.ratelimit.total',
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
        const pointDate = new Date(parseApiTimestamp(r.timestamp || r.bucket_ts));
        return {
          time: pointDate.toLocaleTimeString('pt-BR', {
            hour: '2-digit',
            minute: '2-digit',
          }),
          timestamp: pointDate.getTime(),
          value: round((r.avg ?? r.val_avg ?? r.value ?? 0) as number),
        };
      });

      // Para counters, computa taxa real por segundo usando o delta temporal
      if (meta.showAsRate && points.length > 1) {
        const ratePoints: SparklinePoint[] = [];
        for (let i = 1; i < points.length; i++) {
          const delta = points[i].value - points[i - 1].value;
          const elapsedSeconds = (points[i].timestamp - points[i - 1].timestamp) / 1000;
          ratePoints.push({
            time: points[i].time,
            timestamp: points[i].timestamp,
            value: elapsedSeconds > 0 ? Math.max(0, round(delta / elapsedSeconds)) : 0,
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
  const expandedMeta = expandedCard ? CARD_META[expandedCard] : undefined;

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
                    type="number"
                    dataKey="timestamp"
                    domain={['dataMin', 'dataMax']}
                    scale="time"
                    stroke="#5C5F66"
                    fontSize={9}
                    fontFamily="var(--font-mono)"
                    tickLine={false}
                    axisLine={false}
                    interval="preserveStartEnd"
                    minTickGap={24}
                    tickFormatter={(value) => formatSparklineTick(Number(value))}
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
                    labelFormatter={(value) => formatSparklineTooltipLabel(Number(value))}
                    // eslint-disable-next-line @typescript-eslint/no-explicit-any
                    formatter={((value: number | string) => [
                      formatSparklineValue(Number(value), expandedMeta?.sparkUnit),
                      expandedMeta?.showAsRate ? 'Taxa' : 'Valor',
                    ]) as any}
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

function round(n: number): number {
  return Math.round(n * 100) / 100;
}

function formatSparklineTick(timestamp: number): string {
  return new Date(timestamp).toLocaleTimeString('pt-BR', {
    hour: '2-digit',
    minute: '2-digit',
  });
}

function formatSparklineTooltipLabel(timestamp: number): string {
  return new Date(timestamp).toLocaleString('pt-BR', {
    day: '2-digit',
    month: '2-digit',
    hour: '2-digit',
    minute: '2-digit',
    second: '2-digit',
  });
}

function formatSparklineValue(value: number, unit?: string): string {
  const rounded = round(value);
  const formatted = rounded.toLocaleString('pt-BR', {
    minimumFractionDigits: Number.isInteger(rounded) ? 0 : 1,
    maximumFractionDigits: 2,
  });
  return unit ? `${formatted} ${unit}` : formatted;
}

function parseApiTimestamp(value: unknown): number {
  if (typeof value === 'number') {
    return value < 1_000_000_000_000 ? value * 1000 : value;
  }

  if (typeof value === 'string') {
    const numericValue = Number(value);
    if (Number.isFinite(numericValue)) {
      return numericValue < 1_000_000_000_000 ? numericValue * 1000 : numericValue;
    }

    const parsed = Date.parse(value);
    if (Number.isFinite(parsed)) {
      return parsed;
    }
  }

  return Date.now();
}
