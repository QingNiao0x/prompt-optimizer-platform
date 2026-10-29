import type { DeskSnapshot } from '../domain/types';

/** 全部数据为合成夹具，不含真实人员或真实单位。 */
export function createSeedSnapshot(): DeskSnapshot {
  return {
    sites: [{ id: 'harbor', name: '港湾活动室' }, { id: 'hill', name: '山坡活动室' }],
    kits: [
      { id: 'camera', name: '记录相机套装', depositPerUnit: 80, description: '相机、挂绳及收纳包作为一套借用' },
      { id: 'tripod', name: '轻便三脚架', depositPerUnit: 30, description: '按支记录，归还时检查锁扣' },
      { id: 'speaker', name: '小型音箱', depositPerUnit: 50, description: '只在配置了该器材的地点申请' },
    ],
    stocks: [
      { siteId: 'harbor', kitId: 'camera', total: 8 },
      { siteId: 'hill', kitId: 'camera', total: 4 },
      { siteId: 'harbor', kitId: 'tripod', total: 6 },
      { siteId: 'hill', kitId: 'tripod', total: 2 },
      { siteId: 'hill', kitId: 'speaker', total: 3 },
    ],
    loans: [
      { id: 'L101', siteId: 'harbor', kitId: 'camera', quantity: 2, status: 'CONFIRMED', purpose: '社区短片记录', revision: 1 },
      { id: 'L102', siteId: 'harbor', kitId: 'camera', quantity: 1, status: 'COLLECTED', purpose: '活动拍摄', revision: 2 },
      { id: 'L103', siteId: 'harbor', kitId: 'camera', quantity: 3, status: 'DRAFT', purpose: '尚未确认的构想', revision: 1 },
      { id: 'L104', siteId: 'harbor', kitId: 'camera', quantity: 2, status: 'RETURNED', purpose: '已结束的读书会', revision: 3 },
      { id: 'L105', siteId: 'harbor', kitId: 'camera', quantity: 1, status: 'CANCELLED', purpose: '已经取消', revision: 2 },
      { id: 'L201', siteId: 'hill', kitId: 'camera', quantity: 3, status: 'CONFIRMED', purpose: '山坡社区记录', revision: 1 },
      { id: 'L106', siteId: 'harbor', kitId: 'tripod', quantity: 4, status: 'CONFIRMED', purpose: '定点拍摄', revision: 1 },
    ],
  };
}
