import { useMemo, useEffect, useRef, useState } from 'react';
import {
  ReactFlow,
  Background,
  Handle,
  Position,
  MarkerType,
  type Node,
  type Edge,
  type NodeProps,
} from '@xyflow/react';
import '@xyflow/react/dist/style.css';
import { Globe, Server, Database, Shield } from 'lucide-react';
import type { TopologyData, MetricData } from '../../types';
import './TopologyView.css';

interface Props {
  data: TopologyData | null;
  loading: boolean;
  metrics: Record<string, MetricData>;
}

type TopologyNodeKind = 'listener' | 'gateway' | 'backend';

interface TopologyNodeData extends Record<string, unknown> {
  kind: TopologyNodeKind;
  label: string;
  meta: string;
  rate: number;
}

type TopologyFlowNodeType = Node<TopologyNodeData, 'topology'>;

interface TopologyTotals {
  global: number;
  listeners: Record<string, number>;
  backends: Record<string, number>;
}

interface TopologyRates extends TopologyTotals {}

const EMPTY_RATES: TopologyRates = {
  global: 0,
  listeners: {},
  backends: {},
};

const nodeTypes = {
  topology: TopologyFlowNode,
};

export function TopologyView({ data, loading, metrics }: Props) {
  const totals = useMemo(() => collectTopologyTotals(metrics), [metrics]);
  const previousTotalsRef = useRef<{ timestamp: number; totals: TopologyTotals } | null>(null);
  const [rates, setRates] = useState<TopologyRates>(EMPTY_RATES);

  useEffect(() => {
    const now = Date.now();
    const previous = previousTotalsRef.current;

    if (previous) {
      const elapsedSeconds = (now - previous.timestamp) / 1000;
      setRates(calculateRates(previous.totals, totals, elapsedSeconds));
    }

    previousTotalsRef.current = { timestamp: now, totals };
  }, [totals]);

  const { nodes, edges } = useMemo(() => {
    if (!data) return { nodes: [] as TopologyFlowNodeType[], edges: [] as Edge[] };

    const flowNodes: TopologyFlowNodeType[] = [];
    const flowEdges: Edge[] = [];

    const listeners = data.nodes.filter((node) => node.type === 'listener');
    const gateways = data.nodes.filter((node) => node.type === 'gateway');
    const backends = data.nodes.filter((node) => node.type === 'backend');

    listeners.forEach((node, index) => {
      flowNodes.push({
        id: node.id,
        type: 'topology',
        position: { x: 60, y: 80 + index * 132 },
        sourcePosition: Position.Right,
        targetPosition: Position.Left,
        draggable: false,
        selectable: false,
        data: {
          kind: 'listener',
          label: node.label,
          meta: `:${node.port ?? '-'}${node.ssl ? ' • TLS' : ''}${node.secured ? ' • Secured' : ''}`,
          rate: rates.listeners[node.label] ?? 0,
        },
      });
    });

    gateways.forEach((node) => {
      flowNodes.push({
        id: node.id,
        type: 'topology',
        position: { x: 375, y: 80 + Math.max(0, (Math.max(listeners.length, backends.length) - 1) * 66) },
        sourcePosition: Position.Right,
        targetPosition: Position.Left,
        draggable: false,
        selectable: false,
        data: {
          kind: 'gateway',
          label: node.label,
          meta: node.mode || 'proxy',
          rate: rates.global,
        },
      });
    });

    backends.forEach((node, index) => {
      flowNodes.push({
        id: node.id,
        type: 'topology',
        position: { x: 690, y: 80 + index * 132 },
        sourcePosition: Position.Right,
        targetPosition: Position.Left,
        draggable: false,
        selectable: false,
        data: {
          kind: 'backend',
          label: node.label,
          meta: `${node.members ?? 0} member${node.members !== 1 ? 's' : ''}${node.hasOauth ? ' • OAuth' : ''}`,
          rate: rates.backends[node.label] ?? 0,
        },
      });
    });

    data.edges.forEach((edge, index) => {
      const edgeRate = resolveEdgeRate(edge, rates);
      const edgeColor = edge.type === 'inbound' ? '#748FFC' : '#A5D8FF';

      flowEdges.push({
        id: `edge-${index}`,
        source: edge.source,
        target: edge.target,
        animated: edgeRate > 0.05,
        selectable: false,
        style: {
          stroke: edgeColor,
          strokeWidth: edgeStrokeWidth(edgeRate),
          opacity: edgeRate > 0 ? 0.95 : 0.35,
        },
        markerEnd: {
          type: MarkerType.ArrowClosed,
          color: edgeColor,
          width: 16,
          height: 16,
        },
      });
    });

    return { nodes: flowNodes, edges: flowEdges };
  }, [data, rates]);

  if (loading) {
    return (
      <div className="topology-loading">
        <div className="topology-spinner" />
        <span>Carregando topologia...</span>
      </div>
    );
  }

  if (!data) {
    return (
      <div className="topology-empty">
        <span>Sem dados de topologia</span>
      </div>
    );
  }

  return (
    <div className="topology-container">
      <div className="topology-header">
        <h3>Topologia</h3>
        <div className="topology-badges">
          {data.circuitBreaker?.enabled && <span className="badge badge-accent">CB</span>}
          {data.rateLimiting?.enabled && <span className="badge badge-warning">RL</span>}
          {data.cluster?.enabled && (
            <span className="badge badge-success">Cluster: {data.cluster.clusterName}</span>
          )}
        </div>
      </div>
      <div className="topology-flow">
        <ReactFlow
          nodes={nodes}
          edges={edges}
          nodeTypes={nodeTypes}
          fitView
          panOnDrag
          zoomOnScroll
          zoomOnPinch
          nodesDraggable={false}
          nodesConnectable={false}
          elementsSelectable={false}
          minZoom={0.5}
          maxZoom={2}
          proOptions={{ hideAttribution: true }}
        >
          <Background color="#2C2E33" gap={20} />
        </ReactFlow>
      </div>
    </div>
  );
}

function TopologyFlowNode({ data }: NodeProps<TopologyFlowNodeType>) {
  const Icon = getNodeIcon(data.kind, data.meta.includes('OAuth'));
  const rateTone = getRateTone(data.rate);

  return (
    <div className={`topo-node topo-${data.kind} topo-rate-${rateTone}`}>
      <Handle type="target" position={Position.Left} isConnectable={false} className="topo-handle" />
      <div className="topo-node-main">
        <span className="topo-node-icon">
          <Icon size={data.kind === 'gateway' ? 18 : 14} />
        </span>
        <div className="topo-node-content">
          <span className="topo-node-label">{data.label}</span>
          <span className="topo-node-meta">{data.meta}</span>
        </div>
      </div>
      <div className="topo-node-rate">
        <span className="topo-node-rate-value mono">{formatRate(data.rate)}</span>
        <span className="topo-node-rate-label">req/s</span>
      </div>
      <Handle type="source" position={Position.Right} isConnectable={false} className="topo-handle" />
    </div>
  );
}

function getNodeIcon(kind: TopologyNodeKind, hasOauth: boolean) {
  if (kind === 'listener') return Globe;
  if (kind === 'gateway') return Shield;
  return hasOauth ? Server : Database;
}

function collectTopologyTotals(metrics: Record<string, MetricData>): TopologyTotals {
  const listeners: Record<string, number> = {};
  const backends: Record<string, number> = {};
  let global = 0;

  for (const [key, metric] of Object.entries(metrics)) {
    if (key.startsWith('ngate.requests.total') && metric.value !== undefined) {
      global += metric.value;

      const listener = metric.tags?.listener;
      if (listener) {
        listeners[listener] = (listeners[listener] ?? 0) + metric.value;
      }
    }

    if (key.startsWith('ngate.upstream.requests') && metric.value !== undefined) {
      const backend = metric.tags?.backend;
      if (backend) {
        backends[backend] = (backends[backend] ?? 0) + metric.value;
      }
    }
  }

  return { global, listeners, backends };
}

function calculateRates(previous: TopologyTotals, current: TopologyTotals, elapsedSeconds: number): TopologyRates {
  if (elapsedSeconds <= 0) {
    return EMPTY_RATES;
  }

  return {
    global: rateDelta(previous.global, current.global, elapsedSeconds),
    listeners: calculateTaggedRates(previous.listeners, current.listeners, elapsedSeconds),
    backends: calculateTaggedRates(previous.backends, current.backends, elapsedSeconds),
  };
}

function calculateTaggedRates(
  previous: Record<string, number>,
  current: Record<string, number>,
  elapsedSeconds: number
): Record<string, number> {
  const keys = new Set([...Object.keys(previous), ...Object.keys(current)]);
  const result: Record<string, number> = {};

  for (const key of keys) {
    result[key] = rateDelta(previous[key] ?? 0, current[key] ?? 0, elapsedSeconds);
  }

  return result;
}

function rateDelta(previous: number, current: number, elapsedSeconds: number): number {
  const delta = current - previous;
  if (delta <= 0 || elapsedSeconds <= 0) return 0;
  return round(delta / elapsedSeconds);
}

function resolveEdgeRate(edge: { source: string; target: string; type: string }, rates: TopologyRates): number {
  if (edge.type === 'inbound') {
    const listenerName = edge.source.replace('listener:', '');
    return rates.listeners[listenerName] ?? 0;
  }

  const backendName = edge.target.replace('backend:', '');
  return rates.backends[backendName] ?? 0;
}

function edgeStrokeWidth(rate: number): number {
  if (rate >= 50) return 5.5;
  if (rate >= 10) return 4.5;
  if (rate >= 1) return 3.25;
  if (rate > 0) return 2.5;
  return 2;
}

function getRateTone(rate: number): 'idle' | 'warm' | 'hot' | 'burst' {
  if (rate >= 50) return 'burst';
  if (rate >= 10) return 'hot';
  if (rate >= 1) return 'warm';
  return 'idle';
}

function formatRate(rate: number): string {
  if (rate > 0 && rate < 0.1) return '<0.1';
  return rate.toLocaleString('pt-BR', {
    minimumFractionDigits: rate >= 10 || Number.isInteger(rate) ? 0 : 1,
    maximumFractionDigits: 1,
  });
}

function round(value: number): number {
  return Math.round(value * 10) / 10;
}
