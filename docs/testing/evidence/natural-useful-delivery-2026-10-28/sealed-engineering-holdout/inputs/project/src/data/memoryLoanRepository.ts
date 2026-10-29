import { DeskError } from '../domain/errors';
import type { DeskSnapshot, Loan, LoanRepository, NewLoanCommand } from '../domain/types';

function copySnapshot(value: DeskSnapshot): DeskSnapshot {
  return {
    sites: value.sites.map((row) => ({ ...row })),
    kits: value.kits.map((row) => ({ ...row })),
    stocks: value.stocks.map((row) => ({ ...row })),
    loans: value.loans.map((row) => ({ ...row })),
  };
}

/** 仓库持有内部副本；调用方不能通过 snapshot 修改库存或借用单。 */
export function createMemoryLoanRepository(seed: DeskSnapshot): LoanRepository {
  const state = copySnapshot(seed);
  let nextSequence = 301;

  return {
    snapshot() {
      return copySnapshot(state);
    },
    insert(command: NewLoanCommand): Loan {
      const row: Loan = {
        id: `L${nextSequence++}`,
        siteId: command.siteId,
        kitId: command.kitId,
        quantity: command.quantity,
        purpose: command.purpose.trim(),
        status: 'CONFIRMED',
        revision: 1,
      };
      state.loans.push(row);
      return { ...row };
    },
    update(loanId: string, quantity: number, purpose: string): Loan {
      const row = state.loans.find((loan) => loan.id === loanId);
      if (!row) throw new DeskError('LOAN_NOT_FOUND');
      row.quantity = quantity;
      row.purpose = purpose.trim();
      row.revision += 1;
      return { ...row };
    },
  };
}
