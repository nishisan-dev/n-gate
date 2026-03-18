import { LatencyChart } from '../LatencyChart/LatencyChart';
import './TunnelChartsPanel.css';

export function TunnelChartsPanel() {
  return (
    <div className="tunnel-charts-panel">
      <LatencyChart
        metricName="ishin.tunnel.session.duration"
        title="Session Duration"
      />
      <LatencyChart
        metricName="ishin.tunnel.connection.duration"
        title="TCP Connect Duration"
      />
      <LatencyChart
        metricName="ishin.tunnel.routing.duration.seconds"
        title="Internal Routing Duration"
      />
    </div>
  );
}
