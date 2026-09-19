# 通用物联网场地

一、设备管理

1. 设备基本信息
2. 设备所在的空间

二、空间管理

1. 空间基本信息
2. 空间的用户（用户ID、角色）

## 数据集迁移

Web 看板的类与数据集名加了 `WebDashboard` 前缀后，Mongo 看板布局集合由 `dashboard-layouts`
改为 `web-dashboard-layouts`（移动端看板将另用 `mobile-dashboard-layouts`）。升级后把旧集合
改名迁移一次（同库 renameCollection，原子完成，连同文档与索引一起过去）：

```bash
MONGOSH_URI='mongodb://<host>:27017/matrix' ./scripts/migrate-dashboard-layouts.sh
```

`web-dashboard-layouts` 的 `spaceId` 唯一索引由 `WebDashboardLayoutRepository.ensureIndexes()`
在服务启动时自动补建。脚本幂等：目标集合已存在、或源集合不存在时都会安全跳过。