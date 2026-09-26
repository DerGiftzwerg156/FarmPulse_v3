import { Component, computed, effect, inject, signal, untracked } from '@angular/core';
import { RouterLink } from '@angular/router';
import { Observable, catchError, forkJoin, of } from 'rxjs';
import { ApiService } from '../../core/api/api.service';
import { DiaryView, LoanView, MessageView, NegotiationView, ReputationView, StorageOverview } from '../../core/api/models';
import { TranslatePipe } from '../../core/i18n/translate.pipe';
import { TranslationService } from '../../core/i18n/translation.service';
import { GameStateStore } from '../../core/state/game-state.store';
import { formatMoney } from '../../shared/format/format';
import { GameTimePipe, MoneyPipe, NumberPipe } from '../../shared/format/format.pipes';
import { LabelPipe } from '../../shared/format/label.pipe';
import { Badge } from '../../shared/ui/badge';
import { Card } from '../../shared/ui/card';
import { Icon } from '../../shared/ui/icon';
import { Stat } from '../../shared/ui/stat';
import { NoticesCard } from './notices-card';

export interface FeedItem {
  key: string;
  kind: 'mail' | 'call' | 'diary';
  icon: string;
  title: string;
  subtitle: string;
  gameTime: number;
  link: string;
  query: Record<string, number>;
  unread: boolean;
}

/** Merges mails, calls and diary entries into one "latest events" feed (newest first). */
export function buildFeed(mails: MessageView[], calls: MessageView[], diary: DiaryView[], limit = 8): FeedItem[] {
  const items: FeedItem[] = [
    ...mails
      .filter((m) => m.initiatedBy === 'CHARACTER')
      .map((m) => ({
        key: `m${m.id}`, kind: 'mail' as const, icon: 'mail', title: m.subject ?? '', subtitle: m.character?.name ?? '',
        gameTime: m.gameTime, link: '/mailbox', query: { id: m.id }, unread: !m.read,
      })),
    ...calls
      .filter((c) => c.callStatus !== null)
      .map((c) => ({
        key: `c${c.id}`, kind: 'call' as const, icon: 'phone', title: c.subject ?? '', subtitle: c.character?.name ?? '',
        gameTime: c.gameTime, link: '/calls', query: { id: c.id }, unread: c.callStatus === 'RINGING' || c.callStatus === 'MISSED',
      })),
    ...diary.map((d) => ({
      key: `d${d.id}`, kind: 'diary' as const, icon: 'book', title: d.title, subtitle: d.text ?? '', gameTime: d.gameTime,
      link: '/diary', query: {}, unread: false,
    })),
  ];
  return items.sort((a, b) => b.gameTime - a.gameTime).slice(0, limit);
}

/** Dashboard home (AP-8.10): pure composition of existing endpoints in the look of the design reference. */
@Component({
  selector: 'app-home',
  imports: [RouterLink, TranslatePipe, LabelPipe, MoneyPipe, NumberPipe, GameTimePipe, Stat, Card, Badge, Icon, NoticesCard],
  templateUrl: './home.html',
})
export class Home {
  private readonly api = inject(ApiService);
  readonly store = inject(GameStateStore);
  private readonly i18n = inject(TranslationService);

  readonly mails = signal<MessageView[]>([]);
  readonly calls = signal<MessageView[]>([]);
  readonly diary = signal<DiaryView[]>([]);
  readonly loans = signal<LoanView[]>([]);
  readonly negotiations = signal<NegotiationView[]>([]);
  readonly reputation = signal<ReputationView | null>(null);
  readonly storage = signal<StorageOverview | null>(null);
  readonly loaded = signal(false);

  readonly feed = computed(() => buildFeed(this.mails(), this.calls(), this.diary()));
  readonly unreadMails = computed(() => this.mails().filter((m) => !m.read && m.initiatedBy === 'CHARACTER').slice(0, 3));
  readonly activeLoans = computed(() => this.loans().filter((l) => l.status === 'ACTIVE'));
  readonly debt = computed(() => this.activeLoans().reduce((s, l) => s + l.remainingAmount, 0));
  readonly overdue = computed(() => this.activeLoans().some((l) => l.overdue));
  readonly openNegotiations = computed(() => this.negotiations().filter((n) => n.status === 'OPEN'));
  readonly ringing = computed(() => this.calls().filter((c) => c.callStatus === 'RINGING').length);

  constructor() {
    effect(() => {
      this.store.mailVersion();
      this.store.callVersion();
      this.store.diaryVersion();
      this.store.stateVersion();
      const sg = this.store.savegame();
      if (sg) untracked(() => this.load());
    });
  }

  load(): void {
    const safe = <T>(o: Observable<T>, fallback: T) => o.pipe(catchError(() => of(fallback)));
    forkJoin({
      mails: safe(this.api.mails(), [] as MessageView[]),
      calls: safe(this.api.callLog(), [] as MessageView[]),
      diary: safe(this.api.diary(), [] as DiaryView[]),
      loans: safe(this.api.loans(), [] as LoanView[]),
      negotiations: safe(this.api.negotiations(), [] as NegotiationView[]),
      reputation: safe(this.api.reputation(), null as ReputationView | null),
      storage: safe(this.api.storage(), null as StorageOverview | null),
    }).subscribe((r) => {
      this.mails.set(r.mails);
      this.calls.set(r.calls);
      this.diary.set(r.diary);
      this.loans.set(r.loans);
      this.negotiations.set(r.negotiations);
      this.reputation.set(r.reputation);
      this.storage.set(r.storage);
      this.loaded.set(true);
    });
  }

  debtHint(): string {
    return this.activeLoans().length ? this.i18n.t('home.debtHint', { amount: formatMoney(this.debt()) }) : this.i18n.t('home.noLoans');
  }

  fill(amount: number, capacity: number): number {
    return capacity > 0 ? Math.min(100, (amount / capacity) * 100) : 0;
  }
}
