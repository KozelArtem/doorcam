export function eventFromUrl(search) {
  const id = new URLSearchParams(search).get('event');
  return /^\d{1,15}$/.test(id ?? '') ? id : null;
}
export function dayKey(value) {
  const date = new Date(value);
  return [date.getFullYear(), date.getMonth()+1, date.getDate()].join('-');
}
export function groupDays(events) {
  const groups = new Map();
  for (const event of [...events].sort((a,b) => b.time-a.time)) {
    const key = dayKey(event.time);
    if (!groups.has(key)) groups.set(key, []);
    groups.get(key).push(event);
  }
  return groups;
}
