export function formatDate(isoString: string | null | undefined): string {
  if (!isoString) return '—';
  try {
    const d = new Date(isoString);
    if (isNaN(d.getTime())) return isoString;
    return d.toISOString().replace('T', ' ').substring(0, 19);
  } catch {
    return isoString;
  }
}

export function formatTimeRelative(isoString: string | null | undefined): string {
  if (!isoString) return '—';
  try {
    const d = new Date(isoString);
    const now = new Date();
    const diffSec = Math.floor((now.getTime() - d.getTime()) / 1000);

    if (diffSec < 5) return 'just now';
    if (diffSec < 60) return `${diffSec}s ago`;
    if (diffSec < 3600) return `${Math.floor(diffSec / 60)}m ago`;
    if (diffSec < 86400) return `${Math.floor(diffSec / 3600)}h ago`;
    return `${Math.floor(diffSec / 86400)}d ago`;
  } catch {
    return isoString;
  }
}

export function formatDuration(durationStr: string | null | undefined): string {
  if (!durationStr) return '—';
  // Standard ISO-8601 duration string: PT30S, PT5M, PT2H, etc.
  return durationStr.replace('PT', '').toLowerCase();
}

export function truncateId(id: string | null | undefined, head: number = 8, tail: number = 4): string {
  if (!id) return '—';
  if (id.length <= head + tail + 3) return id;
  return `${id.substring(0, head)}...${id.substring(id.length - tail)}`;
}
