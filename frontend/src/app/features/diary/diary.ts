import { Component, computed, effect, inject, signal, untracked } from '@angular/core';
import { RouterLink } from '@angular/router';
import { PageError, apiErrorMessage, toPageError } from '../../core/api/api-error';
import { ApiService } from '../../core/api/api.service';
import { DiaryView } from '../../core/api/models';
import { TranslatePipe } from '../../core/i18n/translate.pipe';
import { TranslationService } from '../../core/i18n/translation.service';
import { GameStateStore } from '../../core/state/game-state.store';
import { GameTimePipe } from '../../shared/format/format.pipes';
import { LabelPipe } from '../../shared/format/label.pipe';
import { Badge } from '../../shared/ui/badge';
import { Button } from '../../shared/ui/button';
import { Card } from '../../shared/ui/card';
import { PageErrorView } from '../../shared/ui/page-error';

/** File name of a Content-Disposition header (RFC 5987 {@code filename*} first), fallback "chronik.md". */
export function fileNameOf(disposition: string | null): string {
  if (!disposition) return 'chronik.md';
  const star = /filename\*=UTF-8''([^;]+)/i.exec(disposition);
  if (star) {
    try {
      return decodeURIComponent(star[1].trim());
    } catch {
      // malformed encoding: fall through to the plain name
    }
  }
  const plain = /filename="?([^";]+)"?/i.exec(disposition);
  return plain ? plain[1].trim() : 'chronik.md';
}

/** Hands a downloaded file to the browser (temporary link with the download attribute). */
export function saveFile(blob: Blob, name: string): void {
  const url = URL.createObjectURL(blob);
  const a = document.createElement('a');
  a.href = url;
  a.download = name;
  a.click();
  URL.revokeObjectURL(url);
}

export interface DiaryDay {
  day: number;
  entries: DiaryView[];
}

/** Groups entries (already in chronological order from the API) by game day. */
export function groupByDay(entries: DiaryView[], newestFirst: boolean): DiaryDay[] {
  const sorted = [...entries].sort((a, b) => a.gameTime - b.gameTime || a.id - b.id);
  if (newestFirst) sorted.reverse();
  const days: DiaryDay[] = [];
  for (const e of sorted) {
    const last = days[days.length - 1];
    if (last && last.day === e.gameDay) last.entries.push(e);
    else days.push({ day: e.gameDay, entries: [e] });
  }
  return days;
}

/**
 * Diary / chronicle (AP-8.9): automatic entries in chronological order (the first one is the onboarding backstory)
 * and free player notes, clearly marked as purely narrative without any game effect.
 */
@Component({
  selector: 'app-diary',
  imports: [TranslatePipe, LabelPipe, GameTimePipe, Card, Badge, Button, PageErrorView, RouterLink],
  templateUrl: './diary.html',
})
export class Diary {
  private readonly api = inject(ApiService);
  private readonly store = inject(GameStateStore);
  private readonly i18n = inject(TranslationService);

  readonly entries = signal<DiaryView[] | null>(null);
  readonly error = signal<PageError | null>(null);
  readonly newestFirst = signal(false);
  readonly category = signal<string>('');
  readonly title = signal('');
  readonly text = signal('');
  readonly saving = signal(false);
  readonly formError = signal<string | null>(null);
  readonly downloading = signal(false);
  readonly downloadError = signal<string | null>(null);

  readonly categories = computed(() => [...new Set((this.entries() ?? []).map((e) => e.category ?? (e.entryType === 'PLAYER_NOTE' ? 'PLAYER_NOTE' : 'OTHER')))]);
  readonly filtered = computed(() => {
    const cat = this.category();
    return (this.entries() ?? []).filter((e) => !cat || (e.category ?? (e.entryType === 'PLAYER_NOTE' ? 'PLAYER_NOTE' : 'OTHER')) === cat);
  });
  readonly days = computed(() => groupByDay(this.filtered(), this.newestFirst()));

  constructor() {
    effect(() => {
      this.store.diaryVersion();
      untracked(() => this.load());
    });
  }

  load(): void {
    this.api.diary().subscribe({
      next: (d) => {
        this.entries.set(d);
        this.error.set(null);
      },
      error: (e) => this.error.set(toPageError(e, this.i18n.t('common.error'))),
    });
  }

  /** Roadmap V3 R3-T2: downloads the chronicle as Markdown file (name from the backend). */
  download(): void {
    this.downloading.set(true);
    this.downloadError.set(null);
    this.api.chronicleFile().subscribe({
      next: (res) => {
        this.downloading.set(false);
        if (res.body) saveFile(res.body, fileNameOf(res.headers.get('Content-Disposition')));
      },
      error: () => {
        this.downloading.set(false);
        this.downloadError.set(this.i18n.t('diary.downloadError'));
      },
    });
  }

  save(): void {
    const title = this.title().trim();
    if (!title) {
      this.formError.set(this.i18n.t('diary.titleRequired'));
      return;
    }
    this.saving.set(true);
    this.formError.set(null);
    this.api.addDiaryNote(title, this.text().trim()).subscribe({
      next: (entry) => {
        this.saving.set(false);
        this.title.set('');
        this.text.set('');
        this.entries.update((list) => [...(list ?? []), entry]);
      },
      error: (e) => {
        this.saving.set(false);
        this.formError.set(apiErrorMessage(e, this.i18n.t('common.error')));
      },
    });
  }
}
