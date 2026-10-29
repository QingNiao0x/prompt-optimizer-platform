import { availabilityFor, type Signup } from './availability';

export interface EventDefinition { id: string; title: string; capacity: number; }

export function previewSignup(event: EventDefinition, seats: number, signups: Signup[]) {
  if (!Number.isSafeInteger(seats) || seats < 1) return { allowed: false, reason: '席位数量无效', remaining: 0 };
  const remaining = availabilityFor(event.capacity, event.id, signups);
  return { allowed: seats <= remaining, reason: seats <= remaining ? '' : '可进入候补名单', remaining };
}

export function eventCountSummary(eventId: string, signups: Signup[]) {
  const selected = signups.filter((row) => row.eventId === eventId);
  return {
    booked: selected.filter((row) => row.state === 'BOOKED').reduce((sum, row) => sum + row.seats, 0),
    waiting: selected.filter((row) => row.state === 'WAITING').reduce((sum, row) => sum + row.seats, 0),
  };
}
