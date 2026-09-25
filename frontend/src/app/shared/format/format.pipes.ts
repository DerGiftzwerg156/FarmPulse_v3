import { Pipe, PipeTransform } from '@angular/core';
import { formatGameTime, formatMoney, formatNumber } from './format';

@Pipe({ name: 'money' })
export class MoneyPipe implements PipeTransform {
  transform(v: number | null | undefined): string {
    return formatMoney(v);
  }
}

@Pipe({ name: 'num' })
export class NumberPipe implements PipeTransform {
  transform(v: number | null | undefined, digits = 0): string {
    return formatNumber(v, digits);
  }
}

@Pipe({ name: 'gameTime' })
export class GameTimePipe implements PipeTransform {
  transform(v: number | null | undefined): string {
    return formatGameTime(v);
  }
}
