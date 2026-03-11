# Embedded YAML + Nova 示例

本目录展示如何在 YAML 文本块中嵌入 Nova 脚本，并通过 `.nova/*.json` 提供命名空间级别的宿主补全。

## 文件说明

- `.nova/host-bindings.json`：宿主绑定配置
- `workflow.yaml`：YAML 内嵌 Nova 脚本示例

## 使用方式

1. 用 VS Code 打开本目录
2. 确保已安装并启用 `vscode-nova`
3. 打开 `workflow.yaml`
4. 在 `# nova=...` 标记下的文本块中体验补全、悬停、签名帮助等能力

## 标记示例

```yaml
# nova=condition
check: |-
  player.level > 10

# nova=reward
action: |-
  giveItem("diamond", 3)
```

