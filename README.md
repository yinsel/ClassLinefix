![4de798b7ba63fa0fb6a2b7f338bd997](https://github.com/user-attachments/assets/030b190e-d33b-4f50-a19c-6044f41b9cb6)
# ClassLinefix

一个专业的Java字节码行号恢复工具，可以为字节码添加顺序行号，使得调试和异常堆栈跟踪更加清晰。


## 特性

### 🚀 核心功能
- **智能行号恢复**: 集成多种策略，自动选择最适合的方法
- **批量处理**: 支持JAR和CLASS文件的批量处理
- **目录结构保持**: 输出文件保持与输入相同的目录结构
- **重复处理检测**: 自动检测已有行号信息，避免重复处理


## 使用方法

### 基本用法
```bash
java -jar ClassLinefix.jar -i <输入目录> -o <输出目录>
```

### 命令行选项
```
usage: java -jar ClassLinefix.jar
  -m,--modified-only             只输出实际修改过的文件，不复制未修改文件和资源
  -c,--class-only                只处理独立 CLASS 文件，JAR 原样复制（无需参数值）
  -h,--help                      显示帮助信息
  -i,--input <directory>         包含JAR和CLASS文件的输入目录
  -o,--output <directory>        处理后文件的输出目录
  -p,--packages <package1,package2,...>  排除指定包名或类名的处理（逗号分隔）
  -w,--whitelist <package1,package2,...>  仅处理指定包名或类名（与 -p 匹配规则一致）
  -s,--skip-inner <true|false>   跳过内部类和包含内部类的类（默认：false）
```

### 使用示例

#### 基本用法
```bash
# 处理所有文件
java -jar ClassLinefix.jar -i ./input-jars -o ./output-jars
```

#### 只输出实际处理并修改的文件
```bash
java -jar ClassLinefix.jar -i ./input -o ./output --modified-only

# 只输出白名单中实际修改过的独立 CLASS，不输出 JAR 或普通资源
java -jar ClassLinefix.jar -i ./input -o ./output -c -m -w "com.api.doc"
```

`-m` / `--modified-only` 是无参数开关，默认关闭。不传时保持原来的复制行为。
启用后只输出字节码实际修改成功的 CLASS；被白名单/排除规则/内部类规则跳过、
已有行号、无需修改或处理失败后保留原始字节的 CLASS 均不输出，普通资源也不复制。
JAR 只有内部至少一个 CLASS 实际修改后才输出**完整 JAR**，包内未修改的类和资源仍然保留，
签名处理遵循原有逻辑；仅签名/清单清理不算 CLASS 修改。直接输入单个 JAR 时规则相同。
与 `--class-only` 合用时 JAR 完全不输出。
输出文件保持相对目录结构；本选项不会删除或覆盖被跳过文件对应的已有输出，
如需输出目录仅包含本次修改结果，请使用新的空目录。

#### 只处理 CLASS 文件，忽略 JAR
```bash
java -jar ClassLinefix.jar -i ./input -o ./output --class-only

# 可与白名单和排除规则组合
java -jar ClassLinefix.jar -i ./input -o ./output -c -w "com.api.doc" -p "com.api.doc.internal"
```

`-c` / `--class-only` 是无参数开关，默认关闭。启用后仅处理目录中的独立 `.class` 文件，
所有 `.jar` 文件整包原样复制到输出位置，不读取内部 CLASS，也不清理签名或清单。
直接以单个 JAR 为输入时同样只原样复制；已有输出 JAR 会被原始输入覆盖。
白名单、排除规则和 `-s` 继续应用于独立 CLASS 文件，其他资源文件保持原有复制行为。

#### 排除包含内部类的文件
```bash
# 跳过内部类
java -jar ClassLinefix.jar -i ./input-jars -o ./output-jars -s true
```

#### 排除特定包
```bash
# 排除指定包的处理
java -jar ClassLinefix.jar -i ./input-jars -o ./output-jars -p "com.example.exclude,org.test"
```

#### 只处理白名单中的包或类
```bash
# 只处理 com.api.doc 包及其子包
java -jar ClassLinefix.jar -i ./input -o ./output -w "com.api.doc"

# 多个包/类；同时命中 -p 时，排除规则优先
java -jar ClassLinefix.jar -i ./input -o ./output --whitelist "com.api.doc,weaver.hrm.User" -p "com.api.doc.internal"
```

#### 复合选项使用
```bash
# 跳过内部类且排除特定包
java -jar ClassLinefix.jar -i ./input-jars -o ./output-jars -s true -p "com.obfuscated"
```



## 配置选项详解

### 内部类处理 (`--skip-inner`)
- **默认值**: `false`
- **作用**: 控制是否处理内部类和包含内部类的外部类

### 包排除 (`--packages`)
- **格式**: 逗号分隔的包名或类名列表
- **支持模式**:
  - 完整类名: `com.example.MyClass`
  - 包名前缀: `com.example`（会排除该包下所有类）
- **使用场景**: 排除已知有问题的包或不需要调试的第三方库

### 包白名单 (`-w` / `--whitelist`)
- **默认行为**: 不传此参数时不限制处理范围，保持原有行为；显式传入空列表会报错。
- **格式**: 逗号分隔，去掉每项首尾空白、忽略空项并去重，与 `-p` 一致。
- **匹配规则**: 与 `-p` 共用同一实现。完整类名精确匹配；最后一段以小写字母开头的条目按包名处理，以 `包名.` 为边界匹配该包及子包。匹配区分大小写，不支持通配符，参数使用点分名称。
- **边界示例**: `com.example` 不匹配 `com.examples.Foo`；`com.example.Foo` 不自动匹配 `com.example.Foo$Inner`。
- **优先级**: 先满足白名单，再应用 `-p` 排除；`-s` 和已有行号跳过规则继续生效。
- **输出**: 白名单外或被排除的独立 CLASS 文件、JAR 内的 CLASS 条目均保留原始字节，不会删除；过滤依据字节码中的类名，文件路径不影响匹配。JAR 的签名清理仍遵循原有逻辑。

### 输入输出目录
- **输入目录** (`--input`): 包含JAR和CLASS文件的源目录
- **输出目录** (`--output`): 处理后文件的目标目录
- **目录结构**: 输出目录会完全保持输入目录的结构

## 工作原理

### 处理流程
1. **扫描输入目录**: 递归查找所有JAR和CLASS文件
2. **文件分析**: 检查每个文件是否已包含行号信息
3. **内部类检测**: 根据配置决定是否跳过内部类和包含内部类的文件
4. **包过滤**: 检查是否匹配白名单（如指定），再应用排除包列表
5. **策略选择**: 根据文件特征自动选择最适合的恢复策略
6. **行号恢复**: 应用选定的策略添加行号信息
7. **结构保持**: 在输出目录中重建相同的目录结构

### 安全性和兼容性
- **已有行号检测**: 自动跳过已包含行号信息的文件
- **智能过滤**: 内部类检测和包排除机制
- **字节码完整性**: 确保不破坏原始字节码的逻辑结构
- **跳转目标保护**: 智能处理现有的标签和跳转指令，避免破坏控制流
- **内部类安全**: 可选的内部类跳过机制，避免处理复杂的嵌套结构
- **错误恢复**: 处理失败时保持原始文件不变



## 常见问题


**Q: 处理后的JAR文件无法运行**

A: 通过报错确认具体是哪些类存在问题，可暂时使用-p参数排除这些类。

**Q: 某些代码依然没有行号信息**

A: 静态代码块没有添加行号信息

**Q: 某些文件被跳过**

A: 工具会自动跳过已包含行号信息的文件，这是正常行为。

**Q: 程序一直循环卡死**

A: 输出目录不能为输入目录的子目录

**Q: 调试时有时会乱跳行号**

A：行号恢复不能完美复原

# 帆软行号恢复

未恢复行号前帆软不能正常调试
![](https://github.com/user-attachments/assets/93b3ef73-a058-4449-a1dc-14c645c78c7f)
只能打方法断点，行断点显示不可用

将工具复制到帆软的`WEB-INF`下，重命名lib为lib2然后执行下面命令

```bash
java -jar ClassLinefix-1.0.0.jar -i lib2 -o lib -p com.fr.license.function,com.fr.plugin.bridge,com.fr.plugin.manage,com.fr.general.GeneralContext,com.fr.general.GeneralUtils
```
此处必须设置`-p`排除某些类才行

![](images/fab2b3c1-743c-467c-ba42-854f5cfcbe3b.png)
默认为INFO输出详细日志在log目录下

![](images/b84b8c55-1b28-40b3-a928-48a52b4b225e.png)
使用恢复行号后的jar运行程序，然后将恢复行号的lib加入idea依赖可以看到成功断点


## 构建与自动发布

本地使用 JDK 8 或更高版本、Maven 3 构建：

```bash
mvn clean verify
java -jar target/ClassLinefix-1.0.0.jar --help
```

- **CI**：推送到 `master` 或向 `master` 提交 PR 时，在 Java 8 和 Java 17 上运行测试、打包和 JAR 启动检查。
- **发布**：进入仓库 **Actions → Release → Run workflow**，选择 `master`，在 `tag` 中输入如 `v1.1.0`，手动触发。
- 工作流只接受 `vX.Y.Z`（数字无前导零），拒绝已存在的 tag 或 Release。测试通过后，为本次运行的提交创建 tag 并发布 Release，附带可直接运行的 `ClassLinefix-X.Y.Z.jar` 和 SHA-256 校验文件。
- Maven 构建版本及 JAR 显示版本使用输入 tag（去掉 `v`）；版本变更仅用于本次构建，不自动提交回 `master`。普通 push 不会发布 Release。
- 使用内置 `GITHUB_TOKEN` 的 `contents: write` 权限，无需额外配置发布密钥。Fork 仓库如提示 Actions 未启用，先在 Actions 页面启用工作流。
- 发布先创建带附件的草稿，再公开；若发布中断留下同版本草稿或 tag，请核对后清理该失败版本或改用新 tag，不会自动覆盖已有版本。
