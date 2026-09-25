import { TestBed } from '@angular/core/testing';
import { provideHttpClient } from '@angular/common/http';
import { HttpTestingController, provideHttpClientTesting } from '@angular/common/http/testing';
import { FakeEventSource, provideFakeEventSource } from '../../../testing/fake-event-source';
import { GameStateStore } from '../state/game-state.store';
import { LiveEventsService, RECONNECT_DELAYS } from './live-events.service';

describe('LiveEventsService', () => {
  function setup() {
    TestBed.configureTestingModule({
      providers: [provideHttpClient(), provideHttpClientTesting(), provideFakeEventSource()],
    });
    return {
      live: TestBed.inject(LiveEventsService),
      store: TestBed.inject(GameStateStore),
      http: TestBed.inject(HttpTestingController),
    };
  }

  afterEach(() => vi.useRealTimers());

  it('connects to the stream and marks the store as connected on hello', () => {
    const { live, store, http } = setup();
    live.connect();
    expect(FakeEventSource.last.url).toBe('/api/events/stream');
    expect(store.connected()).toBe(false);
    FakeEventSource.last.emit('hello', { connected: true });
    expect(store.connected()).toBe(true);
    http.expectOne('/api/savegame').flush(null);
  });

  it('bumps versions and refreshes header counters on mail/call/state events', () => {
    const { live, store, http } = setup();
    live.connect();
    FakeEventSource.last.emit('mail', { id: 5 });
    expect(store.mailVersion()).toBe(1);
    http.expectOne('/api/savegame').flush({
      id: 1, savegameId: 'x', mapName: null, gameTime: 0, gameDay: 1, balance: 0,
      tonePreset: 'REALISTIC', unreadMails: 4, pendingCalls: 0, reputationTier: 'NEUTRAL',
    });
    expect(store.unreadMails()).toBe(4);

    FakeEventSource.last.emit('call', { id: 6, status: 'RINGING' });
    expect(store.callVersion()).toBe(1);
    http.expectOne('/api/savegame').flush(null);

    FakeEventSource.last.emit('state', { gameTime: 1 });
    expect(store.stateVersion()).toBe(1);
    http.expectOne('/api/savegame').flush(null);

    FakeEventSource.last.emit('diary', { id: 7 });
    expect(store.diaryVersion()).toBe(1);
    http.expectNone('/api/savegame');
  });

  it('reconnects with backoff after the connection drops', () => {
    vi.useFakeTimers();
    const { live, store, http } = setup();
    live.connect();
    FakeEventSource.last.emit('hello');
    http.expectOne('/api/savegame').flush(null);
    const first = FakeEventSource.last;
    first.fail();
    expect(first.closed).toBe(true);
    expect(store.connected()).toBe(false);
    expect(FakeEventSource.instances.length).toBe(1);
    vi.advanceTimersByTime(RECONNECT_DELAYS[0]);
    expect(FakeEventSource.instances.length).toBe(2);
    // second failure waits longer
    FakeEventSource.last.fail();
    vi.advanceTimersByTime(RECONNECT_DELAYS[0]);
    expect(FakeEventSource.instances.length).toBe(2);
    vi.advanceTimersByTime(RECONNECT_DELAYS[1] - RECONNECT_DELAYS[0]);
    expect(FakeEventSource.instances.length).toBe(3);
    FakeEventSource.last.emit('hello');
    expect(store.connected()).toBe(true);
    http.expectOne('/api/savegame').flush(null);
  });

  it('does not reconnect after disconnect', () => {
    vi.useFakeTimers();
    const { live } = setup();
    live.connect();
    const src = FakeEventSource.last;
    live.disconnect();
    expect(src.closed).toBe(true);
    src.fail();
    vi.advanceTimersByTime(60000);
    expect(FakeEventSource.instances.length).toBe(1);
  });
});
