# 航天科技情报分析智能体 — UI 视觉简报

**日期:** 2026-05-04  
**范围:** 仅样式与对外品牌展示（无业务逻辑变更）

## 品牌

- **产品全称:** 航天科技情报分析智能体  
- **浏览器标题:** 与产品全称一致  
- **侧栏标题:** 同上；字较多时允许两行紧凑排版，`title` 提供完整hover 提示  

## 色板（深蓝 + 浅灰）

| Token | Hex | 用途 |
|-------|-----|------|
| brand-primary | `#1B3A5F` | 主色、图标强调、Ant `colorPrimary` |
| brand-secondary | `#2c5282` | 渐变终点、中等深度蓝 |
| brand-deep | `#0f2744` | 深色渐变末端 |
| brand-surface-soft | `#dce6f0` | 渐变起点、柔和高光 |
| sidebar | `#f0f4f8` | 侧栏背景（冷灰） |

装饰渐变统一为 **浅蓝灰 → 中深蓝**，禁用高饱和黄橙、蓝紫娱乐向渐变。

## 图标

- 侧栏品牌区使用 `RocketOutlined`，色为品牌主色。

## Ant Design

- `ConfigProvider.theme.token.colorPrimary` 与 `brand-primary` 对齐，组件默认主按钮与主题一致。

## 未纳入

- 栏目文案（如「智能体助手」）保持原样；`package.json` name 不变。
