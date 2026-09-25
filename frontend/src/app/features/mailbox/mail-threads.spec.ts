import { message } from '../../../testing/fixtures';
import { groupThreads } from './mail-threads';

describe('groupThreads', () => {
  it('groups by thread root, newest thread first, with unread ids and village-life flag', () => {
    const threads = groupThreads([
      message({ id: 4, threadRootId: 1, gameTime: 400, subject: 'Re: Kredit', initiatedBy: 'CHARACTER' }),
      message({ id: 3, threadRootId: 3, gameTime: 300, subject: 'Einladung', category: 'VILLAGE_LIFE', read: true }),
      message({ id: 2, threadRootId: 1, gameTime: 200, subject: 'Re: Kredit', initiatedBy: 'PLAYER', read: true }),
      message({ id: 1, threadRootId: 1, gameTime: 100, subject: 'Kredit', formLink: '/bank?application=1', read: true }),
    ]);
    expect(threads.map((t) => t.rootId)).toEqual([1, 3]);
    expect(threads[0]).toMatchObject({ subject: 'Kredit', count: 3, unreadIds: [4], villageLife: false, formLink: '/bank?application=1' });
    expect(threads[0].latest.id).toBe(4);
    expect(threads[1]).toMatchObject({ villageLife: true, unreadIds: [] });
  });
});
