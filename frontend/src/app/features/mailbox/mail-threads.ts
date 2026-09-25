import { MessageView } from '../../core/api/models';

export interface MailThread {
  rootId: number;
  /** Newest message of the thread (list preview). */
  latest: MessageView;
  /** Subject of the first message. */
  subject: string;
  count: number;
  unreadIds: number[];
  villageLife: boolean;
  formLink: string | null;
}

/** Groups the flat mail list (newest first) into threads by `threadRootId`, newest thread first. */
export function groupThreads(mails: MessageView[]): MailThread[] {
  const byRoot = new Map<number, MessageView[]>();
  for (const m of mails) {
    const root = m.threadRootId ?? m.id;
    byRoot.set(root, [...(byRoot.get(root) ?? []), m]);
  }
  return [...byRoot.entries()]
    .map(([rootId, msgs]) => {
      const sorted = [...msgs].sort((a, b) => b.gameTime - a.gameTime || b.id - a.id);
      const first = sorted[sorted.length - 1];
      return {
        rootId,
        latest: sorted[0],
        subject: first.subject ?? sorted[0].subject ?? '',
        count: msgs.length,
        unreadIds: msgs.filter((m) => !m.read && m.initiatedBy === 'CHARACTER').map((m) => m.id),
        villageLife: msgs.some((m) => m.category === 'VILLAGE_LIFE'),
        formLink: sorted.find((m) => m.formLink)?.formLink ?? null,
      };
    })
    .sort((a, b) => b.latest.gameTime - a.latest.gameTime || b.latest.id - a.latest.id);
}
