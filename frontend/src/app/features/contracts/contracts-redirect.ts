import { Component, OnInit, inject } from '@angular/core';
import { ActivatedRoute, Router } from '@angular/router';
import { ApiService } from '../../core/api/api.service';
import { TranslatePipe } from '../../core/i18n/translate.pipe';
import { APPS } from '../../layout/apps';
import { caseAppId, contractAppId } from '../../layout/task-apps';

/**
 * `/contracts?case=` / `?contract=` was the address of "Verträge & Vorgänge". Mails already stored in a savegame still
 * link there: forward to the app the entry belongs to now (same query, so it is highlighted), otherwise to "Aufgaben".
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
        next: (cases) => this.go(caseAppId(cases.find((c) => c.id === caseId)?.kind ?? ''), { case: caseId }),
        error: () => this.go('tasks', {}),
      });
    } else if (contractId) {
      this.api.contracts().subscribe({
        next: (contracts) => this.go(contractAppId(contracts.find((c) => c.id === contractId)?.kind ?? ''), { contract: contractId }),
        error: () => this.go('tasks', {}),
      });
    } else {
      this.go('tasks', {});
    }
  }

  private go(appId: string, queryParams: Record<string, number>): void {
    const app = APPS.find((a) => a.id === appId) ?? APPS.find((a) => a.id === 'tasks')!;
    this.router.navigate([app.path], { queryParams, replaceUrl: true });
  }
}
