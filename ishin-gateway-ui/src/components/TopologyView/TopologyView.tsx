import { useMemo, useEffect, useRef, useState } from 'react';
import {
  ReactFlow,
  Background,
  Handle,
  Position,
  MarkerType,
  useNodesState,
  type Node,
  type Edge,
  type NodeProps,
  type XYPosition,
  type ReactFlowInstance,
} from '@xyflow/react';
import '@xyflow/react/dist/style.css';
import {
  Globe,
  Server,
  Database,
  Shield,
  Route,
  Code2,
} from 'lucide-react';
import type { TopologyData, TopologyNode, TopologyEdge, MetricData } from '../../types';
import './TopologyView.css';

interface Props {
  data: TopologyData | null;
  loading: boolean;
  metrics: Record<string, MetricData>;
}

type TopologyNodeKind = TopologyNode['type'];
type RateUnit = 'req/s' | 'exec/s';

interface TopologyChip {
  label: string;
  value: string;
}

interface TopologyNodeData extends Record<string, unknown> {
  kind: TopologyNodeKind;
  label: string;
  meta: string;
  path?: string;
  rate: number;
  rateUnit: RateUnit;
  chips: TopologyChip[];
  hasOauth?: boolean;
}

type TopologyFlowNodeType = Node<TopologyNodeData, 'topology'>;

interface CounterTotals {
  global: number;
  listeners: Record<string, number>;
  backends: Record<string, number>;
  contexts: Record<string, number>;
  scripts: Record<string, number>;
}

interface TimerAccumulator {
  count: number;
  totalTime: number;
  max?: number;
  min?: number;
}

interface MetricSnapshot {
  counters: CounterTotals;
  contextTiming: Record<string, TimerAccumulator>;
  scriptTiming: Record<string, TimerAccumulator>;
}

interface TopologyRates extends CounterTotals {}

interface TimingSummary {
  count: number;
  avg?: number;
  min?: number;
  max?: number;
}

type NodePositionOverrides = Record<string, XYPosition>;

const EMPTY_COUNTER_TOTALS: CounterTotals = {
  global: 0,
  listeners: {},
  backends: {},
  contexts: {},
  scripts: {},
};

const EMPTY_RATES: TopologyRates = {
  ...EMPTY_COUNTER_TOTALS,
};

const LISTENER_NODE_WIDTH = 240;
const CONTEXT_NODE_WIDTH = 300;
const SCRIPT_NODE_WIDTH = 280;
const CORE_NODE_WIDTH = 240;
const LISTENER_NODE_HEIGHT = 88;
const CONTEXT_NODE_HEIGHT = 118;
const SCRIPT_NODE_HEIGHT = 94;
const GATEWAY_NODE_HEIGHT = 92;
const BACKEND_NODE_HEIGHT = 88;
const COLUMN_GAP = 104;
const TOP_PADDING = 72;
const CONTEXT_ROW_GAP = 24;
const SCRIPT_ROW_GAP = 14;
const GROUP_GAP = 44;
const BACKEND_ROW_GAP = 44;
const TOPOLOGY_LAYOUT_STORAGE_KEY = 'ngate.topology.layout.positions';

const nodeTypes = {
  topology: TopologyFlowNode,
};

export function TopologyView({ data, loading, metrics }: Props) {
  const snapshot = useMemo(() => collectMetricSnapshot(metrics), [metrics]);
  const previousCountersRef = useRef<{ timestamp: number; counters: CounterTotals } | null>(null);
  const reactFlowRef = useRef<ReactFlowInstance<TopologyFlowNodeType, Edge> | null>(null);
  const [rates, setRates] = useState<TopologyRates>(EMPTY_RATES);
  const [positionOverrides, setPositionOverrides] = useState<NodePositionOverrides>(() => loadStoredNodePositions());
  const [flowNodes, setFlowNodes, onNodesChange] = useNodesState<TopologyFlowNodeType>([]);

  useEffect(() => {
    const now = Date.now();
    const previous = previousCountersRef.current;

    if (previous) {
      const elapsedSeconds = (now - previous.timestamp) / 1000;
      setRates(calculateRates(previous.counters, snapshot.counters, elapsedSeconds));
    }

    previousCountersRef.current = { timestamp: now, counters: snapshot.counters };
  }, [snapshot.counters]);

  const timingSummary = useMemo(() => ({
    contexts: summarizeTiming(snapshot.contextTiming),
    scripts: summarizeTiming(snapshot.scriptTiming),
  }), [snapshot.contextTiming, snapshot.scriptTiming]);

  useEffect(() => {
    if (!data) return;

    const activeNodeIds = new Set(data.nodes.map((node) => node.id));
    setPositionOverrides((current) => prunePositionOverrides(current, activeNodeIds));
  }, [data]);

  useEffect(() => {
    persistNodePositions(positionOverrides);
  }, [positionOverrides]);

  const { nodes, edges } = useMemo(() => {
    if (!data) return { nodes: [] as TopologyFlowNodeType[], edges: [] as Edge[] };

    const flowNodes: TopologyFlowNodeType[] = [];
    const flowEdges: Edge[] = [];

    const listeners = data.nodes.filter((node) => node.type === 'listener');
    const gateways = data.nodes.filter((node) => node.type === 'gateway');
    const backends = data.nodes.filter((node) => node.type === 'backend');
    const contexts = data.nodes.filter((node) => node.type === 'context');
    const scripts = data.nodes.filter((node) => node.type === 'script');

    const contextsByListener = new Map<string, TopologyNode[]>();
    const scriptsByContext = new Map<string, TopologyNode[]>();
    const contextKeyById = new Map<string, string>();
    const scriptKeyById = new Map<string, string>();

    contexts.forEach((contextNode) => {
      const listenerName = contextNode.listener;
      if (!listenerName) return;

      const current = contextsByListener.get(listenerName) ?? [];
      current.push(contextNode);
      contextsByListener.set(listenerName, current);

      contextKeyById.set(contextNode.id, buildContextMetricKey(listenerName, contextNode.label));
    });

    scripts.forEach((scriptNode) => {
      const contextId = scriptNode.context;
      if (!contextId) return;

      const current = scriptsByContext.get(contextId) ?? [];
      current.push(scriptNode);
      scriptsByContext.set(contextId, current);
    });

    const hasContextNodes = contexts.length > 0;
    const hasScriptNodes = scripts.length > 0;
    let nextColumnX = 48;

    const listenerX = nextColumnX;
    nextColumnX += LISTENER_NODE_WIDTH + COLUMN_GAP;

    const contextX = nextColumnX;
    if (hasContextNodes) {
      nextColumnX += CONTEXT_NODE_WIDTH + COLUMN_GAP;
    }

    const scriptX = nextColumnX;
    if (hasScriptNodes) {
      nextColumnX += SCRIPT_NODE_WIDTH + COLUMN_GAP;
    }

    const gatewayX = nextColumnX;
    const backendX = gatewayX + CORE_NODE_WIDTH + COLUMN_GAP;

    let cursorY = TOP_PADDING;

    listeners.forEach((listenerNode) => {
      const listenerName = listenerNode.label;
      const listenerContexts = contextsByListener.get(listenerName) ?? [];

      if (listenerContexts.length === 0) {
        flowNodes.push(createFlowNode(
          listenerNode.id,
          resolveNodePosition(positionOverrides, listenerNode.id, { x: listenerX, y: cursorY }),
          {
            kind: 'listener',
            label: listenerNode.label,
            meta: formatListenerMeta(listenerNode),
            rate: rates.listeners[listenerName] ?? 0,
            rateUnit: 'req/s',
            chips: [],
          }
        ));
        cursorY += LISTENER_NODE_HEIGHT + GROUP_GAP;
        return;
      }

      const groupStartY = cursorY;

      listenerContexts.forEach((contextNode) => {
        const contextKey = buildContextMetricKey(listenerName, contextNode.label);
        const contextRate = rates.contexts[contextKey] ?? 0;
        const contextStats = timingSummary.contexts[contextKey];
        const contextY = cursorY;

        flowNodes.push(createFlowNode(
          contextNode.id,
          resolveNodePosition(positionOverrides, contextNode.id, { x: contextX, y: contextY }),
          {
            kind: 'context',
            label: contextNode.label,
            meta: `${contextNode.method ?? 'ANY'} • ${contextNode.contextPath ?? '--'}`,
            path: formatContextPathLine(contextNode),
            rate: contextRate,
            rateUnit: 'req/s',
            chips: buildTimingChips(contextStats),
          }
        ));

        const relatedScripts = scriptsByContext.get(contextNode.id) ?? [];
        const scriptStartY = contextY + CONTEXT_NODE_HEIGHT + SCRIPT_ROW_GAP;
        relatedScripts.forEach((scriptNode, scriptIndex) => {
          const scriptKey = buildScriptMetricKey(listenerName, contextNode.label, scriptNode.script ?? scriptNode.label);
          scriptKeyById.set(scriptNode.id, scriptKey);

          flowNodes.push(createFlowNode(
            scriptNode.id,
            resolveNodePosition(
              positionOverrides,
              scriptNode.id,
              { x: scriptX, y: scriptStartY + scriptIndex * (SCRIPT_NODE_HEIGHT + SCRIPT_ROW_GAP) }
            ),
            {
              kind: 'script',
              label: shortenScriptLabel(scriptNode.script ?? scriptNode.label),
              meta: `ctx ${contextNode.label}`,
              path: scriptNode.script ?? scriptNode.label,
              rate: rates.scripts[scriptKey] ?? 0,
              rateUnit: 'exec/s',
              chips: buildTimingChips(timingSummary.scripts[scriptKey]),
            }
          ));
        });

        const scriptBlockHeight = relatedScripts.length > 0
          ? relatedScripts.length * SCRIPT_NODE_HEIGHT + (relatedScripts.length - 1) * SCRIPT_ROW_GAP
          : 0;
        const contextBlockHeight = CONTEXT_NODE_HEIGHT
          + (scriptBlockHeight > 0 ? SCRIPT_ROW_GAP + scriptBlockHeight : 0);

        cursorY += contextBlockHeight + CONTEXT_ROW_GAP;
      });

      const groupHeight = Math.max(0, cursorY - groupStartY - CONTEXT_ROW_GAP);
      const listenerY = groupStartY + Math.max(0, (groupHeight - LISTENER_NODE_HEIGHT) / 2);

      flowNodes.push(createFlowNode(
        listenerNode.id,
        resolveNodePosition(positionOverrides, listenerNode.id, { x: listenerX, y: listenerY }),
        {
          kind: 'listener',
          label: listenerNode.label,
          meta: formatListenerMeta(listenerNode),
          rate: rates.listeners[listenerName] ?? 0,
          rateUnit: 'req/s',
          chips: [],
        }
      ));

      cursorY += GROUP_GAP - CONTEXT_ROW_GAP;
    });

    const backendStackHeight = backends.length > 0
      ? BACKEND_NODE_HEIGHT + Math.max(backends.length - 1, 0) * (BACKEND_NODE_HEIGHT + BACKEND_ROW_GAP)
      : 0;
    const contentBottom = Math.max(cursorY, TOP_PADDING + backendStackHeight);
    const gatewayY = TOP_PADDING + Math.max(0, (contentBottom - TOP_PADDING - GATEWAY_NODE_HEIGHT) / 2);

    gateways.forEach((gatewayNode, index) => {
      flowNodes.push(createFlowNode(
        gatewayNode.id,
        resolveNodePosition(
          positionOverrides,
          gatewayNode.id,
          { x: gatewayX, y: gatewayY + index * (GATEWAY_NODE_HEIGHT + BACKEND_ROW_GAP) }
        ),
        {
          kind: 'gateway',
          label: gatewayNode.label,
          meta: gatewayNode.mode || 'proxy',
          rate: rates.global,
          rateUnit: 'req/s',
          chips: [],
        }
      ));
    });

    const backendStartY = gatewayY - Math.max(0, ((backends.length - 1) * (BACKEND_NODE_HEIGHT + BACKEND_ROW_GAP)) / 2);
    backends.forEach((backendNode, index) => {
      flowNodes.push(createFlowNode(
        backendNode.id,
        resolveNodePosition(
          positionOverrides,
          backendNode.id,
          { x: backendX, y: backendStartY + index * (BACKEND_NODE_HEIGHT + BACKEND_ROW_GAP) }
        ),
        {
          kind: 'backend',
          label: backendNode.label,
          meta: `${backendNode.members ?? 0} member${backendNode.members !== 1 ? 's' : ''}${backendNode.hasOauth ? ' • OAuth' : ''}`,
          rate: rates.backends[backendNode.label] ?? 0,
          rateUnit: 'req/s',
          chips: [],
          hasOauth: backendNode.hasOauth,
        }
      ));
    });

    const listenersWithContexts = new Set(
      contexts
        .map((contextNode) => contextNode.listener)
        .filter((listenerName): listenerName is string => typeof listenerName === 'string' && listenerName.length > 0)
    );

    data.edges.forEach((edge, index) => {
      if (
        edge.type === 'inbound' &&
        edge.source.startsWith('listener:') &&
        listenersWithContexts.has(edge.source.replace('listener:', ''))
      ) {
        return;
      }

      const edgeRate = resolveEdgeRate(edge, rates, contextKeyById, scriptKeyById);
      const edgeColor = edgeColorForType(edge.type);

      flowEdges.push({
        id: `edge-${index}`,
        source: edge.source,
        target: edge.target,
        animated: edgeRate > 0.05,
        selectable: false,
        style: {
          stroke: edgeColor,
          strokeWidth: edgeStrokeWidth(edgeRate, edge.type),
          opacity: edgeRate > 0 ? 0.95 : 0.28,
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
  }, [data, positionOverrides, rates, timingSummary]);

  useEffect(() => {
    setFlowNodes(nodes);
  }, [nodes, setFlowNodes]);

  const manualLayoutActive = Object.keys(positionOverrides).length > 0;

  function handleResetLayout() {
    setPositionOverrides({});

    if (typeof window !== 'undefined') {
      window.requestAnimationFrame(() => {
        window.requestAnimationFrame(() => {
          reactFlowRef.current?.fitView({ padding: 0.14, duration: 240 });
        });
      });
    }
  }

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
        <div className="topology-controls">
          <div className="topology-badges">
            {manualLayoutActive && <span className="badge badge-accent">Layout manual</span>}
            {data.circuitBreaker?.enabled && <span className="badge badge-accent">CB</span>}
            {data.rateLimiting?.enabled && <span className="badge badge-warning">RL</span>}
            {data.cluster?.enabled && (
              <span className="badge badge-success">Cluster: {data.cluster.clusterName}</span>
            )}
          </div>
          <button
            type="button"
            className="topology-layout-btn"
            onClick={handleResetLayout}
            disabled={!manualLayoutActive}
          >
            Reset layout
          </button>
        </div>
      </div>
      <div className="topology-flow">
        <ReactFlow
          nodes={flowNodes}
          edges={edges}
          onNodesChange={onNodesChange}
          onNodeDragStop={(_, node) => {
            setPositionOverrides((current) => updateNodePosition(current, node.id, node.position));
          }}
          nodeTypes={nodeTypes}
          onInit={(instance) => {
            reactFlowRef.current = instance;
          }}
          fitView
          fitViewOptions={{ padding: 0.14 }}
          panOnDrag
          zoomOnScroll
          zoomOnPinch
          nodesDraggable
          nodesConnectable={false}
          elementsSelectable={false}
          minZoom={0.45}
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
  const Icon = getNodeIcon(data.kind, Boolean(data.hasOauth));
  const rateTone = getRateTone(data.rate);

  return (
    <div className={`topo-node topo-${data.kind} topo-rate-${rateTone}`}>
      <Handle type="target" position={Position.Left} isConnectable={false} className="topo-handle" />
      <div className="topo-node-body">
        <div className="topo-node-head">
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
            <span className="topo-node-rate-label">{data.rateUnit}</span>
          </div>
        </div>
        {data.path && <div className="topo-node-path mono">{data.path}</div>}
        {data.chips.length > 0 && (
          <div className="topo-node-chips">
            {data.chips.map((chip) => (
              <span key={`${chip.label}-${chip.value}`} className="topo-node-chip">
                <strong>{chip.label}</strong> {chip.value}
              </span>
            ))}
          </div>
        )}
      </div>
      <Handle type="source" position={Position.Right} isConnectable={false} className="topo-handle" />
    </div>
  );
}

function createFlowNode(
  id: string,
  position: { x: number; y: number },
  data: TopologyNodeData
): TopologyFlowNodeType {
  return {
    id,
    type: 'topology',
    position,
    sourcePosition: Position.Right,
    targetPosition: Position.Left,
    draggable: true,
    selectable: false,
    data,
  };
}

function loadStoredNodePositions(): NodePositionOverrides {
  if (typeof window === 'undefined') return {};

  try {
    const raw = window.localStorage.getItem(TOPOLOGY_LAYOUT_STORAGE_KEY);
    if (!raw) return {};

    const parsed = JSON.parse(raw);
    if (!parsed || typeof parsed !== 'object') return {};

    return Object.fromEntries(
      Object.entries(parsed).filter((entry): entry is [string, XYPosition] => {
        const value = entry[1] as Partial<XYPosition> | null;
        return typeof value === 'object'
          && value !== null
          && typeof value.x === 'number'
          && typeof value.y === 'number';
      })
    );
  } catch {
    return {};
  }
}

function persistNodePositions(positionOverrides: NodePositionOverrides) {
  if (typeof window === 'undefined') return;

  if (Object.keys(positionOverrides).length === 0) {
    window.localStorage.removeItem(TOPOLOGY_LAYOUT_STORAGE_KEY);
    return;
  }

  window.localStorage.setItem(TOPOLOGY_LAYOUT_STORAGE_KEY, JSON.stringify(positionOverrides));
}

function prunePositionOverrides(
  current: NodePositionOverrides,
  activeNodeIds: Set<string>
): NodePositionOverrides {
  const next = Object.fromEntries(
    Object.entries(current).filter(([nodeId]) => activeNodeIds.has(nodeId))
  );

  return Object.keys(next).length === Object.keys(current).length ? current : next;
}

function resolveNodePosition(
  positionOverrides: NodePositionOverrides,
  nodeId: string,
  fallback: XYPosition
): XYPosition {
  return positionOverrides[nodeId] ?? fallback;
}

function updateNodePosition(
  current: NodePositionOverrides,
  nodeId: string,
  position: XYPosition
): NodePositionOverrides {
  const previous = current[nodeId];
  if (previous && previous.x === position.x && previous.y === position.y) {
    return current;
  }

  return {
    ...current,
    [nodeId]: {
      x: round(position.x),
      y: round(position.y),
    },
  };
}

function collectMetricSnapshot(metrics: Record<string, MetricData>): MetricSnapshot {
  const counters: CounterTotals = {
    global: 0,
    listeners: {},
    backends: {},
    contexts: {},
    scripts: {},
  };

  const contextTiming: Record<string, TimerAccumulator> = {};
  const scriptTiming: Record<string, TimerAccumulator> = {};

  for (const [key, metric] of Object.entries(metrics)) {
    if (key.startsWith('ishin.requests.total') && metric.value !== undefined) {
      counters.global += metric.value;

      const listener = metric.tags?.listener;
      if (listener) {
        counters.listeners[listener] = (counters.listeners[listener] ?? 0) + metric.value;
      }
      continue;
    }

    if (key.startsWith('ishin.upstream.requests') && metric.value !== undefined) {
      const backend = metric.tags?.backend;
      if (backend) {
        counters.backends[backend] = (counters.backends[backend] ?? 0) + metric.value;
      }
      continue;
    }

    if (key.startsWith('ishin.context.requests.total') && metric.value !== undefined) {
      const listener = metric.tags?.listener;
      const contextName = metric.tags?.context;
      if (listener && contextName) {
        const contextKey = buildContextMetricKey(listener, contextName);
        counters.contexts[contextKey] = (counters.contexts[contextKey] ?? 0) + metric.value;
      }
      continue;
    }

    if (key.startsWith('ishin.script.executions.total') && metric.value !== undefined) {
      const listener = metric.tags?.listener;
      const contextName = metric.tags?.context;
      const script = metric.tags?.script;
      if (listener && contextName && script) {
        const scriptKey = buildScriptMetricKey(listener, contextName, script);
        counters.scripts[scriptKey] = (counters.scripts[scriptKey] ?? 0) + metric.value;
      }
      continue;
    }

    if (key.startsWith('ishin.context.duration')) {
      const listener = metric.tags?.listener;
      const contextName = metric.tags?.context;
      if (listener && contextName) {
        accumulateTimer(contextTiming, buildContextMetricKey(listener, contextName), metric);
      }
      continue;
    }

    if (key.startsWith('ishin.script.duration')) {
      const listener = metric.tags?.listener;
      const contextName = metric.tags?.context;
      const script = metric.tags?.script;
      if (listener && contextName && script) {
        accumulateTimer(scriptTiming, buildScriptMetricKey(listener, contextName, script), metric);
      }
    }
  }

  return { counters, contextTiming, scriptTiming };
}

function accumulateTimer(
  registry: Record<string, TimerAccumulator>,
  metricKey: string,
  metric: MetricData
) {
  const current = registry[metricKey] ?? { count: 0, totalTime: 0 };
  const count = metric.count ?? 0;
  const totalTime = metric.totalTime ?? ((metric.mean ?? 0) * count);

  current.count += count;
  current.totalTime += totalTime;

  if (metric.max !== undefined) {
    current.max = current.max === undefined ? metric.max : Math.max(current.max, metric.max);
  }

  if (metric.min !== undefined) {
    current.min = current.min === undefined ? metric.min : Math.min(current.min, metric.min);
  }

  registry[metricKey] = current;
}

function summarizeTiming(registry: Record<string, TimerAccumulator>): Record<string, TimingSummary> {
  return Object.fromEntries(
    Object.entries(registry).map(([metricKey, value]) => {
      const avg = value.count > 0 ? value.totalTime / value.count : undefined;
      return [metricKey, {
        count: value.count,
        avg,
        min: value.min,
        max: value.max,
      }];
    })
  );
}

function calculateRates(previous: CounterTotals, current: CounterTotals, elapsedSeconds: number): TopologyRates {
  if (elapsedSeconds <= 0) return EMPTY_RATES;

  return {
    global: rateDelta(previous.global, current.global, elapsedSeconds),
    listeners: calculateTaggedRates(previous.listeners, current.listeners, elapsedSeconds),
    backends: calculateTaggedRates(previous.backends, current.backends, elapsedSeconds),
    contexts: calculateTaggedRates(previous.contexts, current.contexts, elapsedSeconds),
    scripts: calculateTaggedRates(previous.scripts, current.scripts, elapsedSeconds),
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

function buildTimingChips(summary?: TimingSummary): TopologyChip[] {
  return [
    { label: 'avg', value: formatLatency(summary?.avg) },
    { label: 'min', value: formatLatency(summary?.min) },
    { label: 'max', value: formatLatency(summary?.max) },
  ];
}

function resolveEdgeRate(
  edge: TopologyEdge,
  rates: TopologyRates,
  contextKeyById: Map<string, string>,
  scriptKeyById: Map<string, string>
): number {
  if (edge.type === 'inbound') {
    const listenerName = edge.source.replace('listener:', '');
    return rates.listeners[listenerName] ?? 0;
  }

  if (edge.type === 'upstream') {
    const backendName = edge.target.replace('backend:', '');
    return rates.backends[backendName] ?? 0;
  }

  if (edge.type === 'context' || edge.type === 'inbound-context') {
    const contextKey = contextKeyById.get(edge.type === 'context' ? edge.target : edge.source);
    return contextKey ? (rates.contexts[contextKey] ?? 0) : 0;
  }

  if (edge.type === 'script-exec') {
    const scriptKey = scriptKeyById.get(edge.target);
    return scriptKey ? (rates.scripts[scriptKey] ?? 0) : 0;
  }

  return 0;
}

function edgeColorForType(type: TopologyEdge['type']): string {
  if (type === 'script-exec') return '#FCC419';
  if (type === 'inbound-context') return '#51CF66';
  if (type === 'context') return '#A5D8FF';
  if (type === 'upstream') return '#A5D8FF';
  return '#748FFC';
}

function edgeStrokeWidth(rate: number, type: TopologyEdge['type']): number {
  if (type === 'script-exec') {
    if (rate >= 50) return 4.25;
    if (rate >= 10) return 3.5;
    if (rate >= 1) return 2.75;
    return rate > 0 ? 2.2 : 1.8;
  }

  if (rate >= 50) return 5.5;
  if (rate >= 10) return 4.5;
  if (rate >= 1) return 3.25;
  if (rate > 0) return 2.5;
  return 2;
}

function getNodeIcon(kind: TopologyNodeKind, hasOauth: boolean) {
  if (kind === 'listener') return Globe;
  if (kind === 'gateway') return Shield;
  if (kind === 'context') return Route;
  if (kind === 'script') return Code2;
  return hasOauth ? Server : Database;
}

function formatListenerMeta(listener: TopologyNode): string {
  return `:${listener.port ?? '-'}${listener.ssl ? ' • TLS' : ''}${listener.secured ? ' • Secured' : ''}`;
}

function formatContextPathLine(contextNode: TopologyNode): string | undefined {
  const mode = contextNode.secured === true ? 'secured' : contextNode.secured === false ? 'open' : undefined;
  const script = contextNode.ruleMapping && contextNode.ruleMapping.trim().length > 0 ? 'Groovy' : undefined;

  const parts = [mode, script].filter((value): value is string => Boolean(value));
  return parts.length > 0 ? parts.join(' • ') : undefined;
}

function shortenScriptLabel(script: string): string {
  const normalized = script.replaceAll('\\', '/');
  const segments = normalized.split('/');
  return segments[segments.length - 1] || script;
}

function buildContextMetricKey(listener: string, contextName: string): string {
  return `${listener}::${contextName}`;
}

function buildScriptMetricKey(listener: string, contextName: string, script: string): string {
  return `${listener}::${contextName}::${script}`;
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

function formatLatency(value?: number): string {
  if (value === undefined || Number.isNaN(value)) return '--';
  if (value > 0 && value < 1) return '<1ms';
  if (value >= 1000) return `${(value / 1000).toFixed(2)}s`;
  return `${round(value)}ms`;
}

function round(value: number): number {
  return Math.round(value * 10) / 10;
}
