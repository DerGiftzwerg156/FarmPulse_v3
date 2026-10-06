import { Component, OnInit, inject } from '@angular/core';
import { ActivatedRoute, Router } from '@angular/router';
import { ApiService } from '../../core/api/api.service';
import { TranslatePipe } from '../../core/i18n/translate.pipe';
import { APPS } from '../../layout/apps';
import { caseTabOf, contractTabOf, isTab } from '../../layout/app-tabs';
import { caseAppId, contractAppId } from '../../layout/task-apps';

/**
 * `/contracts?case=` / `?contract=` was the address of "Verträge & Vorgänge". Mails already stored in a savegame still
 * link there: forward to the app and tab the entry belongs to now (same query, so it is highlighted), otherwise to
 * "Aufgaben".
 */
@Component({
  selector: 'app-contracts-redirect',
  imports: [TranslatePipe],
  template: `<p class="text-sm text-muted">{{ 'common.loading' | t }}</p>`,
})
export class ContractsRedirect implements OnInit {
  private readonly api = inject(ApiService);
  private readonly route = inject(ActivatedRoute);
  private readonly router = inject(Router);

  ngOnInit(): void {
    const query = this.route.snapshot.queryParamMap;
    const caseId = Number(query.get('case'));
    const contractId = Number(query.get('contract'));
    if (caseId) {
      this.api.cases().subscribe({
        next: (cases) => {
          const kind = cases.find((c) => c.id === caseId)?.kind ?? '';
          this.go(caseAppId(kind), caseTabOf(kind), { case: caseId });
        },
        error: () => this.go('tasks', undefined, {}),
      });
    } else if (contractId) {
      this.api.contracts().subscribe({
        next: (contracts) => {
          const c = contracts.find((x) => x.id === contractId);
          this.go(contractAppId(c?.kind ?? ''), c ? contractTabOf(c.kind, c.level) : undefined, { contract: contractId });
        },
        error: () => this.go('tasks', undefined, {}),
      });
    } else {
      this.go('tasks', undefined, {});
    }
  }

  /** Straight to the entry's tab; without one the app's own redirect picks the tab. */
  private go(appId: string, tab: string | undefined, queryParams: Record<string, number>): void {
    const app = APPS.find((a) => a.id === appId) ?? APPS.find((a) => a.id === 'tasks')!;
    const path = isTab(app.id, tab) ? [app.path, tab!] : [app.path];
    this.router.navigate(path, { queryParams, replaceUrl: true });
  }
}
