/**
 * 表浏览工具栏什么时候把浏览类操作（导出 / 刷新 / 新增行 / 导入）收进「更多」。
 *
 * 以前这件事由 CSS 容器查询决定，「更多」只在收起时才出现。但「备份此表」是个低频操作，
 * 而且对象树右键和对象详情里都有入口，不值得常驻工具栏 —— 它挪进「更多」以后，「更多」
 * 得一直在，菜单里收不收浏览类操作就必须由脚本知道，否则宽屏下菜单里会重复一遍工具栏。
 *
 * 门槛量的是工具栏自己的宽度，不是视口：资源管理器可以拖到 480px，标签条也在这一行。
 * 有待提交修改时多出「撤销全部 / 预览 / 提交」三颗按钮，所以收起得更早。
 */
export const TABLE_TOOLBAR_COLLAPSE_WIDTH = 1000;
export const TABLE_TOOLBAR_COLLAPSE_WIDTH_WITH_PENDING = 1240;

export function tableToolbarCollapsed(width: number | undefined, hasPendingChanges: boolean): boolean {
  // 还没量到宽度（首帧、隐藏的标签页）时按展开处理：收起会让常用操作暂时从工具栏消失。
  if (width == null || width <= 0) return false;
  return width <= (hasPendingChanges ? TABLE_TOOLBAR_COLLAPSE_WIDTH_WITH_PENDING : TABLE_TOOLBAR_COLLAPSE_WIDTH);
}
