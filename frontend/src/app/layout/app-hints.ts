/** One paragraph of a first-open hint: i18n key of the text, optionally the card or field it belongs to as heading. */
export interface HintParagraph {
  heading?: string;
  text: string;
}

/**
 * First-open hints of the apps (owner decisions 2026-10-06): the fixed explanations that stood on the app pages, as
 * i18n keys in reading order, headed by the title of the card or field they stood at. Apps without such a text got a
 * short new explanation (`appHints.<id>`). Explanations in the case cards and warnings that depend on the state stay
 * where they are.
 */
export const APP_HINTS: Record<string, HintParagraph[]> = {
  mail: [{ text: 'mailbox.replyHint' }],
  phone: [{ text: 'appHints.phone' }],
  contacts: [{ text: 'appHints.contacts' }],
  newspaper: [{ text: 'appHints.newspaper' }],
  chat: [{ text: 'chat.hint' }],
  tasks: [{ heading: 'tasks.inGame', text: 'tasks.waitingPromptsHint' }],
  calendar: [{ text: 'appHints.calendar' }],
  bank: [
    { heading: 'bank.apply', text: 'bank.processingHint' },
    { heading: 'bank.deferral', text: 'bank.deferralHint' },
  ],
  authorities: [
    { heading: 'authorities.advisor', text: 'bank.tax.advisorHint' },
    { heading: 'authorities.inspections', text: 'authorities.inspectionsIntro' },
    { heading: 'authorities.droughtAid', text: 'authorities.droughtAidIntro' },
    { heading: 'authorities.socialInsurance.title', text: 'authorities.socialInsurance.intro' },
    { heading: 'authorities.winter.title', text: 'authorities.winter.intro' },
  ],
  market: [
    { heading: 'market.whyTitle', text: 'market.whyCredit' },
    { text: 'market.whyEvents' },
    { heading: 'market.history', text: 'market.unitHint' },
  ],
  insurance: [
    { heading: 'contracts.insurance', text: 'contracts.insuranceIntro' },
    { heading: 'insurance.theftCover', text: 'insurance.theftCoverHint' },
    { heading: 'insurance.drought', text: 'insurance.droughtIntro' },
    { heading: 'insurance.damages', text: 'insurance.damagesIntro' },
  ],
  staff: [
    { heading: 'settings.helpers.title', text: 'employees.helperHint' },
    { heading: 'employees.trainings', text: 'employees.trainingInfo' },
    { heading: 'enums.jobRole.SEASONAL_WORKER', text: 'employees.seasonalHint' },
    { heading: 'employees.postings', text: 'employees.factsFixed' },
  ],
  fields: [
    { heading: 'farmland.leases', text: 'contracts.leaseHint' },
    { heading: 'farmland.leaseOut.title', text: 'farmland.leaseOut.intro' },
    { heading: 'farmland.familyField', text: 'farmland.familyFieldHint' },
  ],
  stable: [{ text: 'contracts.livestockHint' }],
  workshop: [
    { heading: 'workshop.maintenance', text: 'contracts.maintenanceHint' },
    { heading: 'workshop.loans.title', text: 'workshop.loans.intro' },
  ],
  trade: [
    { text: 'trade.intro' },
    { heading: 'trade.cases', text: 'trade.offerHint' },
    { heading: 'tabs.trade.tiere', text: 'trade.animals.intro' },
    { heading: 'trade.farmShop', text: 'trade.farmShopIntro' },
    { heading: 'trade.holiday.title', text: 'trade.holiday.hint' },
  ],
  diary: [{ heading: 'diary.addNote', text: 'diary.narrativeOnly' }],
  settings: [
    { heading: 'settings.ai', text: 'settings.aiIntro' },
    { heading: 'settings.apiKey', text: 'settings.keyHint' },
    { heading: 'settings.farm.name', text: 'settings.farm.hint' },
    { heading: 'onboarding.tonePreset', text: 'settings.toneFixed' },
    { heading: 'settings.burden.title', text: 'settings.burden.hint' },
    { heading: 'settings.bypass.title', text: 'settings.bypass.hint' },
    { heading: 'settings.prompts.title', text: 'settings.prompts.hint' },
    { text: 'settings.prompts.key' },
    { heading: 'settings.helpers.title', text: 'settings.helpers.intro' },
    { text: 'settings.helpers.strictHint' },
    { heading: 'lan.title', text: 'lan.intro' },
  ],
};

export function hintsOf(appId: string): HintParagraph[] {
  return APP_HINTS[appId] ?? [];
}
