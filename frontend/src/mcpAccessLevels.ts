/**
 * MCP Agent 的连接访问档位。
 *
 * <p>此前 MCP 工具一律只读，界面上「连接白名单」是一份扁平的 id 列表。分档之后授权变成
 * 「连接 → 档位」，同一个 Agent 可以在开发库上有完全权限、在生产库上只读。</p>
 *
 * <p>这里只做建模：档位如何取值、只读连接为什么不能给写档位、保存前该提示什么。真正的拦截
 * 全部在服务端 —— 界面的作用是让管理员看清自己正在授予什么，而不是充当安全边界。</p>
 */
import type { McpAccessLevel, McpAgentGrant, McpConnectionOption } from './types';

export const MCP_ACCESS_LEVELS: McpAccessLevel[] = ['READ_ONLY', 'DATA_WRITE', 'FULL'];

export const MCP_ACCESS_LEVEL_LABELS: Record<McpAccessLevel, string> = {
  READ_ONLY: '只读',
  DATA_WRITE: '数据读写',
  FULL: '完全'
};

export const MCP_ACCESS_LEVEL_HINTS: Record<McpAccessLevel, string> = {
  READ_ONLY: '查看结构、浏览表、执行 SELECT 与 EXPLAIN',
  DATA_WRITE: '只读能力，外加 INSERT / UPDATE / DELETE',
  FULL: '数据读写能力，外加 CREATE / ALTER / DROP 等结构变更'
};

export function mcpAccessLevelColor(level: McpAccessLevel): string | undefined {
  if (level === 'FULL') return 'red';
  if (level === 'DATA_WRITE') return 'orange';
  return undefined;
}

/** 只读连接上的写档位没有意义：服务端执行时一定会拒绝，让它可选只会造成「授权已生效」的错觉。 */
export function allowedLevelsFor(connection: McpConnectionOption | undefined): McpAccessLevel[] {
  return connection?.readonly ? ['READ_ONLY'] : MCP_ACCESS_LEVELS;
}

/**
 * 把表单里的「选中的连接」和「每条连接的档位」合成提交给服务端的授权列表。
 *
 * <p>没有显式选过档位的连接落到只读；只读连接一律强制为只读，避免刚勾上连接、还没来得及
 * 改档位时提交出一个服务端必然拒绝的组合。</p>
 */
export function buildAgentGrants(
  connectionIds: number[],
  levels: Record<number, McpAccessLevel> | undefined,
  connections: Map<number, McpConnectionOption>
): McpAgentGrant[] {
  return connectionIds.map((connectionId) => {
    const requested = levels?.[connectionId] ?? 'READ_ONLY';
    const allowed = allowedLevelsFor(connections.get(connectionId));
    return {
      connectionId,
      accessLevel: allowed.includes(requested) ? requested : 'READ_ONLY'
    };
  });
}

/** 把服务端返回的授权列表拆回表单需要的两份状态。 */
export function grantsToFormValues(grants: McpAgentGrant[]): {
  connectionIds: number[];
  levels: Record<number, McpAccessLevel>;
} {
  const levels: Record<number, McpAccessLevel> = {};
  for (const grant of grants) levels[grant.connectionId] = grant.accessLevel;
  return { connectionIds: grants.map((grant) => grant.connectionId), levels };
}

/**
 * 保存前需要管理员二次确认的理由。
 *
 * <p>生产连接一直都要确认。写档位是新增的一类：把写能力交给一个自动化 Agent，和交给一个人
 * 不是一回事 —— 它不会在按下回车前停顿。所以这两件事都要在确认框里被点名。</p>
 */
export function agentGrantWarnings(
  grants: McpAgentGrant[],
  connections: Map<number, McpConnectionOption>
): { production: string[]; writable: string[] } {
  const production: string[] = [];
  const writable: string[] = [];
  for (const grant of grants) {
    const connection = connections.get(grant.connectionId);
    const name = connection?.name || `#${grant.connectionId}`;
    if (connection?.environment === 'prod') production.push(name);
    if (grant.accessLevel !== 'READ_ONLY') {
      writable.push(`${name}（${MCP_ACCESS_LEVEL_LABELS[grant.accessLevel]}）`);
    }
  }
  return { production, writable };
}
