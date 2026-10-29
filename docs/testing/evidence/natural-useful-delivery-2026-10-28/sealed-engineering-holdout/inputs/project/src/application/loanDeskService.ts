import { availabilityFor } from '../domain/availability';
import { DeskError } from '../domain/errors';
import { assertRequestedQuantity } from '../domain/quantity';
import type { AvailabilityQuote, EditLoanCommand, Loan, LoanDesk, LoanRepository, NewLoanCommand, SiteId } from '../domain/types';

/** 页面共享业务入口，仓库可注入；不接触浏览器或持久化环境。 */
export function createLoanDesk(repository: LoanRepository): LoanDesk {
  const previews = new Map<string, AvailabilityQuote>();
  const newKey = (siteId: SiteId, kitId: string) => `new:${siteId}:${kitId}`;
  const editKey = (loanId: string) => `edit:${loanId}`;

  function getLoan(loanId: string): Loan {
    const row = repository.snapshot().loans.find((loan) => loan.id === loanId);
    if (!row) throw new DeskError('LOAN_NOT_FOUND');
    return row;
  }

  function assertEditable(loan: Loan): void {
    if (loan.status !== 'CONFIRMED') throw new DeskError('READ_ONLY_LOAN');
  }

  function quoteNew(siteId: SiteId, kitId: string): AvailabilityQuote {
    const quote = availabilityFor(repository.snapshot(), { siteId, kitId });
    previews.set(newKey(siteId, kitId), quote);
    return quote;
  }

  function quoteEdit(loanId: string): AvailabilityQuote {
    const loan = getLoan(loanId);
    const quote = availabilityFor(repository.snapshot(), { siteId: loan.siteId, kitId: loan.kitId });
    previews.set(editKey(loanId), quote);
    return quote;
  }

  function createLoan(command: NewLoanCommand): Loan {
    assertRequestedQuantity(command.quantity);
    const quote = previews.get(newKey(command.siteId, command.kitId))
      ?? quoteNew(command.siteId, command.kitId);
    if (command.quantity > quote.available) throw new DeskError('INSUFFICIENT_STOCK');
    return repository.insert(command);
  }

  function updateLoan(command: EditLoanCommand): Loan {
    const loan = getLoan(command.loanId);
    assertEditable(loan);
    assertRequestedQuantity(command.quantity);
    const quote = previews.get(editKey(command.loanId)) ?? quoteEdit(command.loanId);
    if (command.quantity > quote.available) throw new DeskError('INSUFFICIENT_STOCK');
    return repository.update(command.loanId, command.quantity, command.purpose);
  }

  return { snapshot: () => repository.snapshot(), getLoan, quoteNew, quoteEdit, createLoan, updateLoan };
}
