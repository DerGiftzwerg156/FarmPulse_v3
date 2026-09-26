import { Injectable, InjectionToken, OnDestroy, inject } from '@angular/core';
import { environment } from '../../../environments/environment';
import { GameStateStore } from '../state/game-state.store';

/** Minimal EventSource surface the service needs (lets tests inject a fake). */
export interface LiveEventSource {
  addEventListener(type: string, listener: (e: MessageEvent) => void): void;
  onerror: ((e: Event) => void) | null;
  close(): void;
}

export const EVENT_SOURCE_FACTORY = new InjectionToken<(url: string) => LiveEventSource>('EVENT_SOURCE_FACTORY', {
  providedIn: 'root',
  factory: () => (url: string) => new EventSource(url) as unknown as LiveEventSource,
});

/** Reconnect delays in ms; the last value repeats. */
export const RECONNECT_DELAYS = [1000, 2000, 5000, 10000, 30000];

/**
 * Consumes the backend SSE stream (`GET /api/events/stream`) and feeds it into {@link GameStateStore}:
 * `mail`/`call`/`diary`/`state`/`notice` bump the matching version signal (feature pages reload on change) and refresh the
 * header counters. Reconnects automatically with a stepped backoff when the connection drops.
 */
@Injectable({ providedIn: 'root' })
export class LiveEventsService implements OnDestroy {
  private readonly store = inject(GameStateStore);
  private readonly factory = inject(EVENT_SOURCE_FACTORY);
  private source: LiveEventSource | null = null;
  private reconnectTimer: ReturnType<typeof setTimeout> | null = null;
  private attempt = 0;
  private active = false;

  connect(): void {
    this.active = true;
    if (this.source) return;
    this.open();
  }

  disconnect(): void {
    this.active = false;
    if (this.reconnectTimer) clearTimeout(this.reconnectTimer);
    this.reconnectTimer = null;
    this.source?.close();
    this.source = null;
    this.store.connected.set(false);
  }

  ngOnDestroy(): void {
    this.disconnect();
  }

  private open(): void {
    const src = this.factory(`${environment.apiBase}/events/stream`);
    this.source = src;
    src.addEventListener('hello', () => {
      this.attempt = 0;
      this.store.connected.set(true);
      // Something may have happened while we were offline.
      this.store.refresh();
    });
    src.addEventListener('mail', () => this.bump(this.store.mailVersion));
    src.addEventListener('call', () => this.bump(this.store.callVersion));
    src.addEventListener('diary', () => this.bump(this.store.diaryVersion, false));
    src.addEventListener('state', () => this.bump(this.store.stateVersion));
    src.addEventListener('notice', () => this.bump(this.store.noticeVersion, false));
    src.onerror = () => this.handleError(src);
  }

  private bump(version: { update(fn: (v: number) => number): void }, refresh = true): void {
    version.update((v) => v + 1);
    if (refresh) this.store.refresh();
  }

  private handleError(src: LiveEventSource): void {
    if (src !== this.source) return;
    // We handle reconnects ourselves (predictable backoff, works for fakes as well).
    src.close();
    this.source = null;
    this.store.connected.set(false);
    if (!this.active) return;
    const delay = RECONNECT_DELAYS[Math.min(this.attempt, RECONNECT_DELAYS.length - 1)];
    this.attempt++;
    this.reconnectTimer = setTimeout(() => {
      this.reconnectTimer = null;
      if (this.active && !this.source) this.open();
    }, delay);
  }
}
