import { Component, computed, effect, inject, input, signal, untracked } from '@angular/core';
import { FormsModule } from '@angular/forms';
import { RouterLink } from '@angular/router';
import { PageError, apiErrorMessage, toPageError } from '../../core/api/api-error';
import { ApiService } from '../../core/api/api.service';
import { MessageView, ThreadView } from '../../core/api/models';
import { TranslatePipe } from '../../core/i18n/translate.pipe';
import { TranslationService } from '../../core/i18n/translation.service';
import { GameStateStore } from '../../core/state/game-state.store';
import { GameTimePipe } from '../../shared/format/format.pipes';
import { LabelPipe } from '../../shared/format/label.pipe';
import { splitLink } from '../../shared/format/link';
import { Badge } from '../../shared/ui/badge';
import { Button } from '../../shared/ui/button';
import { Card } from '../../shared/ui/card';
import { PageErrorView } from '../../shared/ui/page-error';
import { Icon } from '../../shared/ui/icon';
import { ListItem } from '../../shared/ui/list-item';
import { MailThread, groupThreads } from './mail-threads';

/**
 * Mailbox (AP-8.2): thread list with unread highlighting (live via SSE), thread detail, free reply - or, where the
 * concept requires a form (credit counter offer, bids, hiring, special offers), a link to that form instead.
 */
@Component({
  selector: 'app-mailbox',
  imports: [FormsModule, RouterLink, TranslatePipe, LabelPipe, GameTimePipe, Card, ListItem, Badge, Button, Icon, PageErrorView],
  templateUrl: './mailbox.html',
})
export class Mailbox {
  private readonly api = inject(ApiService);
  private readonly store = inject(GameStateStore);
  private readonly i18n = inject(TranslationService);

  /** `?id=` opens a mail directly (e.g. from the dashboard). */
  readonly id = input<string>();

  readonly mails = signal<MessageView[] | null>(null);
  readonly error = signal<PageError | null>(null);
  readonly filter = signal<'all' | 'unread'>('all');
  readonly openRoot = signal<number | null>(null);
  readonly thread = signal<ThreadView | null>(null);
  readonly replyText = signal('');
  readonly sending = signal(false);
  readonly replyError = signal<string | null>(null);

  readonly threads = computed(() => groupThreads(this.mails() ?? []));
  readonly visibleThreads = computed(() =>
    this.filter() === 'unread' ? this.threads().filter((t) => t.unreadIds.length > 0) : this.threads(),
  );
  readonly current = computed(() => this.threads().find((t) => t.rootId === this.openRoot()) ?? null);
  readonly formLink = computed(() => {
    const link = this.current()?.formLink ?? this.thread()?.thread.find((m) => m.formLink)?.formLink ?? null;
    return link ? splitLink(link) : null;
  });
  readonly canReply = computed(() => {
    const t = this.thread();
    const c = t?.message.character;
    return !!t && !!c && c.status !== 'TERMINATED' && !this.formLink();
  });

  constructor() {
    // Reload on every live mail event (and initially).
    effect(() => {
      this.store.mailVersion();
      untracked(() => this.load());
    });
    effect(() => {
      const id = Number(this.id());
      if (id) {
        untracked(() => this.openMessage(id));
      }
    });
  }

  load(): void {
    this.api.mails().subscribe({
      next: (m) => {
        this.mails.set(m);
        this.error.set(null);
        const root = this.openRoot();
        if (root !== null) {
          this.reloadThread(root);
        }
      },
      error: (e) => this.error.set(toPageError(e, this.i18n.t('common.error'))),
    });
  }

  open(t: MailThread): void {
    this.openRoot.set(t.rootId);
    this.replyText.set('');
    this.replyError.set(null);
    this.thread.set(null);
    this.reloadThread(t.rootId);
  }

  openMessage(id: number): void {
    this.api.mail(id).subscribe({
      next: (tv) => {
        this.openRoot.set(tv.message.threadRootId ?? tv.message.id);
        this.thread.set(tv);
        this.markRead([tv.message.id]);
      },
      error: (e) => this.error.set(toPageError(e, this.i18n.t('common.error'))),
    });
  }

  close(): void {
    this.openRoot.set(null);
    this.thread.set(null);
  }

  private reloadThread(rootId: number): void {
    const t = this.threads().find((x) => x.rootId === rootId);
    const target = t?.unreadIds[0] ?? t?.latest.id ?? rootId;
    this.api.mail(target).subscribe({
      next: (tv) => {
        if (this.openRoot() !== rootId) return;
        this.thread.set(tv);
        const others = (t?.unreadIds ?? []).filter((id) => id !== target);
        // Opening marks only the fetched mail as read server-side; fetch further unread ones of the thread too.
        others.forEach((id) => this.api.mail(id).subscribe());
        if (t && t.unreadIds.length > 0) this.markRead(t.unreadIds);
      },
      error: (e) => this.error.set(toPageError(e, this.i18n.t('common.error'))),
    });
  }

  private markRead(ids: number[]): void {
    this.mails.update((list) => list?.map((m) => (ids.includes(m.id) ? { ...m, read: true } : m)) ?? list);
    this.store.refresh();
  }

  send(): void {
    const t = this.thread();
    const text = this.replyText().trim();
    if (!t || !text) return;
    this.sending.set(true);
    this.replyError.set(null);
    this.api.reply(t.message.id, text).subscribe({
      next: (own) => {
        this.sending.set(false);
        this.replyText.set('');
        this.thread.update((tv) => (tv ? { ...tv, thread: [...tv.thread, own] } : tv));
        this.mails.update((list) => (list ? [own, ...list] : [own]));
      },
      error: (e) => {
        this.sending.set(false);
        this.replyError.set(apiErrorMessage(e, this.i18n.t('common.error')));
      },
    });
  }

  sender(m: MessageView): string {
    return m.initiatedBy === 'PLAYER' ? this.i18n.t('mailbox.me') : (m.character?.name ?? this.i18n.t('mailbox.system'));
  }
}
