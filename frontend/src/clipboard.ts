/**
 * 写剪贴板。查询结果表格和表数据工作区共用：桌面壳与非 HTTPS 部署下 navigator.clipboard
 * 可能不存在，退回 execCommand('copy')。
 */
export async function copyText(content: string) {
  if (navigator.clipboard?.writeText) {
    await navigator.clipboard.writeText(content);
    return;
  }
  const textarea = document.createElement('textarea');
  textarea.value = content;
  textarea.style.position = 'fixed';
  textarea.style.opacity = '0';
  document.body.appendChild(textarea);
  textarea.select();
  const copied = document.execCommand('copy');
  textarea.remove();
  if (!copied) throw new Error('浏览器未允许访问剪贴板');
}
