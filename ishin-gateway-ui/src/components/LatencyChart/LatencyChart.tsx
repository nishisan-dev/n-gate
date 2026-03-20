import { useState, useEffect, useMemo } from 'react';
import {
  AreaChart,
  Area,
  XAxis,
  YAxis,
  CartesianGrid,
  Tooltip,
  ResponsiveContainer,
  Legend,
} from 'recharts';
import { TrendingUp, Clock } from 'lucide-react';
import { api } from '../../api';
import './LatencyChart.css';

interface RRDPoint {
  time: string;
  timestamp: number;
  min: number;
  avg: number;
  max: number;
  count: number;
}

interface Props {
  metricName?: string;
  title?: string;
  unit?: string;
  hours?: number;
}

const TIME_RANGES = [
  { label: '1h', hours: 1 },
  { label: '6h', hours: 6 },
  { label: '24h', hours: 24 },
  { label: '7d', hours: 168 },
  { label: '30d', hours: 720 },
];

export function LatencyChart({
  metricName = 'ishin.request.duration',
  title = 'Latência',
  unit = 'ms',
  hours: defaultHours = 1,
}: Props) {
  const [data, setData] = useState<RRDPoint[]>([]);
  const [selectedRange, setSelectedRange] = useState(defaultHours);
  const [loading, setLoading] = useState(true);
  const [tier, setTier] = useState<string>('raw');

  useEffect(() => {
    const fetchHistory = async () => {
      setLoading(true);
      try {
        const now = new Date();
        const from = new Date(now.getTime() - selectedRange * 3600 * 1000);
        const response = await api.getMetricHistory(
          metricName,
          from.toISOString(),
          now.toISOString()
        );

        // New RRD response format: { tier, points, data: [...] }
        const records = response?.data || response || [];
        setTier(response?.tier || 'raw');

        const timeFormat: Intl.DateTimeFormatOptions =
          selectedRange <= 24
            ? { hour: '2-digit', minute: '2-digit' }
            : selectedRange <= 168
              ? { month: '2-digit', day: '2-digit', hour: '2-digit' }
              : { month: '2-digit', day: '2-digit' };

        const chartData: RRDPoint[] = records.map(
          // eslint-disable-next-line @typescript-eslint/no-explicit-any
          (r: any) => {
            const timestamp = parseApiTimestamp(r.timestamp || r.bucket_ts);
            return {
              time: new Date(timestamp).toLocaleString('pt-BR', timeFormat),
              timestamp,
              min: round(r.min ?? r.val_min ?? r.value ?? 0),
              avg: round(r.avg ?? r.val_avg ?? r.value ?? 0),
              max: round(r.max ?? r.val_max ?? r.value ?? 0),
              count: r.count ?? r.val_count ?? 1,
            };
          }
        );

        setData(chartData);
      } catch {
        setData([]);
      } finally {
        setLoading(false);
      }
    };

    fetchHistory();
    const refreshMs = selectedRange <= 6 ? 15000 : 60000;
    const id = setInterval(fetchHistory, refreshMs);
    return () => clearInterval(id);
  }, [metricName, selectedRange]);

  const stats = useMemo(() => {
    if (data.length === 0) return { avg: 0, max: 0, min: 0, current: 0 };
    return {
      avg: round(data.reduce((a, b) => a + b.avg, 0) / data.length),
      max: round(Math.max(...data.map((d) => d.max))),
      min: round(Math.min(...data.map((d) => d.min))),
      current: round(data[data.length - 1].avg),
    };
  }, [data]);

  return (
    <div className="latency-chart">
      <div className="chart-header">
        <div className="chart-title-group">
          <TrendingUp size={14} />
          <h3>{title}</h3>
          <span className="tier-badge mono">{tier}</span>
        </div>
        <div className="chart-controls">
          <div className="chart-stats">
            <span className="chart-stat">
              avg: <strong>{stats.avg}{unit}</strong>
            </span>
            <span className="chart-stat">
              max: <strong className="text-warning">{stats.max}{unit}</strong>
            </span>
            <span className="chart-stat">
              cur: <strong className="text-accent">{stats.current}{unit}</strong>
            </span>
          </div>
          <div className="time-range-selector">
            {TIME_RANGES.map((range) => (
              <button
                key={range.hours}
                className={`time-range-btn ${selectedRange === range.hours ? 'active' : ''}`}
                onClick={() => setSelectedRange(range.hours)}
              >
                {range.label}
              </button>
            ))}
          </div>
        </div>
      </div>

      <div className="chart-body">
        {loading && data.length === 0 ? (
          <div className="chart-loading">
            <Clock size={16} className="subtle-pulse" />
            <span>Carregando dados...</span>
          </div>
        ) : data.length === 0 ? (
          <div className="chart-empty">
            <span>Sem dados históricos para &quot;{metricName}&quot;</span>
          </div>
        ) : (
          <ResponsiveContainer width="100%" height={220}>
            <AreaChart data={data} margin={{ top: 5, right: 10, left: -20, bottom: 0 }}>
              <defs>
                {/* Gradient: avg area fill */}
                <linearGradient id={`avgGrad-${metricName}`} x1="0" y1="0" x2="0" y2="1">
                  <stop offset="5%" stopColor="#748FFC" stopOpacity={0.25} />
                  <stop offset="95%" stopColor="#748FFC" stopOpacity={0} />
                </linearGradient>
                {/* Gradient: min-max band fill */}
                <linearGradient id={`bandGrad-${metricName}`} x1="0" y1="0" x2="0" y2="1">
                  <stop offset="5%" stopColor="#A5D8FF" stopOpacity={0.12} />
                  <stop offset="95%" stopColor="#A5D8FF" stopOpacity={0.02} />
                </linearGradient>
              </defs>
              <CartesianGrid
                strokeDasharray="3 3"
                stroke="rgba(255,255,255,0.04)"
                vertical={false}
              />
              <XAxis
                dataKey="time"
                stroke="#5C5F66"
                fontSize={10}
                fontFamily="var(--font-mono)"
                tickLine={false}
                axisLine={false}
                interval="preserveStartEnd"
              />
              <YAxis
                stroke="#5C5F66"
                fontSize={10}
                fontFamily="var(--font-mono)"
                tickLine={false}
                axisLine={false}
                tickFormatter={(v) => `${v}${unit}`}
              />
              <Tooltip
                contentStyle={{
                  background: '#25262B',
                  border: '1px solid rgba(255,255,255,0.1)',
                  borderRadius: '8px',
                  fontSize: '11px',
                  fontFamily: 'var(--font-mono)',
                  color: '#C1C2C5',
                }}
                labelStyle={{ color: '#909296' }}
                // eslint-disable-next-line @typescript-eslint/no-explicit-any
                formatter={((value: any, name: any) => {
                  const labels: Record<string, string> = {
                    max: 'Máx',
                    avg: 'Média',
                    min: 'Mín',
                  };
                  return [`${value}${unit}`, labels[name] || name];
                }) as any}
              />
              <Legend
                wrapperStyle={{
                  fontSize: '10px',
                  fontFamily: 'var(--font-mono)',
                  color: '#909296',
                  paddingTop: '4px',
                }}
              />
              {/* Max line (top of band) */}
              <Area
                type="monotone"
                dataKey="max"
                stroke="#FF6B6B"
                strokeWidth={1}
                strokeDasharray="4 2"
                fill={`url(#bandGrad-${metricName})`}
                dot={false}
                activeDot={{ r: 3, fill: '#FF6B6B', strokeWidth: 0 }}
                name="max"
              />
              {/* Avg line (main) */}
              <Area
                type="monotone"
                dataKey="avg"
                stroke="#748FFC"
                strokeWidth={2}
                fill={`url(#avgGrad-${metricName})`}
                dot={false}
                activeDot={{
                  r: 4,
                  fill: '#748FFC',
                  stroke: '#1A1B1E',
                  strokeWidth: 2,
                }}
                name="avg"
              />
              {/* Min line (bottom of band) */}
              <Area
                type="monotone"
                dataKey="min"
                stroke="#51CF66"
                strokeWidth={1}
                strokeDasharray="4 2"
                fill="none"
                dot={false}
                activeDot={{ r: 3, fill: '#51CF66', strokeWidth: 0 }}
                name="min"
              />
            </AreaChart>
          </ResponsiveContainer>
        )}
      </div>
    </div>
  );
}

function round(n: number): number {
  return Math.round(n * 100) / 100;
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
