import test from 'node:test';
import assert from 'node:assert/strict';
import { dayKey, eventFromUrl, groupDays } from '../public/library.mjs';

test('notification target only accepts an event id', () => {
  assert.equal(eventFromUrl('?event=1733552046789'), '1733552046789');
  assert.equal(eventFromUrl('?event=../foo'), null);
});
test('local-day groups retain most recent recordings first across midnight', () => {
  const dates = [new Date(2026, 10, 22, 0, 1), new Date(2026, 10, 21, 23, 59), new Date(2026, 10, 22, 10)];
  const items = dates.map((date,i) => ({time:date.getTime(),id:String(i)}));
  const groups = groupDays(items);
  assert.deepEqual([...groups.keys()], [dayKey(dates[0]),dayKey(dates[1])]);
  assert.deepEqual([...groups.values()].map(g => g.map(e => e.id)), [['2','0'],['1']]);
});
