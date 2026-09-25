import { Provider } from '@angular/core';
import { EVENT_SOURCE_FACTORY, LiveEventSource } from '../app/core/live/live-events.service';

/** In-memory EventSource stand-in for unit tests. */
export class FakeEventSource implements LiveEventSource {
  static instances: FakeEventSource[] = [];
  static get last(): FakeEventSource {
    return FakeEventSource.instances[FakeEventSource.instances.length - 1];
  }

  private readonly listeners = new Map<string, ((e: MessageEvent) => void)[]>();
  onerror: ((e: Event) => void) | null = null;
  closed = false;

  constructor(readonly url: string) {
    FakeEventSource.instances.push(this);
  }

  addEventListener(type: string, listener: (e: MessageEvent) => void): void {
    this.listeners.set(type, [...(this.listeners.get(type) ?? []), listener]);
  }

  emit(type: string, data: unknown = {}): void {
    const ev = new MessageEvent(type, { data: JSON.stringify(data) });
    (this.listeners.get(type) ?? []).forEach((l) => l(ev));
  }

  fail(): void {
    this.onerror?.(new Event('error'));
  }

  close(): void {
    this.closed = true;
  }
}

export function provideFakeEventSource(): Provider {
  FakeEventSource.instances = [];
  return { provide: EVENT_SOURCE_FACTORY, useValue: (url: string) => new FakeEventSource(url) };
}
