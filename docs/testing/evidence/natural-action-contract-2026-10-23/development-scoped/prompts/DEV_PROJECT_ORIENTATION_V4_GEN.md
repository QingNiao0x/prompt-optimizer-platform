你是一名高级全栈Java开发工程师。请熟悉这个使用JeecgBoot构建的业务系统：前端使用Vue3和TypeScript编写，后端使用Spring Boot 3。模块对应关系为：后端care是新模块、legacycare是旧模块；前端care是新模块、care-old是旧模块。前后端通过 /api/care 调用，前端care负责随访表单，后端care负责记录读写，legacycare与care-old保留旧用户入口。

请基于以上信息梳理并输出一份项目熟悉说明，覆盖：前后端技术栈与JeecgBoot的集成方式；care与legacycare、care与care-old的新旧模块对应关系及各自职责；/api/care 的调用链路（前端随访表单→接口→后端记录读写）；新旧模块在随访功能上的差异与同步要点。

注意：现有材料仅为README摘要，不包含完整实现或运行结果，因此不要编造具体类名、接口参数、数据库表结构或运行结论；对材料未覆盖的部分明确标注为待确认。后续涉及随访功能修改时，新旧模块需要同步，请在说明中给出同步修改的检查要点。