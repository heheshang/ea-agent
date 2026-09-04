<template>
  <router-view />
</template>

<style>
/* ===== 豆包风全局主题（品牌蓝 #3370FF、浅色留白、大圆角） ===== */
:root {
  /* 品牌蓝及其派生色 */
  --db-primary: #3370ff;
  --db-primary-light-3: #5c8bff;
  --db-primary-light-5: #99b7ff;
  --db-primary-light-7: #d5e2ff;
  --db-primary-light-8: #e8efff;
  --db-primary-light-9: #f4f8ff;
  --db-primary-dark-2: #295acc;
  --db-bg: #f6f7f9;
  --db-card-shadow: 0 4px 20px rgba(31, 45, 80, 0.06), 0 1px 3px rgba(31, 45, 80, 0.04);

  /* Element Plus 主色对齐 */
  --el-color-primary: var(--db-primary);
  --el-color-primary-light-3: var(--db-primary-light-3);
  --el-color-primary-light-5: var(--db-primary-light-5);
  --el-color-primary-light-7: var(--db-primary-light-7);
  --el-color-primary-light-8: var(--db-primary-light-8);
  --el-color-primary-light-9: var(--db-primary-light-9);
  --el-color-primary-dark-2: var(--db-primary-dark-2);

  /* 圆角 */
  --el-border-radius-base: 8px;
  --el-border-radius-small: 6px;
  --el-border-radius-round: 999px;

  /* 文字/背景 */
  --el-text-color-primary: #1d2129;
  --el-text-color-regular: #4e5969;
  --el-bg-color-page: var(--db-bg);
  --el-border-color-lighter: #f0f1f3;
}

html,
body,
#app {
  height: 100%;
  margin: 0;
  font-family: -apple-system, "PingFang SC", "Helvetica Neue", "Microsoft YaHei", sans-serif;
  background: var(--db-bg);
  -webkit-font-smoothing: antialiased;
  color: var(--el-text-color-primary);
}

/* 卡片：白底、大圆角、柔和阴影 */
.el-card {
  border-radius: 16px !important;
  border: none !important;
  box-shadow: var(--db-card-shadow) !important;
}
.el-card__header {
  border-bottom: 1px solid #f4f5f7 !important;
  font-weight: 600;
  color: #1d2129;
}

/* 按钮：胶囊形态 */
.el-button {
  border-radius: 999px;
  font-weight: 500;
}
.el-button--primary:not(.is-link):not(.is-text) {
  box-shadow: 0 2px 8px rgba(51, 112, 255, 0.28);
}
.el-button--primary:not(.is-link):not(.is-text):hover {
  box-shadow: 0 4px 12px rgba(51, 112, 255, 0.35);
}

/* 输入：大圆角、聚焦蓝边 */
.el-input__wrapper {
  border-radius: 12px;
  box-shadow: 0 0 0 1px #e5e6eb inset !important;
}
.el-input__wrapper.is-focus {
  box-shadow: 0 0 0 1.5px var(--db-primary) inset !important;
}
.el-textarea__inner {
  border-radius: 12px !important;
}

/* 表格：表头浅灰、行 hover 浅蓝 */
.el-table {
  --el-table-header-bg-color: #fafbfc;
  --el-table-border-color: #f0f1f3;
  --el-table-row-hover-bg-color: #f4f8ff;
  border-radius: 12px;
  overflow: hidden;
}
.el-table th.el-table__cell {
  font-weight: 600;
  color: #4e5969;
}

/* 标签：胶囊 */
.el-tag {
  border-radius: 999px;
}

/* 弹窗/消息：圆角统一 */
.el-message {
  border-radius: 12px;
}
.el-dialog {
  border-radius: 16px;
}

/* 滚动条：细窄圆润 */
::-webkit-scrollbar {
  width: 6px;
  height: 6px;
}
::-webkit-scrollbar-thumb {
  background: #d0d3d9;
  border-radius: 3px;
}
::-webkit-scrollbar-thumb:hover {
  background: #b8bcc4;
}
</style>