import { Component, computed, effect, inject, signal, untracked } from '@angular/core';
import { Router } from '@angular/router';
import { ApiService } from '../../core/api/api.service';
import { apiErrorMessage } from '../../core/api/api-error';
import { ChatGroupView, ChatMessageView } from '../../core/api/models';
import { TranslatePipe } from '../../core/i18n/translate.pipe';
import { TranslationService } from '../../core/i18n/translation.service';
import { GameStateStore } from '../../core/state/game-state.store';
import { GameTimePipe } from '../../shared/format/format.pipes';
import { Button } from '../../shared/ui/button';
import { Card } from '../../shared/ui/card';

/**
 * Hof-Tablet app "Dorfchat" (Roadmap V3.1 R31-D2): the village group chat in the style of a messenger - groups "Dorf",
 * "Nachbarn" and the clubs. Characters post announcements, gossip, congratulations and help requests (with a link to
 * the request); the player writes like in "Nachricht verfassen" (tone counts once per group and game day, no mechanics
 * through free text) and one member answers.
 */
@Component({
  selector: 'app-chat',
  imports: [TranslatePipe, GameTimePipe, Button, Card],
  template: `
    <section class="grid grid-cols-1 gap-4 lg:grid-cols-[minmax(0,1fr)_minmax(0,3fr)]">
      <app-card [title]="'chat.groups' | t" data-testid="chat-groups">
        <ul class="space-y-1">
          @for (g of groups(); track g.id) {
            <li>
              <button type="button" class="w-full rounded-lg px-2 py-1.5 text-left text-[12px] hover:bg-bg"
                [class.bg-bg]="selected()?.id === g.id" (click)="select(g)" data-testid="chat-group" [attr.data-key]="g.key">
                <span class="font-display font-bold text-text">{{ g.name }}</span>
                <span class="fp-label"> · {{ 'chat.members' | t: { n: g.members.length } }}</span>
                @if (g.last?.text) { <span class="block truncate text-muted">{{ g.last?.text }}</span> }
              </button>
            </li>
          } @empty {
            <li class="text-[12px] text-muted">{{ 'chat.none' | t }}</li>
          }
        </ul>
      </app-card>
      @if (selected(); as g) {
        <div class="flex min-h-[420px] flex-col rounded-[18px] border border-border bg-surface px-4 py-3" data-testid="chat-view">
          <header class="border-b border-border pb-2">
            <h2 class="font-display text-[15px] font-bold text-text">{{ g.name }}</h2>
            <p class="truncate text-[11px] text-muted">{{ memberNames(g) }}</p>
          </header>
          <ul class="flex-1 space-y-2 overflow-y-auto py-3">
            @for (m of messages(); track m.id) {
              <li class="flex" [class.justify-end]="m.character === null" data-testid="chat-message" [attr.data-kind]="m.kind">
                <div class="max-w-[80%] rounded-xl px-3 py-2 text-[13px]"
                  [class]="m.character === null ? 'bg-accent/15 text-text' : 'border border-border bg-bg text-text'">
                  @if (m.character) { <div class="fp-label">{{ m.character.name }}</div> }
                  @if (m.pending) {
                    <span class="text-muted">{{ 'chat.typing' | t }}</span>
                  } @else {
                    <span class="whitespace-pre-line">{{ m.text }}</span>
                  }
                  @if (m.link) {
                    <button type="button" class="mt-1 block text-[12px] text-accent hover:underline" (click)="open(m.link)" data-testid="chat-link">{{ 'chat.openRequest' | t }}</button>
                  }
                  <div class="mt-0.5 text-right text-[10px] text-muted">{{ m.gameTime | gameTime }}</div>
                </div>
              </li>
            } @empty {
              <li class="text-[12px] text-muted">{{ 'chat.empty' | t }}</li>
            }
          </ul>
          <form class="flex items-end gap-2 border-t border-border pt-2" (submit)="$event.preventDefault(); send()">
            <textarea class="fp-input min-h-[44px] flex-1" rows="2" maxlength="1000" [value]="draft()"
              (input)="draft.set($any($event.target).value)" [placeholder]="'chat.placeholder' | t" data-testid="chat-input"></textarea>
            <app-button type="submit" [disabled]="busy() || !draft().trim()" data-testid="chat-send">{{ 'chat.send' | t }}</app-button>
          </form>
          @if (pacing()) { <p class="mt-1 text-[11px] text-muted" data-testid="chat-pacing">{{ 'chat.pacing' | t }}</p> }
          <p class="mt-1 text-[11px] text-muted">{{ 'chat.hint' | t }}</p>
        </div>
      }
      @if (error(); as e) { <p class="text-[12px] text-danger" data-testid="chat-error">{{ e }}</p> }
    </section>
  `,
})
export class Chat {
  private readonly api = inject(ApiService);
  private readonly i18n = inject(TranslationService);
  private readonly store = inject(GameStateStore);
  private readonly router = inject(Router);

  readonly groups = signal<ChatGroupView[]>([]);
  private readonly selectedId = signal<number | null>(null);
  readonly messages = signal<ChatMessageView[]>([]);
  readonly draft = signal('');
  readonly busy = signal(false);
  readonly pacing = signal(false);
  readonly error = signal<string | null>(null);

  readonly selected = computed(() => {
    const list = this.groups();
    return list.find((g) => g.id === this.selectedId()) ?? list[0] ?? null;
  });

  constructor() {
    effect(() => {
      this.store.stateVersion();
      this.store.mailVersion();
      if (this.store.savegame()) untracked(() => this.load());
    });
  }

  load(): void {
    this.api.chatGroups().subscribe({
      next: (list) => {
        this.groups.set(list);
        this.loadMessages();
      },
      error: (e) => this.error.set(apiErrorMessage(e, this.i18n.t('common.error'))),
    });
  }

  private loadMessages(): void {
    const g = this.selected();
    if (!g) {
      this.messages.set([]);
      return;
    }
    this.api.chatMessages(g.id).subscribe({
      next: (list) => this.messages.set(list),
      error: (e) => this.error.set(apiErrorMessage(e, this.i18n.t('common.error'))),
    });
  }

  select(g: ChatGroupView): void {
    this.selectedId.set(g.id);
    this.pacing.set(false);
    this.loadMessages();
  }

  memberNames(g: ChatGroupView): string {
    return g.members.map((m) => m.name).join(', ');
  }

  send(): void {
    const g = this.selected();
    const text = this.draft().trim();
    if (!g || !text) return;
    this.busy.set(true);
    this.error.set(null);
    this.api.postChat(g.id, text).subscribe({
      next: (p) => {
        this.busy.set(false);
        this.draft.set('');
        this.pacing.set(p.pacingActive);
        this.loadMessages();
      },
      error: (e) => {
        this.busy.set(false);
        this.error.set(apiErrorMessage(e, this.i18n.t('common.error')));
      },
    });
  }

  open(link: string): void {
    void this.router.navigateByUrl(link);
  }
}
