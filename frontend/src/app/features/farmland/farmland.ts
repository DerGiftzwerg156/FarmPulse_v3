import { Component, computed, effect, inject, input, signal, untracked } from '@angular/core';
import { RouterLink } from '@angular/router';
import { Observable, forkJoin } from 'rxjs';
import { PageError, apiErrorMessage, toPageError } from '../../core/api/api-error';
import { ApiService } from '../../core/api/api.service';
import { FarmlandView, MessageView, NegotiationView } from '../../core/api/models';
import { TranslatePipe } from '../../core/i18n/translate.pipe';
import { TranslationService } from '../../core/i18n/translation.service';
import { GameStateStore } from '../../core/state/game-state.store';
import { GameTimePipe, MoneyPipe, NumberPipe } from '../../shared/format/format.pipes';
import { LabelPipe } from '../../shared/format/label.pipe';
import { Badge } from '../../shared/ui/badge';
import { Button } from '../../shared/ui/button';
import { Card } from '../../shared/ui/card';
import { PageErrorView } from '../../shared/ui/page-error';

/** Amount the counterpart currently offers/demands and that the player can accept with one click. */
export function acceptableAmount(n: NegotiationView): number | null {
  if (n.status !== 'OPEN' || n.roundsUsed >= n.maxRounds) return null;
  if (n.lastCounterOffer !== null) return n.lastCounterOffer;
  if (n.kind === 'SALE_OFFER') {
    const first = [...n.offers].reverse().find((o) => o.offeredBy === 'CHARACTER');
    return first?.amount ?? null;
  }
  return null;
}

/** Highest bid in an auction so far (NPC or player). */
export function highestBid(n: NegotiationView): number | null {
  return n.offers.length ? Math.max(...n.offers.map((o) => o.amount)) : null;
}

/**
 * Farmland & negotiation (AP-8.6): overview of all fields with owner, auctions/direct negotiations/sale offers with
 * a bid form (max. rounds visible), result and the AI narration (the related mails), selling own fields.
 */
@Component({
  selector: 'app-farmland',
  imports: [RouterLink, TranslatePipe, LabelPipe, MoneyPipe, NumberPipe, GameTimePipe, Card, Badge, Button, PageErrorView],
  templateUrl: './farmland.html',
})
export class Farmland {
  private readonly api = inject(ApiService);
  private readonly store = inject(GameStateStore);
  private readonly i18n = inject(TranslationService);

  /** `?negotiation=` selects a negotiation (link from mails / village). */
  readonly negotiation = input<string>();

  readonly fields = signal<FarmlandView[] | null>(null);
  readonly negotiations = signal<NegotiationView[] | null>(null);
  readonly mails = signal<MessageView[]>([]);
  readonly error = signal<PageError | null>(null);
  readonly selectedField = signal<number | null>(null);
  readonly selectedNegotiation = signal<number | null>(null);
  readonly amount = signal<number | null>(null);
  readonly askingPrice = signal<number | null>(null);
  readonly info = signal<string | null>(null);
  readonly actionError = signal<string | null>(null);
  readonly busy = signal(false);
  readonly showClosed = signal(false);

  readonly field = computed(() => this.fields()?.find((f) => f.farmlandId === this.selectedField()) ?? null);
  readonly open = computed(() => (this.negotiations() ?? []).filter((n) => n.status === 'OPEN'));
  readonly closed = computed(() => (this.negotiations() ?? []).filter((n) => n.status !== 'OPEN'));
  readonly current = computed(() => this.negotiations()?.find((n) => n.id === this.selectedNegotiation()) ?? null);
  readonly narration = computed(() => {
    const n = this.current();
    return n
      ? this.mails()
          .filter((m) => m.relatedEntityType === 'NEGOTIATION' && m.relatedEntityId === n.id)
          .sort((a, b) => a.gameTime - b.gameTime || a.id - b.id)
      : [];
  });
  readonly counts = computed(() => {
    const f = this.fields() ?? [];
    return { player: f.filter((x) => x.ownerType === 'PLAYER').length, total: f.length };
  });

  constructor() {
    effect(() => {
      this.store.stateVersion();
      this.store.mailVersion();
      untracked(() => this.load());
    });
    effect(() => {
      const id = Number(this.negotiation());
      if (id) untracked(() => this.selectNegotiation(id));
    });
  }

  load(): void {
    forkJoin({ fields: this.api.farmlands(), negotiations: this.api.negotiations(), mails: this.api.mails() }).subscribe({
      next: ({ fields, negotiations, mails }) => {
        this.fields.set(fields);
        this.negotiations.set(negotiations);
        this.mails.set(mails);
        this.error.set(null);
        if (this.selectedNegotiation() === null && negotiations.some((n) => n.status === 'OPEN')) {
          this.selectNegotiation(negotiations.find((n) => n.status === 'OPEN')!.id);
        }
      },
      error: (e) => this.error.set(toPageError(e, this.i18n.t('common.error'))),
    });
  }

  selectField(id: number): void {
    this.selectedField.set(this.selectedField() === id ? null : id);
    const f = this.field();
    this.askingPrice.set(f ? f.referencePrice : null);
    this.clearMessages();
  }

  selectNegotiation(id: number): void {
    this.selectedNegotiation.set(id);
    this.amount.set(null);
    this.clearMessages();
  }

  negotiationFor(f: FarmlandView): NegotiationView | undefined {
    return this.open().find((n) => n.assetId === String(f.farmlandId));
  }

  fieldOf(n: NegotiationView): FarmlandView | undefined {
    return this.fields()?.find((f) => String(f.farmlandId) === n.assetId);
  }

  acceptable(n: NegotiationView): number | null {
    return acceptableAmount(n);
  }

  highest(n: NegotiationView): number | null {
    return highestBid(n);
  }

  amountLabel(n: NegotiationView): string {
    return this.i18n.t(`farmland.amount.${n.kind}`);
  }

  startDirect(f: FarmlandView): void {
    if (!f.owner) return;
    this.run(this.api.startDirectNegotiation(f.owner.id, f.farmlandId), (n) => {
      this.upsert(n);
      this.selectNegotiation(n.id);
      this.info.set(this.i18n.t('farmland.directStarted', { name: f.owner!.name }));
    });
  }

  /** TODO T-22: lease request to the owner; the offer (or refusal) arrives by mail and under "Verträge". */
  requestLease(f: FarmlandView): void {
    if (!f.owner) return;
    this.run(this.api.requestLease(f.farmlandId), (c) => {
      this.info.set(this.i18n.t(c.status === 'OFFERED' ? 'farmland.leaseOffered' : 'farmland.leaseRefused', { name: f.owner!.name }));
      this.load();
    });
  }

  sell(f: FarmlandView): void {
    const price = Number(this.askingPrice());
    if (!Number.isFinite(price) || price <= 0) {
      this.actionError.set(this.i18n.t('farmland.invalidAmount'));
      return;
    }
    this.run(this.api.sellOffer(f.farmlandId, Math.round(price)), (list) => {
      list.forEach((n) => this.upsert(n));
      if (list[0]) this.selectNegotiation(list[0].id);
      this.info.set(this.i18n.t('farmland.saleOffered', { n: list.length }));
    });
  }

  placeOffer(n: NegotiationView, value: number | null = this.amount()): void {
    const amount = Number(value);
    if (!Number.isFinite(amount) || amount <= 0) {
      this.actionError.set(this.i18n.t('farmland.invalidAmount'));
      return;
    }
    this.run(this.api.offer(n.id, Math.round(amount)), (r) => {
      this.upsert(r.negotiation);
      this.amount.set(null);
      this.info.set(
        this.i18n.t(`farmland.result.${r.result}`, {
          counter: r.counterAmount !== null ? this.i18n.t('farmland.counterValue', { amount: new Intl.NumberFormat('de-DE').format(r.counterAmount) }) : '',
          left: r.roundsLeft,
        }),
      );
      if (r.negotiation.status !== 'OPEN') {
        this.api.farmlands().subscribe((f) => this.fields.set(f));
        this.store.refresh();
      }
    });
  }

  withdraw(n: NegotiationView): void {
    this.run(this.api.withdraw(n.id), (updated) => {
      this.upsert(updated);
      this.info.set(this.i18n.t('farmland.withdrawn'));
    });
  }

  private upsert(n: NegotiationView): void {
    this.negotiations.update((list) => {
      const rest = (list ?? []).filter((x) => x.id !== n.id);
      return [n, ...rest];
    });
  }

  private clearMessages(): void {
    this.info.set(null);
    this.actionError.set(null);
  }

  private run<T>(obs: Observable<T>, next: (v: T) => void): void {
    this.busy.set(true);
    this.clearMessages();
    obs.subscribe({
      next: (v) => {
        this.busy.set(false);
        next(v);
      },
      error: (e) => {
        this.busy.set(false);
        this.actionError.set(apiErrorMessage(e, this.i18n.t('common.error')));
      },
    });
  }
}
