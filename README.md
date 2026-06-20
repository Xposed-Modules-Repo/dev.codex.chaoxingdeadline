# 学习通截止提醒

LSPosed Xposed 模块，自动捕获超星学习通作业和考试截止时间。

## 下载

[最新版本](https://github.com/Xposed-Modules-Repo/dev.codex.chaoxingdeadline/releases/latest)

## 使用

1. 安装本模块并在 LSPosed Manager 中勾选，作用域选择「学习通」
2. 打开学习通，模块自动捕获作业和考试的截止时间
3. 截止前会发送系统通知提醒

## 构建

```bash
./gradlew :app:assembleRelease
```

## 开源许可

本项目基于 [Apache License 2.0](LICENSE) 开源，SPDX 标识为 `Apache-2.0`。

分发、修改或二次开发本项目时，请保留版权声明、许可证文本以及必要的第三方组件声明。

## 第三方组件

本项目使用或引用了以下第三方组件，相关组件遵循各自的开源许可证：

- Android Gradle Plugin / Gradle Wrapper
- Android SDK APIs
- libxposed API 102
- `app/libs/service-102.0.0-patched.aar`
- `app/libs/interface-102.0.0-patched.aar`

其中 `service-102.0.0-patched.aar` 与 `interface-102.0.0-patched.aar` 为本项目构建所需的本地 patched AAR。若重新分发或继续修改，请同时保留其上游项目的许可证、版权与 NOTICE 声明。详见 [NOTICE](NOTICE)。

## 免责声明

本项目仅用于个人待办提醒与学习辅助，不隶属于超星、学习通或相关学校平台。使用本项目时请遵守所在学校、课程平台和相关服务的使用规则。
