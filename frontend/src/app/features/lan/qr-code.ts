import { Component, computed, input } from '@angular/core';
import qrcode from 'qrcode-generator';

/** Roadmap V3 R3-N3: QR code of a text (the tablet address), generated in the browser as SVG path. */
@Component({
  selector: 'app-qr-code',
  template: `
    <svg
      [attr.viewBox]="viewBox()"
      class="h-40 w-40 rounded-lg bg-white p-2"
      shape-rendering="crispEdges"
      role="img"
      [attr.aria-label]="text()"
      data-testid="qr-code"
    >
      <path [attr.d]="path()" fill="#0B0F0D" />
    </svg>
  `,
})
export class QrCode {
  readonly text = input.required<string>();

  private readonly code = computed(() => {
    const qr = qrcode(0, 'M');
    qr.addData(this.text());
    qr.make();
    return qr;
  });

  readonly viewBox = computed(() => {
    const n = this.code().getModuleCount();
    return `0 0 ${n} ${n}`;
  });

  /** One unit square per dark module. */
  readonly path = computed(() => {
    const qr = this.code();
    const n = qr.getModuleCount();
    const parts: string[] = [];
    for (let row = 0; row < n; row++) {
      for (let col = 0; col < n; col++) {
        if (qr.isDark(row, col)) parts.push(`M${col} ${row}h1v1h-1z`);
      }
    }
    return parts.join('');
  });
}
