# 第三方组件致谢

> **本文件不是正文，只是指针。**
>
> 完整文档只有一份：[`docs/THIRD_PARTY_NOTICES.md`](docs/THIRD_PARTY_NOTICES.md)。

## 为什么改成指针

这里原本放着一份标注「与 `docs/` 保持同步」的副本，实际漏了一条**法律义务**：

`docs/` 版包含 Nimbus JOSE + JWT（`com.nimbusds:nimbus-jose-jwt`，Apache-2.0）的
许可声明——该库用于校验 OAuth 登录的签名与身份声明，是实际依赖；根目录这份没有。

第三方许可声明漏项不只是文档问题：Apache-2.0 要求在分发时保留声明。
两份并存时，谁照着根目录那份整理 NOTICE，就会漏掉它。

完整历史内容仍可从 git 历史取回（`git log -- THIRD_PARTY_NOTICES.md`）。

以后只改 `docs/THIRD_PARTY_NOTICES.md`。
