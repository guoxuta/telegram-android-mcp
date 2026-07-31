# Telegram Android MCP (experimental)

> This is an unofficial, partially completed research prototype based on the
> Telegram Android source code. It is not affiliated with or endorsed by
> Telegram. Do not publish it under Telegram's name or standard logo.

该仓库在 Telegram Android 12.9.0 源码上增加了一个仅用于 debug 构建的本地
MCP 服务、Agent 调用工具和验证材料，目标是让 Android 智能体通过结构化接口
操作 Telegram。项目仍处于原型阶段；已注册工具不等于对应业务已经完成端到端
验证，请勿把它用于生产账号或无人监督的高风险操作。

- Agent 使用与本机/模拟器连接说明：[agent/README.md](agent/README.md)
- MCP 接口目录：[agent/TELEGRAM_MCP_AGENT_CATALOG.md](agent/TELEGRAM_MCP_AGENT_CATALOG.md)
- 完整覆盖率与正确性审计：[Tools/MCP/TELEGRAM_MCP_AUDIT.md](Tools/MCP/TELEGRAM_MCP_AUDIT.md)
- 机器可读工具定义：[TMessagesProj/src/main/assets/mcp/telegram_mcp_tools.json](TMessagesProj/src/main/assets/mcp/telegram_mcp_tools.json)

安全说明：MCP 服务只在 debug 构建中启动，绑定本机回环地址，并使用运行时生成
的 bearer token。Telegram API ID/Hash 应写入被 Git 忽略的 `local.properties`，
不要向仓库提交 API Key、验证码、两步验证密码或真实账号数据。

---

## Telegram messenger for Android

[Telegram](https://telegram.org) is a messaging app with a focus on speed and security. It’s superfast, simple and free.
This repo contains the official source code for [Telegram App for Android](https://play.google.com/store/apps/details?id=org.telegram.messenger).

## Creating your Telegram Application

We welcome all developers to use our API and source code to create applications on our platform.
There are several things we require from **all developers** for the moment.

1. [**Obtain your own api_id**](https://core.telegram.org/api/obtaining_api_id) for your application.
2. Please **do not** use the name Telegram for your app — or make sure your users understand that it is unofficial.
3. Kindly **do not** use our standard logo (white paper plane in a blue circle) as your app's logo.
3. Please study our [**security guidelines**](https://core.telegram.org/mtproto/security_guidelines) and take good care of your users' data and privacy.
4. Please remember to publish **your** code too in order to comply with the licences.

### API, Protocol documentation

Telegram API manuals: https://core.telegram.org/api

MTproto protocol manuals: https://core.telegram.org/mtproto

### Compilation Guide

**Note**: In order to support [reproducible builds](https://core.telegram.org/reproducible-builds), this repo contains dummy release.keystore,  google-services.json and filled variables inside BuildVars.java. Before publishing your own APKs please make sure to replace all these files with your own.

You will require Android Studio 3.4, Android NDK rev. 20 and Android SDK 8.1

1. Download the Telegram source code from https://github.com/DrKLO/Telegram ( git clone https://github.com/DrKLO/Telegram.git )
2. Copy your release.keystore into TMessagesProj/config
3. Fill out RELEASE_KEY_PASSWORD, RELEASE_KEY_ALIAS, RELEASE_STORE_PASSWORD in gradle.properties to access your  release.keystore
4.  Go to https://console.firebase.google.com/, create two android apps with application IDs org.telegram.messenger and org.telegram.messenger.beta, turn on firebase messaging and download google-services.json, which should be copied to the same folder as TMessagesProj.
5. Open the project in the Studio (note that it should be opened, NOT imported).
6. Fill out values in TMessagesProj/src/main/java/org/telegram/messenger/BuildVars.java – there’s a link for each of the variables showing where and which data to obtain.
7. You are ready to compile Telegram.

### Localization

We moved all translations to https://translations.telegram.org/en/android/. Please use it.
