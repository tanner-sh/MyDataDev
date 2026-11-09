/**
 * 表浏览工具栏什么时候把浏览类操作（导出 / 刷新 / 新增行 / 导入）收进「更多」。
 *
 * 宽的时候「备份此表」直接摆在工具栏上，不再有「更多」—— 以前它独自占着一个「更多」菜单，
 * 点开只有一项，纯属多一步。收窄时「更多」才出现，浏览类操作连同备份整组收进去。
 * 菜单里放什么取决于收没收起，所以门槛必须由脚本知道，而不是交给 CSS 容器查询。
 *
 * 门槛量的是工具栏自己的宽度，不是视口：资源管理器可以拖到 480px，标签条也在这一行。
 * 有待提交修改时多出「撤销全部 / 预览 / 提交」三颗按钮，所以收起得更早。
 */
export const TABLE_TOOLBAR_COLLAPSE_WIDTH = 1100;
export const TABLE_TOOLBAR_COLLAPSE_WIDTH_WITH_PENDING = 1340;

/**
 * 停靠到顶栏之后量的是顶栏中间那一格，里面只有按钮、没有左边那段说明文字，所以门槛是另一组：
 * 筛选 / 导出 / 刷新 / 新增行 / 导入 / 备份一排约 570px；有待提交修改时再加撤销全部、预览、提交（带快捷键）
 * 与单步撤销 / 重做，约 950px。
 */
export const TABLE_DOCKED_COLLAPSE_WIDTH = 600;
export const TABLE_DOCKED_COLLAPSE_WIDTH_WITH_PENDING = 960;

export function tableToolbarCollapsed(width: number | undefined, hasPendingChanges: boolean, docked = false): boolean {
  // 还没量到宽度（首帧、隐藏的标签页）时按展开处理：收起会让常用操作暂时从工具栏消失。
  if (width == null || width <= 0) return false;
  if (docked) return width <= (hasPendingChanges ? TABLE_DOCKED_COLLAPSE_WIDTH_WITH_PENDING : TABLE_DOCKED_COLLAPSE_WIDTH);
  return width <= (hasPendingChanges ? TABLE_TOOLBAR_COLLAPSE_WIDTH_WITH_PENDING : TABLE_TOOLBAR_COLLAPSE_WIDTH);
}
