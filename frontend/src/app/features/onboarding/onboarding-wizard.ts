import { Component, computed, inject, signal } from '@angular/core';
import { FormBuilder, ReactiveFormsModule, Validators } from '@angular/forms';
import { Router } from '@angular/router';
import { Observable } from 'rxjs';
import { apiErrorMessage } from '../../core/api/api-error';
import { ApiService } from '../../core/api/api.service';
import { DetectedView, OnboardingRequest, OnboardingView } from '../../core/api/models';
import { TranslatePipe } from '../../core/i18n/translate.pipe';
import { TranslationService } from '../../core/i18n/translation.service';
import { GameStateStore } from '../../core/state/game-state.store';
import { GameTimePipe } from '../../shared/format/format.pipes';
import { LabelPipe } from '../../shared/format/label.pipe';
import { Badge } from '../../shared/ui/badge';
import { Button } from '../../shared/ui/button';
import { Card } from '../../shared/ui/card';
import { Icon } from '../../shared/ui/icon';

export const FARM_ORIGINS = ['INHERITED', 'BOUGHT_FRESH_START', 'RETURNED_HOME', 'LEASE_TAKEN_OVER'] as const;
export const VILLAGE_RELATIONS = ['UNKNOWN', 'CONNECTED', 'STRAINED'] as const;
export const TONE_PRESETS = ['IDYLLIC', 'REALISTIC', 'HARSH'] as const;
export const JOB_ROLES = ['MACHINE_OPERATOR', 'MECHANIC', 'ANIMAL_KEEPER', 'OFFICE_CLERK'] as const;
export const MAX_INITIAL_EMPLOYEES = 10;

export type WizardStep = 1 | 2 | 3 | 4 | 5;

/**
 * Onboarding wizard (functional concept "Vorgeschichte & Onboarding-Ablauf"):
 * 1 backstory blocks, 2 initial staff, 3 cast preview with reroll, 4 hint to create/load the FS25 savegame,
 * 5 link one of the detected, unlinked savegames. Numbers are form fields only; free text never sets values.
 */
@Component({
  selector: 'app-onboarding-wizard',
  imports: [ReactiveFormsModule, TranslatePipe, LabelPipe, GameTimePipe, Card, Button, Badge, Icon],
  templateUrl: './onboarding-wizard.html',
})
export class OnboardingWizard {
  private readonly api = inject(ApiService);
  private readonly store = inject(GameStateStore);
  private readonly router = inject(Router);
  private readonly i18n = inject(TranslationService);

  readonly origins = FARM_ORIGINS;
  readonly relations = VILLAGE_RELATIONS;
  readonly tones = TONE_PRESETS;
  readonly jobRoles = JOB_ROLES;
  readonly maxEmployees = MAX_INITIAL_EMPLOYEES;
  readonly stepNumbers: WizardStep[] = [1, 2, 3, 4, 5];

  readonly step = signal<WizardStep>(1);
  readonly busy = signal(false);
  readonly error = signal<string | null>(null);
  readonly draft = signal<OnboardingView | null>(null);
  readonly detected = signal<DetectedView[] | null>(null);
  readonly selectedSavegame = signal<string | null>(null);
  readonly employees = signal<string[]>([]);

  readonly form = inject(FormBuilder).nonNullable.group({
    farmOrigin: ['INHERITED', Validators.required],
    villageRelation: ['UNKNOWN', Validators.required],
    startingCapitalTarget: [100000, [Validators.required, Validators.min(0)]],
    withLegacyLoan: [false],
    legacyLoanAmount: [50000, [Validators.min(0)]],
    freeText: ['', [Validators.maxLength(2000)]],
    tonePreset: ['REALISTIC', Validators.required],
  });

  readonly freeTextEmpty = signal(true);

  constructor() {
    this.form.controls.freeText.valueChanges.subscribe((v) => this.freeTextEmpty.set(v.trim().length < 3));
  }

  readonly canAddEmployee = computed(() => this.employees().length < MAX_INITIAL_EMPLOYEES);

  addEmployee(role: string = JOB_ROLES[0]): void {
    if (this.canAddEmployee()) {
      this.employees.update((e) => [...e, role]);
    }
  }

  setEmployeeRole(index: number, role: string): void {
    this.employees.update((e) => e.map((r, i) => (i === index ? role : r)));
  }

  removeEmployee(index: number): void {
    this.employees.update((e) => e.filter((_, i) => i !== index));
  }

  toStep(s: WizardStep): void {
    this.error.set(null);
    this.step.set(s);
    if (s === 5) {
      this.loadDetected();
    }
  }

  nextFromBackstory(): void {
    if (this.form.invalid) {
      this.form.markAllAsTouched();
      return;
    }
    this.toStep(2);
  }

  request(): OnboardingRequest {
    const v = this.form.getRawValue();
    return {
      farmOrigin: v.farmOrigin,
      villageRelation: v.villageRelation,
      freeText: v.freeText.trim(),
      startingCapitalTarget: Number(v.startingCapitalTarget),
      legacyLoanAmount: v.withLegacyLoan ? Number(v.legacyLoanAmount) : null,
      tonePreset: v.tonePreset,
      initialEmployees: [...this.employees()],
    };
  }

  /** Step 2 -> 3: create the draft savegame and generate the start cast. */
  generate(): void {
    this.run(this.api.createOnboarding(this.request()), (view) => {
      this.draft.set(view);
      this.toStep(3);
    });
  }

  rerollAll(): void {
    const d = this.draft();
    if (d) {
      this.run(this.api.reroll(d.id), (view) => this.draft.set(view));
    }
  }

  rerollOne(characterId: number): void {
    const d = this.draft();
    if (d) {
      this.run(this.api.reroll(d.id, characterId), (view) => this.draft.set(view));
    }
  }

  loadDetected(): void {
    this.api.unlinkedSavegames().subscribe({
      next: (list) => {
        this.detected.set(list);
        if (list.length === 1) {
          this.selectedSavegame.set(list[0].savegameId);
        }
      },
      error: (e) => this.error.set(apiErrorMessage(e, this.i18n.t('common.error'))),
    });
  }

  confirm(): void {
    const d = this.draft();
    const sg = this.selectedSavegame();
    if (!d || !sg) {
      return;
    }
    this.run(this.api.confirmOnboarding(d.id, sg), () => {
      this.store.refresh();
      void this.router.navigateByUrl('/');
    });
  }

  private run<T>(obs: Observable<T>, next: (v: T) => void): void {
    this.busy.set(true);
    this.error.set(null);
    obs.subscribe({
      next: (v) => {
        this.busy.set(false);
        next(v);
      },
      error: (e) => {
        this.busy.set(false);
        this.error.set(apiErrorMessage(e, this.i18n.t('common.error')));
      },
    });
  }
}
