# Native Pro52准备机制：零调用准备

本目录尚未正式freeze、启动服务、GET目录或调用模型。当前总198/500。根任务只批准此有限工具准备；后续先另批freeze/start/GET-only，确认当前真实目录／官方路由后，再单独批准最多2次PREPARE机制调用。不是采用、作品或产品Pro Plan。

`bundle.draft.json`只复用52闭批001/003两个原始PREPARE请求，顶层`model`从`deepseek-flash`改成`deepseek-v4-pro`。52system、完整原JSON、真实题答、消息数和所有8192/.2/JSON/disabled参数不变，无hint／最后题答消息／新事实或registry。内部`input.modelId=deepseek:deepseek-flash`明确保留为历史任务元数据，实际实验模型由本次profile的公开Pro目录、真实route和顶层model决定，不能称为用户通过产品选择Pro。

独立有限profile `NATIVE_PRO52_PREPARE_MECHANISM_V1`只接受两固定EXEC52 job、总2及每job一次，完整payload还原后必须等于52锁定原请求。模型、host、未知job、材料／system／参数改变、超cap或重复job均拒绝。原无profile仍Flash；旧GEN50 profile及其模块不变。start仅复制已闭52的逐hash校验classes/classpath，不读将来的共享target。

9项JS源请求边界、11项Java传输边界及旧GEN50兼容、7项private-freezer／GET前置形状检查已通过，均0HTTP/0模型；真实目录没有用历史记录冒充当前结果。当前验证器默认只预检，显式`--execute-get`才正常固定身份执行3次loopback GET（auth/me、public models、现有model-selection），使用第一条真实冻结job做header／active metadata，不造job、不POST模型；只保存安全公开路由及默认值。调用前每次再次真实catalog.resolve/官方route核验，传输拦截还强制官方目的地。

拟阶段名`native-pro52-prepare`。两个job各一次无repair；每次结果交独立语义检查，任一混合、代选、硬遗漏或协议失败即停余job及writer。两者均对也仅有资格另行批准writer2。未知仍未知、旧失败不改判、默认31不变、封存继续不读。闭批后继续使用网络禁用的实际严格reader/resolve作结构校验，不能以HTTP200代替语义正确。
