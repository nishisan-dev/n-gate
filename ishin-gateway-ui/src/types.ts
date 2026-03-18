// Tipos compartilhados para a UI do dashboard n-gate

export interface TopologyNode {
  id: string;
  type: 'gateway' | 'listener' | 'backend' | 'context' | 'script' | 'tunnel' | 'virtual-port' | 'tunnel-member';
  label: string;
  mode?: string;
  port?: number;
  ssl?: boolean | null;
  secured?: boolean | null;
  members?: number;
  hasOauth?: boolean;
  listener?: string;
  contextPath?: string;
  method?: string;
  ruleMapping?: string | null;
  script?: string;
  context?: string;
  // Tunnel specific
  listenerOpen?: boolean;
  activeMembers?: number;
  standbyMembers?: number;
  drainingMembers?: number;
  nodeId?: string;
  host?: string;
  realPort?: number;
  status?: string;
  weight?: number;
  activeConnections?: number;
  keepaliveAgeSeconds?: number;
}

export interface TopologyEdge {
  source: string;
  target: string;
  type: 'inbound' | 'upstream' | 'context' | 'inbound-context' | 'script-exec' | 'tunnel-link';
  listener?: string;
}

export interface TopologyData {
  nodes: TopologyNode[];
  edges: TopologyEdge[];
  mode: string;
  cluster?: {
    enabled: boolean;
    nodeId: string;
    clusterName: string;
  };
  circuitBreaker?: { enabled: boolean };
  rateLimiting?: { enabled: boolean };
}

export interface MetricData {
  type: 'counter' | 'timer' | 'gauge' | 'summary';
  tags?: Record<string, string>;
  value?: number;
  count?: number;
  min?: number;
  mean?: number;
  max?: number;
  totalTime?: number;
}

export interface MetricSnapshot {
  timestamp: string;
  name: string;
  tagsJson: string;
  value: number;
}

export interface EventLog {
  timestamp: string;
  eventType: string;
  source: string;
  detailsJson: string;
}

export interface HealthInfo {
  status: string;
  timestamp: string;
  mode: string;
  version: string;
  uptime: string;
  uptimeMs: number;
  listeners: number;
  backends: number;
  // Tunnel-specific
  virtualListeners?: number;
  tunnelGroups?: number;
  tunnelMembers?: number;
  activeConnections?: number;
  dashboardCapabilities?: string[];
}

// ─── Tunnel Runtime Types ─────────────────────────────────────────

export interface TunnelMemberSnapshot {
  backendKey: string;
  nodeId: string;
  host: string;
  realPort: number;
  status: string;
  weight: number;
  activeConnections: number;
  keepaliveAgeSeconds: number;
}

export interface VirtualPortSnapshot {
  virtualPort: number;
  listenerOpen: boolean;
  activeMembers: number;
  standbyMembers: number;
  drainingMembers: number;
  members: TunnelMemberSnapshot[];
}

export interface TunnelRuntimeSnapshot {
  mode: string;
  listeners: number;
  groups: number;
  members: number;
  activeConnections: number;
  virtualPorts: VirtualPortSnapshot[];
}
