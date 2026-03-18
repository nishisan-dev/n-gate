import type { TunnelRuntimeSnapshot, TunnelMemberSnapshot } from '../../types';
import './TunnelMembersPanel.css';

interface Props {
  runtime: TunnelRuntimeSnapshot | null;
}

export function TunnelMembersPanel({ runtime }: Props) {
  if (!runtime || runtime.virtualPorts.length === 0) {
    return (
      <div className="tunnel-members-empty">
        <span>Nenhum member registrado</span>
      </div>
    );
  }

  return (
    <div className="tunnel-members-panel">
      {runtime.virtualPorts.map(vp => (
        <div key={vp.virtualPort} className="tunnel-vport-section">
          <div className="tunnel-vport-header">
            <span className="tunnel-vport-label">vPort:{vp.virtualPort}</span>
            <div className="tunnel-vport-badges">
              <span className={`tunnel-badge ${vp.listenerOpen ? 'tunnel-badge-success' : 'tunnel-badge-error'}`}>
                {vp.listenerOpen ? 'LISTENING' : 'CLOSED'}
              </span>
              <span className="tunnel-badge tunnel-badge-info">
                {vp.activeMembers}A / {vp.standbyMembers}S / {vp.drainingMembers}D
              </span>
            </div>
          </div>

          <div className="tunnel-members-table-wrapper">
            <table className="tunnel-members-table">
              <thead>
                <tr>
                  <th>Backend</th>
                  <th>Host</th>
                  <th>Status</th>
                  <th>Conn</th>
                  <th>Weight</th>
                  <th>KA Age</th>
                </tr>
              </thead>
              <tbody>
                {vp.members.map((member: TunnelMemberSnapshot) => (
                  <tr key={member.backendKey} className={`tunnel-member-row tunnel-member-${member.status.toLowerCase()}`}>
                    <td className="mono">{member.backendKey}</td>
                    <td className="mono">{member.host}:{member.realPort}</td>
                    <td>
                      <span className={`tunnel-status-chip tunnel-status-${member.status.toLowerCase()}`}>
                        {member.status}
                      </span>
                    </td>
                    <td className="mono">{member.activeConnections}</td>
                    <td className="mono">{member.weight}</td>
                    <td className="mono">{member.keepaliveAgeSeconds}s</td>
                  </tr>
                ))}
              </tbody>
            </table>
          </div>
        </div>
      ))}
    </div>
  );
}
