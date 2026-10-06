import { Component, OnInit, inject } from '@angular/core';
import { ActivatedRoute, Params, Router } from '@angular/router';
import { ApiService } from '../core/api/api.service';
import { TranslatePipe } from '../core/i18n/translate.pipe';
import { appById } from './apps';
import { caseTabOf, contractTabOf, defaultTab, isTab, queryTab } from './app-tabs';

/**
 * Address of an app without a tab (`/bank`, `/aemter?case=12`): opens the tab of the deep link's entry (mail
 * `formLink`s, task links, old bookmarks) with the same query, so the entry stays highlighted; without a deep link the
 * tab last used on this device. `case` / `contract` need the kind of the entry, so they are looked up.
 */
@Component({
  selector: 'app-tab-redirect',
  imports: [TranslatePipe],
  template: `<p class="text-sm text-muted">{{ 'common.loading' | t }}</p>`,
})
export class AppTabRedirect implements OnInit {
  private readonly api = inject(ApiService);
  private readonly route = inject(ActivatedRoute);
  private readonly router = inject(Router);

  ngOnInit(): void {
    const appId = this.route.snapshot.data['appId'] as string;
    const query = this.route.snapshot.queryParams;
    const caseId = Number(query['case']);
    const contractId = Number(query['contract']);
    const direct = Object.keys(query).map((k) => queryTab(appId, k)).find((t) => !!t);
    if (direct) {
      this.go(appId, direct, query);
    } else if (caseId) {
      this.api.cases().subscribe({
        next: (cases) => this.go(appId, caseTabOf(cases.find((c) => c.id === caseId)?.kind ?? ''), query),
        error: () => this.go(appId, undefined, query),
      });
    } else if (contractId) {
      this.api.contracts().subscribe({
        next: (contracts) => {
          const c = contracts.find((x) => x.id === contractId);
          this.go(appId, c ? contractTabOf(c.kind, c.level) : undefined, query);
        },
        error: () => this.go(appId, undefined, query),
      });
    } else {
      this.go(appId, undefined, query);
    }
  }

  private go(appId: string, tab: string | undefined, queryParams: Params): void {
    const target = isTab(appId, tab) ? tab! : defaultTab(appId);
    this.router.navigate([appById(appId).path, target], { queryParams, replaceUrl: true });
  }
}
