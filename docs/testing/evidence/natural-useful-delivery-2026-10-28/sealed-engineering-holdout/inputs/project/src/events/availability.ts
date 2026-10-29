export interface Signup { id: string; eventId: string; seats: number; state: 'BOOKED' | 'WAITING' | 'CANCELLED'; }

/** 活动报名采用独立名额口径：候补不占正式席位。 */
export function availabilityFor(capacity: number, eventId: string, signups: Signup[]): number {
  const used = signups.filter((row) => row.eventId === eventId && row.state === 'BOOKED')
    .reduce((sum, row) => sum + row.seats, 0);
  return Math.max(0, capacity - used);
}
