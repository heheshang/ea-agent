<template>
  <router-view />
</template>

<style>
:root {
  color-scheme: light;
  --db-primary: #3864ed;
  --db-primary-light-3: #6b8bf2;
  --db-primary-light-5: #9cb2f6;
  --db-primary-light-7: #c3d1fa;
  --db-primary-light-8: #d7e0fc;
  --db-primary-light-9: #edf2ff;
  --db-primary-dark-2: #2c50c2;
  --db-bg: #f3f6fa;
  --db-card-shadow: 0 3px 14px #172b4505;
  --ea-text-muted: #69798f;
  --ea-border: #e5ebf3;
  --el-color-primary: var(--db-primary);
  --el-color-primary-light-3: var(--db-primary-light-3);
  --el-color-primary-light-5: var(--db-primary-light-5);
  --el-color-primary-light-7: var(--db-primary-light-7);
  --el-color-primary-light-8: var(--db-primary-light-8);
  --el-color-primary-light-9: var(--db-primary-light-9);
  --el-color-primary-dark-2: var(--db-primary-dark-2);
  --el-color-success: #23856c;
  --el-color-warning: #b87b19;
  --el-color-danger: #d34c5e;
  --el-color-info: #74839a;
  --el-border-radius-base: 8px;
  --el-border-radius-small: 6px;
  --el-text-color-primary: #172b45;
  --el-text-color-regular: #4e6078;
  --el-text-color-secondary: var(--ea-text-muted);
  --el-bg-color-page: var(--db-bg);
  --el-border-color: #dce4ef;
  --el-border-color-light: var(--ea-border);
  --el-border-color-lighter: #edf1f7;
  --el-font-size-base: 13px;
  --el-component-size: 36px;
}
* { box-sizing: border-box; }
html, body, #app { height: 100%; margin: 0; }
body { font-family: Inter, -apple-system, BlinkMacSystemFont, "PingFang SC", "Microsoft YaHei", sans-serif; background: var(--db-bg); -webkit-font-smoothing: antialiased; color: var(--el-text-color-primary); font-size: 14px; }
button, input, textarea { font: inherit; }
button { -webkit-tap-highlight-color: transparent; }
:focus-visible { outline: 2px solid var(--db-primary); outline-offset: 3px; }
::selection { background: #dce6ff; }
.el-card { --el-card-border-color: var(--ea-border); border-radius: 14px; box-shadow: var(--db-card-shadow); }
.el-card__header { padding: 20px 22px; font-weight: 600; font-size: 14px; border-bottom-color: var(--ea-border); }
.el-card__body { padding: 22px; }
.el-button { font-weight: 500; border-radius: 8px; transition: background-color .18s, border-color .18s, box-shadow .18s; }
.el-button--primary:not(.is-link):not(.is-text):not(.is-disabled) { box-shadow: 0 3px 8px #3864ed1a; }
.el-input__wrapper, .el-select__wrapper { border-radius: 8px; }
.el-input__inner { font-size: 13px; }
.el-textarea__inner { border-radius: 8px; line-height: 1.7; }
.el-table { --el-table-header-bg-color: #f6f8fc; --el-table-border-color: #edf1f7; --el-table-row-hover-bg-color: #f1f5ff; --el-table-header-text-color: #65758c; --el-table-text-color: #41546d; border-radius: 10px; }
.el-table th.el-table__cell { font-size: 12px; font-weight: 600; padding: 13px 0; }
.el-table td.el-table__cell { padding: 13px 0; }
.el-table .cell { line-height: 1.6; }
.el-table .el-table__row--striped td.el-table__cell { background: #fbfcfe; }
.el-tag { border-radius: 5px; font-size: 11px; font-weight: 500; }
.el-radio-button__inner { font-weight: 500; }
.el-pagination { font-weight: 500; --el-pagination-button-bg-color: #fff; }
.el-dialog { border-radius: 16px; max-width: calc(100vw - 32px); padding: 24px; }
.el-dialog__header { padding-bottom: 20px; }
.el-dialog__title { font-size: 18px; font-weight: 650; }
.el-message { border-radius: 10px; }
.el-form-item__label { font-size: 12px; font-weight: 500; }
/* Shared secondary-page rhythm; scoped functional layouts remain intact. */
.main .page-head { gap: 16px; flex-wrap: wrap; margin-bottom: 6px; }
main.main .page-title { color: var(--el-text-color-primary); font-size: 23px; font-weight: 650; letter-spacing: -.5px; line-height: 1.4; }
main.main .page-sub { color: var(--ea-text-muted); font-size: 12px; margin-top: 7px; line-height: 1.8; }
.main .toolbar { flex-wrap: wrap; align-items: center; }
.main .card { border-color: var(--ea-border); box-shadow: var(--db-card-shadow); min-width: 0; }
.main .card .v { color: #223e65; font-variant-numeric: tabular-nums; letter-spacing: -.6px; }
.main .card .k { color: var(--ea-text-muted); }
.main .no-runs, .main .empty-tip { color: var(--ea-text-muted); }
.resource-heading { display: flex; align-items: center; justify-content: space-between; flex-wrap: wrap; gap: 20px; padding: 4px 0; }
.resource-heading h1 { margin: 0; font-size: 22px; font-weight: 650; letter-spacing: -.5px; }
.resource-heading p { margin: 8px 0 0; color: var(--ea-text-muted); font-size: 12px; font-weight: 400; line-height: 1.7; }
.resource-actions { display: flex; gap: 8px; align-items: center; flex-wrap: wrap; }
.resource-count { display: inline-block; vertical-align: middle; margin-left: 6px; padding: 2px 8px; border-radius: 6px; background: #edf2ff; color: var(--db-primary); font-size: 12px; letter-spacing: 0; }
::-webkit-scrollbar { width: 6px; height: 6px; }
::-webkit-scrollbar-thumb { background: #c8d2e0; border-radius: 6px; }
::-webkit-scrollbar-thumb:hover { background: #aab9cd; }
@media (max-width: 768px) {
  .el-card__header, .el-card__body { padding: 16px; }
  main.main .page-title { font-size: 21px; }
  .main .page-head { align-items: flex-start; }
  .resource-actions { width: 100%; }
  .resource-actions .el-input { flex: 1 1 100%; width: 100% !important; }
  main.main .toolbar .kw { width: 100%; }
  .main .controls, .main .head-right { display: flex; flex-wrap: wrap; gap: 8px; }
  main.main .cards, main.main .cards-5, main.main .onto-cards { grid-template-columns: repeat(2, minmax(0, 1fr)); }
  main.main .grid-2 { grid-template-columns: minmax(0, 1fr); }
  .main .token-legend { flex-wrap: wrap; gap: 8px; }
  .el-pagination { flex-wrap: wrap; justify-content: flex-start; gap: 6px; }
  .el-table .el-table__cell.el-table-fixed-column--right { position: relative !important; right: auto !important; }
  .el-table .el-table-fixed-column--right::before { display: none; }
  .el-dialog { margin-top: 6vh; }
}
@media (prefers-reduced-motion: reduce) { *, *::before, *::after { scroll-behavior: auto !important; animation-duration: .01ms !important; transition-duration: .01ms !important; } }
</style>
