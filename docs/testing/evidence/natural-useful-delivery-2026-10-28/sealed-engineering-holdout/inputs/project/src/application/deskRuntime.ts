import { createMemoryLoanRepository } from '../data/memoryLoanRepository';
import { createSeedSnapshot } from '../data/seed';
import { createLoanDesk } from './loanDeskService';

/** 浏览器内的默认运行时；测试用例可直接注入自己的仓库和 desk。 */
export const deskRuntime = createLoanDesk(createMemoryLoanRepository(createSeedSnapshot()));
