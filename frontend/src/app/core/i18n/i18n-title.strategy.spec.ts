import { Component } from '@angular/core';
import { TestBed } from '@angular/core/testing';
import { Title } from '@angular/platform-browser';
import { Router, TitleStrategy, provideRouter } from '@angular/router';
import { I18nTitleStrategy } from './i18n-title.strategy';

@Component({ template: '' })
class Blank {}

describe('I18nTitleStrategy', () => {
  it('translates route title keys', async () => {
    TestBed.configureTestingModule({
      providers: [
        provideRouter([
          { path: '', component: Blank, title: 'nav.home' },
          { path: 'mailbox', component: Blank, title: 'nav.mailbox' },
        ]),
        { provide: TitleStrategy, useClass: I18nTitleStrategy },
      ],
    });
    const router = TestBed.inject(Router);
    const title = TestBed.inject(Title);
    await router.navigateByUrl('/mailbox');
    expect(title.getTitle()).toBe('Postfach · FarmPulse');
    await router.navigateByUrl('/');
    expect(title.getTitle()).toBe('FarmPulse');
  });
});
