# RemoteMux 开发约定

## 提交边界

- 每个提交只包含一个可独立解释、测试和回滚的改动；不要混入无关格式化或本地文件。
- 提交信息采用 Conventional Commits：`type(scope): summary`。常用类型包括 `feat`、`fix`、`refactor`、`test`、`docs`、`build`、`ci` 和 `chore`。
- summary 使用英文祈使语气，不加句号；必要时在正文说明原因、兼容性和验证方式。
- `Cargo.lock` 随应用代码提交。`target/`、`dist/`、本地配置和配对凭证不得提交。
- 不改写已经共享的历史，不使用 `--no-verify` 绕过质量检查。

## 提交前检查

首次克隆仓库后启用受版本控制的 Git hooks：

```bash
scripts/setup-git-hooks.sh
```

该设置只写入当前仓库的本地 Git 配置，不修改全局配置。`pre-commit` 和 `pre-push` 都会强制依次执行：

```bash
cargo check
cargo clippy --all-targets -- -D warnings
cargo clippy --all-targets --all-features -- -D warnings
cargo test
```

任意命令失败都会阻止 commit 或 push。不要使用 `--no-verify` 绕过 hooks。常规 Rust 改动还应运行格式和 diff 检查：

```bash
cargo fmt --all -- --check
git diff --check
```

修改 musl 构建或发布流程时，还要运行：

```bash
bash -n scripts/build-musl.sh
scripts/build-musl.sh x86_64-unknown-linux-musl
scripts/build-musl.sh aarch64-unknown-linux-musl
```

静态分发包必须通过构建脚本中的 ELF interpreter 检查。涉及 Linux tmux 行为的改动，应按 `docs/validation.md` 在 Linux 环境验证，不以 macOS 结果代替。

## 安全要求

- 不提交 `agent.toml`、`pairing.toml`、Relay token、机器密钥、日志或终端录屏。
- 管理命令继续使用固定 argv 调用 tmux，不得把协议字段拼接成 shell 命令。
- 新增破坏性操作必须由 Client 明确确认，连接断开不得隐式终止 tmux session。
- 修改协议、认证、加密或凭证存储时，提交正文中要写明安全边界和迁移影响。

## 分支和合并

- `main` 始终保持可构建、可测试；功能开发使用短生命周期分支。
- 合并前确保 CI 通过并审阅完整 diff。出现不兼容的协议或配置变更时，在提交信息中使用 `BREAKING CHANGE:` 说明。
