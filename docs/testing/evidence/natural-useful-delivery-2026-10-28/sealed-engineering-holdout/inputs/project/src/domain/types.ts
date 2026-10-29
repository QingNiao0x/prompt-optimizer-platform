export type SiteId = 'harbor' | 'hill';
export type LoanStatus = 'DRAFT' | 'CONFIRMED' | 'COLLECTED' | 'RETURNED' | 'CANCELLED';

export interface Site {
  id: SiteId;
  name: string;
}

export interface Kit {
  id: string;
  name: string;
  depositPerUnit: number;
  description: string;
}

export interface StockRow {
  siteId: SiteId;
  kitId: string;
  total: number;
}

export interface Loan {
  id: string;
  siteId: SiteId;
  kitId: string;
  quantity: number;
  status: LoanStatus;
  purpose: string;
  revision: number;
}

export interface DeskSnapshot {
  sites: Site[];
  kits: Kit[];
  stocks: StockRow[];
  loans: Loan[];
}

export interface AvailabilityQuery {
  siteId: SiteId;
  kitId: string;
  excludeLoanId?: string;
}

export interface AvailabilityQuote {
  siteId: SiteId;
  kitId: string;
  total: number;
  occupied: number;
  available: number;
}

export interface NewLoanCommand {
  siteId: SiteId;
  kitId: string;
  quantity: number;
  purpose: string;
}

export interface EditLoanCommand {
  loanId: string;
  quantity: number;
  purpose: string;
}

export interface LoanRepository {
  snapshot(): DeskSnapshot;
  insert(command: NewLoanCommand): Loan;
  update(loanId: string, quantity: number, purpose: string): Loan;
}

export interface LoanDesk {
  snapshot(): DeskSnapshot;
  getLoan(loanId: string): Loan;
  quoteNew(siteId: SiteId, kitId: string): AvailabilityQuote;
  quoteEdit(loanId: string): AvailabilityQuote;
  createLoan(command: NewLoanCommand): Loan;
  updateLoan(command: EditLoanCommand): Loan;
}
