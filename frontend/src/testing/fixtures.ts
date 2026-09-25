import { CharacterRef, MessageView, SavegameView } from '../app/core/api/models';

export const HOUR = 3_600_000;
export const DAY = 24 * HOUR;

export function character(over: Partial<CharacterRef> = {}): CharacterRef {
  return { id: 1, name: 'Heike Brandt', role: 'BANK_ADVISOR', status: 'ACTIVE', ...over };
}

export function message(over: Partial<MessageView> = {}): MessageView {
  const id = over.id ?? 1;
  return {
    id,
    threadRootId: id,
    channel: 'MAIL',
    initiatedBy: 'CHARACTER',
    character: character(),
    subject: 'Betreff',
    body: 'Text',
    gameTime: DAY,
    read: false,
    category: 'CREDIT',
    eventType: null,
    formLink: null,
    usedFallback: false,
    callStatus: null,
    ringDeadlineGameTime: null,
    openTopic: false,
    relatedEntityType: null,
    relatedEntityId: null,
    ...over,
  };
}

export function savegame(over: Partial<SavegameView> = {}): SavegameView {
  return {
    id: 1, savegameId: 'sg-1', mapName: 'Erlengrund', gameTime: 3 * DAY, gameDay: 3, balance: 120000,
    tonePreset: 'REALISTIC', unreadMails: 0, pendingCalls: 0, reputationTier: 'NEUTRAL', ...over,
  };
}
