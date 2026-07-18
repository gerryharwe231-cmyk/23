# Slope Connector RealMesh 0.9.18 — Connected Model Sweep

本版不再把围栏、栏杆、玻璃板和完整方块简化成程序长方体后重新贴图，而是直接读取 Minecraft 客户端已经烘焙完成的真实方块模型：

- 保留每个 BakedQuad 的原始顶点、原始图集 UV 和染色信息。
- 只把模型顶点沿弧线路径变形，因此石砖等完整方块的细节不再局部发糊。
- 围栏、铁栏杆、玻璃板、墙、Conquest Reforged balustrade/railing 等使用东西双向连接的中间模型。
- 每个弧线单元使用完整的中间连接模型；两个真实端点仍保留在世界中，第一和最后一个单元直接接到端点连接面。
- 带四向连接属性的端点方块会被设置为朝向弧线的单向连接状态，端点仍是独立普通方块。
- Conquest Reforged 的 `axis=x` 栏杆、四向连接栏杆和 Pane 类方块会自动进入真实模型扫掠模式。
- 视觉模型不再决定碰撞。非完整方块继续使用其真实 OutlineShape 生成独立碰撞，避免栏杆变成一整堵实心墙。
- 完整方块仍支持自动弧边裁切，并使用真实一格模型棱柱作为裁切体。

## 升级测试

删除旧的 0.9.17 jar，只保留本版本构建出的 0.9.18 jar。旧弧线保存的是旧版网格数据，验证纹理和栏杆时请拆除旧弧后重新生成。

## 构建

运行 GitHub Actions 工作流：

`Build Slope Connector`

每次成功构建会同时生成：

- `slope-connector-build`：可直接放入 `mods` 的 jar。
- `slope-connector-0.9.18-complete-source`：完整可编译源码压缩包。
