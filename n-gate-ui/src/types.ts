// Tipos compartilhados para a UI do dashboard n-gate

export interface TopologyNode {
  id: string;
  type: 'gateway' | 'listener' | 'backend';
  label: string;
  mode?: string;
  port?: number;
  ssl?: boolean;
  secured?: boolean;
  members?: number;
  hasOauth?: boolean;
}

export interface TopologyEdge {
  source: string;
  target: string;
  type: 'inbound' | 'upstream';
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
}
