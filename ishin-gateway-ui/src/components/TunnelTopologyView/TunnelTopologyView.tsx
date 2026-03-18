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
import { Network, Server, Radio } from 'lucide-react';
import type { TopologyData, MetricData } from '../../types';
import './TunnelTopologyView.css';

interface Props {
  data: TopologyData | null;
  loading: boolean;
  metrics: Record<string, MetricData>;
}

interface TunnelNodeData extends Record<string, unknown> {
  kind: string;
  label: string;
  meta: string;
  status?: string;
  chips: { label: string; value: string }[];
}

type TunnelFlowNode = Node<TunnelNodeData, 'tunnel-node'>;

const NODE_WIDTH = 260;
const NODE_HEIGHT_TUNNEL = 80;
const NODE_HEIGHT_VP = 90;
const NODE_HEIGHT_MEMBER = 70;
const COL_GAP = 120;
const ROW_GAP = 28;
const TOP_PAD = 60;
const LAYOUT_KEY = 'ngate.tunnel-topo.positions';

const nodeTypes = { 'tunnel-node': TunnelFlowNode_Component };

export function TunnelTopologyView({ data, loading, metrics }: Props) {
  const rfRef = useRef<ReactFlowInstance<TunnelFlowNode, Edge> | null>(null);
  const [overrides, setOverrides] = useState<Record<string, XYPosition>>(() => loadPositions());
  const [flowNodes, setFlowNodes, onNodesChange] = useNodesState<TunnelFlowNode>([]);

  const { nodes, edges } = useMemo(() => {
    if (!data) return { nodes: [] as TunnelFlowNode[], edges: [] as Edge[] };

    const fn: TunnelFlowNode[] = [];
    const fe: Edge[] = [];

    const tunnelNodes = data.nodes.filter(n => n.type === 'tunnel');
    const vpNodes = data.nodes.filter(n => n.type === 'virtual-port');
    const memberNodes = data.nodes.filter(n => n.type === 'tunnel-member');

    const col0X = 48;
    const col1X = col0X + NODE_WIDTH + COL_GAP;
    const col2X = col1X + NODE_WIDTH + COL_GAP;

    // Tunnel node (center)
    tunnelNodes.forEach((tn, i) => {
      fn.push(mkNode(tn.id, resolvePos(overrides, tn.id, { x: col1X, y: TOP_PAD + i * (NODE_HEIGHT_TUNNEL + ROW_GAP) }), {
        kind: 'tunnel', label: tn.label, meta: tn.mode ?? 'tunnel', chips: [],
      }));
    });

    // Virtual ports (left)
    vpNodes.forEach((vp, i) => {
      fn.push(mkNode(vp.id, resolvePos(overrides, vp.id, { x: col0X, y: TOP_PAD + i * (NODE_HEIGHT_VP + ROW_GAP) }), {
        kind: 'virtual-port', label: vp.label,
        meta: `${vp.activeMembers ?? 0}A / ${vp.standbyMembers ?? 0}S / ${vp.drainingMembers ?? 0}D`,
        status: vp.listenerOpen ? 'open' : 'closed',
        chips: [{ label: 'port', value: String(vp.port ?? '?') }],
      }));
    });

    // Members (right)
    memberNodes.forEach((m, i) => {
      fn.push(mkNode(m.id, resolvePos(overrides, m.id, { x: col2X, y: TOP_PAD + i * (NODE_HEIGHT_MEMBER + ROW_GAP) }), {
        kind: 'tunnel-member', label: m.label,
        meta: `${m.host ?? '?'}:${m.realPort ?? '?'}`,
        status: m.status?.toLowerCase(),
        chips: [
          { label: 'conn', value: String(m.activeConnections ?? 0) },
          { label: 'wt', value: String(m.weight ?? 0) },
          { label: 'ka', value: `${m.keepaliveAgeSeconds ?? 0}s` },
        ],
      }));
    });

    // Edges
    data.edges.forEach((e, i) => {
      fe.push({
        id: `te-${i}`,
        source: e.source,
        target: e.target,
        animated: true,
        selectable: false,
        style: { stroke: '#748FFC', strokeWidth: 1.8, opacity: 0.7 },
        markerEnd: { type: MarkerType.ArrowClosed, color: '#748FFC', width: 14, height: 14 },
      });
    });

    return { nodes: fn, edges: fe };
  }, [data, overrides, metrics]);

  useEffect(() => { setFlowNodes(nodes); }, [nodes, setFlowNodes]);
  useEffect(() => { persistPositions(overrides); }, [overrides]);

  const manual = Object.keys(overrides).length > 0;

  if (loading) return <div className="tunnel-topo-loading"><div className="topology-spinner" /><span>Carregando topologia...</span></div>;
  if (!data) return <div className="tunnel-topo-empty"><span>Sem dados de topologia</span></div>;

  return (
    <div className="tunnel-topo-container">
      <div className="tunnel-topo-header">
        <h3>Topologia L4 — Tunnel</h3>
        <div className="tunnel-topo-controls">
          {manual && <span className="badge badge-accent">Layout manual</span>}
          {data.cluster?.enabled && <span className="badge badge-success">Cluster: {data.cluster.clusterName}</span>}
          <button type="button" className="topology-layout-btn" disabled={!manual}
            onClick={() => { setOverrides({}); requestAnimationFrame(() => rfRef.current?.fitView({ padding: 0.14, duration: 240 })); }}>
            Reset layout
          </button>
        </div>
      </div>
      <div className="tunnel-topo-flow">
        <ReactFlow
          nodes={flowNodes} edges={edges} onNodesChange={onNodesChange}
          onNodeDragStop={(_, node) => setOverrides(cur => ({ ...cur, [node.id]: { x: Math.round(node.position.x), y: Math.round(node.position.y) } }))}
          nodeTypes={nodeTypes} onInit={inst => { rfRef.current = inst; }}
          fitView fitViewOptions={{ padding: 0.14 }} panOnDrag zoomOnScroll zoomOnPinch
          nodesDraggable nodesConnectable={false} elementsSelectable={false}
          minZoom={0.45} maxZoom={2} proOptions={{ hideAttribution: true }}
        >
          <Background color="#2C2E33" gap={20} />
        </ReactFlow>
      </div>
    </div>
  );
}

function TunnelFlowNode_Component({ data }: NodeProps<TunnelFlowNode>) {
  const Icon = data.kind === 'tunnel' ? Server : data.kind === 'virtual-port' ? Radio : Network;
  const statusClass = data.status ? `tn-status-${data.status}` : '';

  return (
    <div className={`tn-node tn-${data.kind} ${statusClass}`}>
      <Handle type="target" position={Position.Left} isConnectable={false} className="topo-handle" />
      <div className="tn-node-body">
        <div className="tn-node-head">
          <span className="tn-node-icon"><Icon size={14} /></span>
          <div className="tn-node-content">
            <span className="tn-node-label">{data.label}</span>
            <span className="tn-node-meta">{data.meta}</span>
          </div>
        </div>
        {data.chips.length > 0 && (
          <div className="tn-node-chips">
            {data.chips.map(c => (
              <span key={c.label} className="tn-chip"><strong>{c.label}</strong> {c.value}</span>
            ))}
          </div>
        )}
      </div>
      <Handle type="source" position={Position.Right} isConnectable={false} className="topo-handle" />
    </div>
  );
}

function mkNode(id: string, position: XYPosition, data: TunnelNodeData): TunnelFlowNode {
  return { id, type: 'tunnel-node', position, sourcePosition: Position.Right, targetPosition: Position.Left, draggable: true, selectable: false, data };
}

function resolvePos(overrides: Record<string, XYPosition>, id: string, fallback: XYPosition): XYPosition {
  return overrides[id] ?? fallback;
}

function loadPositions(): Record<string, XYPosition> {
  try {
    const raw = window.localStorage.getItem(LAYOUT_KEY);
    return raw ? JSON.parse(raw) : {};
  } catch { return {}; }
}

function persistPositions(pos: Record<string, XYPosition>) {
  if (Object.keys(pos).length === 0) { window.localStorage.removeItem(LAYOUT_KEY); return; }
  window.localStorage.setItem(LAYOUT_KEY, JSON.stringify(pos));
}
