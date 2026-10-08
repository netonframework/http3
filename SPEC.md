# http3 — 规格说明（SPEC）

本仓库原是 `http` 仓库中的 `:http3` 与 `:http3-interop` 模块，2026-09-29 按所有者决定独立（提交历史随文件保留）。通用类型（Request、
Response、HeaderMap、Uri、Method、StatusCode、Body 等）与 HTTP/1.1、HTTP/2 仍在 `com.netonstream:http`，见该仓库的 SPEC；QUIC 与 TLS 在
`com.netonstream:quic`，见其 SPEC。

> **编号对照**：下文 §2 即原 `http` SPEC 的 §5，§3 即原 §6 中 HTTP/3 的部分，§4 即原 §11 中 HTTP/3 的各段记录。记录原文中的"§5""§6""§11"
> 分别指本文件的 §2、§3、§4；"§1""§3.9""§4.1""§8"等其他编号指 `http` 仓库 SPEC 的对应章节。

## 1. 产物与分层

| 产物 | 坐标 | 包 | 依赖 |
|---|---|---|---|
| HTTP/3 | `com.netonstream:http3` | `neton.http.h3` | `com.netonstream:http`、`com.netonstream:quic` |

- **为何独立**：只用 HTTP/1.1 或 HTTP/2 的使用者不必带上 QUIC 与 OpenSSL；HTTP/3 随 quic 的版本演进，发版节奏与已稳定的 `http` 不同；目标平台为
  `http` 与 `quic` 的交集（无 32 位 Android）。
- **分层**：`neton.http.h3` 的协议核心（帧、QPACK、流状态机、薄 QUIC 接口上的连接层）在 commonMain，只依赖 `com.netonstream:http`；接到
  `com.netonstream:quic` 的适配在 nativeMain。`http3-interop` 为互通对端与脚本，不发布。
- **与 `http` 的共享面**：HPACK / QPACK 共用的霍夫曼编解码经 `neton.http.internal.HuffmanCodec`（`@InternalHttpApi`，须显式启用）。
- **构建**：`com.netonstream:http:0.1.0` 取自 Maven Central；`com.netonstream:quic` / `quic-testkit` 在 quic 发布之前以
  `includeBuild("../quic")` 取自同级仓库（2026-09-30 起不再用 mavenLocal）。

## 2. HTTP/3（复刻 `h3` 0.0.8，在 `neton.quic` 上）

> **状态：四层均已实现并验收（2026-09-29）：第 1 层（纯编解码，阶段 A）、第 2 层（连接层接在薄 QUIC 接口上、以内存替身测试，阶段 B）、
> 第 3 层（接到 `neton.quic` 的端到端测试，阶段 C）与第 4 层（真实 TLS 1.3 上与参考 h3 0.0.8 双向互通、与 aioquic 互通、h3spec 49 / 49，
> 阶段 D）。** 本节的 ✅ 表示"已与参考对照、决定照做"；实现与验收记录见 §11。GREASE 帧已改为紧跟消息头（⚖️，§4 末），aioquic 开启 GREASE
> 亦双向互通。仍未完成：quiche / curl 未测，真实 TLS 下丢包与乱序的互通、性能对照未做。

- **首版范围（评审确定，2026-09-29）**：
  - 客户端与服务端；普通请求、流式消息体、trailer、取消（请求流 reset / stop）、GOAWAY 与优雅关闭。
  - QPACK：编码只用静态表与字面量（含霍夫曼），**通告动态表容量为 0**；解码遇到动态引用 → QPACK_DECOMPRESSION_FAILED。编码器 / 解码器
    单向流**照常打开并读取**，其上的指令按协议校验（超出容量的插入 → QPACK_ENCODER_STREAM_ERROR；Insert Count Increment 为 0 →
    QPACK_DECODER_STREAM_ERROR），不得忽略这两条流。
  - 关键流（控制流、QPACK 编码器 / 解码器流）被关闭或复位 → H3_CLOSED_CRITICAL_STREAM 关闭整个连接；普通请求流的取消只影响该请求。
  - 请求流被阻塞（对端不读、流量控制）时，控制流与其他请求流仍须推进。
- **首版不做**：0-RTT、服务端推送（PUSH_PROMISE 只解析、按参考忽略 MAX_PUSH_ID / CANCEL_PUSH）、WebTransport、HTTP Datagram（RFC 9297）、
  动态 QPACK。下文中 h3-datagram / h3-webtransport 一项随之移出首版。
- **开发与验收分层**：
  1. 纯编解码（varint、帧、QPACK 静态 + 字面量、SETTINGS）与状态机（控制流、请求流、GOAWAY、错误映射）：输入字节 / 事件驱动测试，不依赖 QUIC。
  2. 连接层接在一个薄的 QUIC 接口上；测试用内存中的流对替身，覆盖多流协调、取消、慢消费者、资源上限。
  3. 接到 `neton.quic`（当前握手为 TLS 测试替身）做端到端测试。
  4. 最终验收（真实 TLS 到位后）：与外部 HTTP/3 实现双向互通（如 curl `--http3`、quiche / nghttp3 / quinn-h3 的客户端与服务端）与 h3spec；
     **quinn 的 QUIC 互通不等于 HTTP/3 互通**，两者分别验收。

- **QUIC 接口**：参考以 trait 抽象 QUIC（`H3/h3/src/quic.rs`：`Connection`、`OpenStreams`、`SendStream`、`RecvStream`、`BidiStream`），由 h3-quinn 适配。本库直接使用 `neton.quic` 的连接与流 ⚖️，另保留一层薄接口，以便测试时替换。
- **帧**：DATA、HEADERS、CANCEL_PUSH、SETTINGS、PUSH_PROMISE、GOAWAY、MAX_PUSH_ID、WEBTRANSPORT_BI_STREAM ✅。
  - HTTP/2 专有类型 → H3_FRAME_UNEXPECTED；未知类型与 GREASE 帧跳过。
  - DATA 流式处理，不缓存负载。
  - HEADERS：参考先把整个帧缓存进 `BufList` 再解码，且不限制帧长。本库的限制见下文"头部的三种上限" ⚖️。
- **SETTINGS** ✅：最多 8 项，重复 → 错误，HTTP/2 保留 ID → H3_SETTINGS_ERROR，未知 ID 忽略；GREASE 设置；控制流上 SETTINGS 必须是第一帧，第二个 SETTINGS 以及 DATA / HEADERS → H3_FRAME_UNEXPECTED。
- **单向流** ✅：
  - 控制、推送、QPACK 编码器、QPACK 解码器、WebTransport。
  - 连接建立时打开控制流（带 SETTINGS）与 QPACK 的两条流，并在后台发送 GREASE 流。
  - 第二条控制 / 编码器 / 解码器流 → H3_STREAM_CREATION_ERROR；未知类型 → STOP_SENDING。
  - 控制流关闭 → H3_CLOSED_CRITICAL_STREAM。
- **QPACK**：
  - 参考只接入了无状态模式：编码只用静态表；解码遇到动态引用 → QPACK_DECOMPRESSION_FAILED；全部字符串霍夫曼编码。首版对等 ✅。
  - 参考中完整的动态表代码只有单元测试、未接入连接，本库首版不接入 ✅，作为后续项。
- **请求生命周期** ✅：
  - 服务端第一帧必须是 HEADERS；头部过大时自动回 431；格式错误 → H3_MESSAGE_ERROR。
  - 消息体为 DATA，随后可有作为 trailer 的 HEADERS。
  - `finish` 前每个连接发送一个 GREASE 帧。
- **GOAWAY** ⚖️：
  - 服务端 `shutdown(maxRequests)`；**ID 大于或等于** GOAWAY 所带 ID 的请求 → H3_REQUEST_REJECTED（RFC 9114 §5.2：GOAWAY 携带的是第一个
    不会处理的流 ID）。参考 h3 0.0.8 写作 `send_id() > max_id`，会接受边界上的请求，影响客户端的安全重试判断；本库按 RFC（评审发现，2026-09-29）。
  - 客户端 `shutdown`。
  - ID 比上次大 → H3_ID_ERROR。
- **错误码**：H3_DATAGRAM_ERROR、0x100–0x110、QPACK 0x200–0x202 ✅。
- **h3-datagram（RFC 9297）与 h3-webtransport（仅服务端）**：参考标为实验性；**本库首版不做**（见上方首版范围）。
- **配置**（`config.rs`）：
  - `sendGrease` true ✅；`enableExtendedConnect` / `enableWebtransport` / `enableDatagram` false ✅；`maxWebtransportSessions` 0 ✅。
  - **头部的三种上限**（⚖️；参考只有 `max_field_section_size`，默认不设上限）：HEADERS 帧编码后的字节数与 QPACK 解码后的字段段大小是两个不同的量，
    分别设限，并在收包与解码过程中逐步检查（超出即停止，不先整体分配再检查）：
    - `maxHeadersFrameSize`：HEADERS 帧负载（编码后）的字节上限，默认 64 KiB。帧头声明的长度超出即拒绝，不读取负载。
    - `maxFieldSectionSize`：解码后字段段的大小（按 RFC 9114 的计法：每个字段名 + 值 + 32），默认 64 KiB。也通过
      SETTINGS_MAX_FIELD_SECTION_SIZE 通告给对端；解码时逐字段累加，超出即停止解码。
    - `maxFieldCount`：字段个数上限，默认 100（与 HTTP/1.1 的头部个数一致）；解码时逐个计数。
    - 超出任一上限：服务端回 431 并按参考的方式结束该请求流；客户端 → 错误。
    - HTTP/2 的对应物：`maxHeaderListSize`（解码后）与 CONTINUATION 帧数上限（编码后）已按参考分开（§4.1），HTTP/1.1 的头部段与头部个数也已分开（§3.7）。
- **参考的缺口**（首版照参考、在此列明）：
  - 不支持服务端推送（解析存在，服务端忽略 MAX_PUSH_ID / CANCEL_PUSH）。
  - 不支持 1xx。
  - 头部校验弱：不查重复伪头部、普通头部之后的伪头部、连接相关头部、content-length。**本库 ⚖️ 补上这四项校验**（与 HTTP/2 的校验一致），理由是安全。
  - 编码前字段名小写化、cookie 拆分、0-RTT 保存的设置等 TODO：首版照参考，逐项在实施时评估。
- **并发模型**：参考没有中心驱动任务，共享状态用 `Arc` / 原子量，并用 tokio 的无界通道跟踪请求完成。本库中连接在所属反应器上，不加锁 ⚖️。


## 3. 测试（移植清单与外部一致性）

| 来源 | 数量 | 内容 |
|---|---|---|
| h3 | 19 + 38 + 约 113 | 连接（设置、控制流错误、GOAWAY）、请求、QPACK 与帧 |
| 外部一致性 | h2spec v2.1.1、h3spec v0.1.13 | 纳入验收；不继承参考的跳过项，见下 |

- **跳过项不继承**：参考在 CI 中跳过的外部一致性用例，不自动成为本库的验收豁免。每一项在本表中记录跳过的原因、对本库是否适用、替代验证；
  默认必须通过。h3spec 中参考跳过的五项：
  | 用例 | 参考跳过的原因 | 对本库 | 验收 |
  |---|---|---|---|
  | 请求流上的 CANCEL_PUSH | 参考不校验 | 适用：应以 H3_FRAME_UNEXPECTED 拒绝 | 必须通过 |
  | QPACK 容量上限 | 参考未接入动态表 | 适用：本库通告容量 0，对端写入超出容量的指令应以 QPACK_ENCODER_STREAM_ERROR 拒绝 | 必须通过；另加单元测试 |
  | Insert Count Increment 为 0 | 同上 | 适用：应以 QPACK_DECODER_STREAM_ERROR 拒绝 | 必须通过；另加单元测试 |
  | 重复的伪头部 | 参考不校验 | 适用：本库补上此项校验（§5） | 必须通过 |
  | missing_extension TLS 告警 | 取决于 TLS 实现 | 取决于 `quic` 的 TLS 选择（`quic` SPEC §4） | TLS 选定后必须通过；在此之前记为未验证，不记为豁免。**已通过**（quic 真实 TLS，§11 阶段 D） |
  h2spec 在参考中没有跳过项，本库全部必须通过。h3spec v0.1.13 对本库服务端 49 / 49 通过，上表五项均在其中（§11 阶段 D）。

## 4. 实施记录

**HTTP/3 阶段 A：纯协议核心（2026-09-29，h3 0.0.8，§5 分层验收第 1 层）**
- 范围：不做 I/O、不接 QUIC，字节 / 事件进、字节 / 事件出。**连接层（控制流与请求流状态机、GOAWAY、关键流规则、431 应答）、QUIC 接入
  （`neton.quic` 与薄接口）、端到端测试与外部互通（curl `--http3`、quiche / nghttp3 / quinn-h3、h3spec）均未做**，分别是第 2–4 层。
- 已实现（模块 `:http3`，`neton.http.h3`，全部在 commonMain）：
  - `proto.VarInt`（`proto/varint.rs`、`coding.rs`）：值为 `Long`，输入不足返回 `INCOMPLETE`（-1），不抛异常、不分配。
  - `Code` 与 `H3Exception`（`error/codes.rs`）：H3_DATAGRAM_ERROR、0x100–0x110、QPACK 0x200–0x202；各层错误都是带 `code` 的
    `H3Exception` 子类，只在出错路径上抛出，"输入不足"一律用返回值表示。
  - 帧（`proto/frame.rs`）：DATA、HEADERS、CANCEL_PUSH、SETTINGS、PUSH_PROMISE（只解析）、GOAWAY、MAX_PUSH_ID、GREASE；`FrameDecoder`
    （`frame.rs` 的 `FrameDecoder` 与 `Frame::decode`）与 `FrameStream`（`FrameStream` 的无 I/O 版本：`onData` / `onEnd` 输入，
    `nextFrame` / `nextData` 输出，DATA 负载按到达分段交出、不整体缓存）。
  - SETTINGS：至多 8 项，重复 → H3_SETTINGS_ERROR，HTTP/2 保留 ID（0、2–5）→ H3_SETTINGS_ERROR，未知 ID 与 GREASE 忽略，发送侧可加 GREASE。
  - 单向流（`proto/stream.rs`、`stream.rs` 的编解码部分）：`StreamType`、`StreamId`、`UniStreamHeader`（控制流带 SETTINGS、QPACK 编码器 /
    解码器流）、`StreamTypeDecoder`（流类型与推送 ID，可分段到达）、`WriteBuf`（帧头与负载分开，负载不复制）。
  - QPACK（`qpack/`）：前缀整数、前缀字符串（霍夫曼）、`HeaderField`、静态表、字段行表示（`block.rs`）、无状态编码器 / 解码器、编码器 /
    解码器流指令（`stream.rs`）及对端两条流的接收校验（`EncoderStreamReceiver` / `DecoderStreamReceiver`）。
  - 头部（`proto/headers.rs`）：`Header` / `Pseudo` / `Protocol`，请求与响应的构造与拆解，按字段行逐行校验，类型化的 QPACK 编码。
- 霍夫曼：复用 `neton.http.h2.hpack` 的实现，不另写一份。`:http` 新增 `neton.http.internal.HuffmanCodec`（只转调 HPACK 的
  `huffmanDecode` / `huffmanEncode` / `huffmanEncodedLength`），以 `@RequiresOptIn(level = ERROR)` 的 `@InternalHttpApi` 标注，KDoc
  说明仅供本仓库模块使用；`:http` 其余代码未改，全量测试仍为 1,197 个（14 个忽略）、0 失败。
- 测试：`:http3` macOS 160 个全部通过（`./gradlew :http3:macosArm64Test`），linuxX64 编译通过。参考测试逐文件：
  | 参考文件 | 参考测试数 | 移植 | 不适用 | 本库另加 |
  |---|---|---|---|---|
  | `proto/frame.rs` | 10 | 10 | 0 | 15（限额、单 varint 帧、HTTP/2 帧、未知帧边到边丢弃、SETTINGS 规则） |
  | `frame.rs` | 12 | 12（2 个按 ⚖️ 改断言） | 0 | 4（每个字节边界拆分、流在未知帧中结束、干净结束、大 DATA 流式） |
  | `stream.rs` | 6 | 5 | 1 | 7（`WriteBuf`、流类型解码、流 ID） |
  | `buf.rs` | 1 | 0 | 1 | — |
  | `proto/headers.rs` | 8 | 8 | 0 | 10（四项 ⚖️ 校验、非法名 / 值、请求构造、QPACK 往返、校验与限额同时作用） |
  | `qpack/prefix_int.rs` | 8 | 8 | 0 | 2 |
  | `qpack/prefix_string/mod.rs` | 5 | 5 | 0 | 2 |
  | `qpack/prefix_string/decode.rs` | 3 | 2（均按 ⚖️ 改断言） | 1 | — |
  | `qpack/prefix_string/encode.rs` | 5 | 4 | 1 | — |
  | `qpack/field.rs` | 2 | 2 | 0 | — |
  | `qpack/static_.rs` | 6 | 6 | 0 | 1（每一项按字节、按类型化名字查找） |
  | `qpack/block.rs` | 9 | 9 | 0 | 2 |
  | `qpack/encoder.rs` | 14 | 7（解码器流 5 个中 3 个按 ⚖️ 改断言） | 7 | — |
  | `qpack/decoder.rs` | 17 | 7（3 个按 ⚖️ 改断言） | 10 | — |
  | `qpack/stream.rs` | 7 | 7 | 0 | — |
  | `qpack/tests.rs` | 4 | 2 | 2 | — |
  | `qpack/dynamic.rs` | 42 | 0 | 42 | — |
  | `qpack/vas.rs` | 11 | 0 | 11 | — |
  | 模糊测试 `fuzz/fuzz_targets/fuzz_varint.rs` | 1 | 1（2 个测试） | 0 | — |
  | 合计 | 170 + 1 | 94 + 1 | 76 | |
  - QPACK 编解码与流的另加测试在 `CodecTest`（10 个）与 `QpackStreamsTest`（7 个），`VarIntTest` 3 个、`CodeTest` 1 个（参考的
    `varint.rs`、`coding.rs`、`proto/stream.rs`、`error/codes.rs` 没有测试）。
  - `fuzz_varint` 移植为 `VarIntFuzzTest`（风格同 `ParseFuzzTest`）：参考语料 116 个文件（`VarInt::decode` 至多读 8 字节，故保留每个文件的
    前 8 字节与长度）加固定种子的 20,000 个随机输入；断言不越界、`INCOMPLETE` 恰在输入短于首字节声明的长度时出现、消耗字节数正确、
    重新编码为最短形式后值不变。
  - 参考中 `tests/connection.rs`（19）与 `tests/request.rs`（38）属连接层，留到第 2 层。
- 不适用的参考测试（76 个）及理由：
  - `qpack/dynamic.rs` 42、`qpack/vas.rs` 11：动态 QPACK 不在首版（§5 首版不做）。
  - `qpack/encoder.rs` 的 `encode_static_nameref`、`encode_static_nameref_indexed_in_dynamic`、`encode_dynamic_insert`、
    `encode_dynamic_insert_nameref`、`encode_literal_nameref`、`encode_literal_postbase_nameref`、`encode_with_header_block`：
    都在动态表中插入或引用。对应的无动态表行为另有测试（如 `location: /bar` 编为带静态名字引用的字面量）。
  - `qpack/decoder.rs` 的 `test_insert_field_with_name_ref_into_dynamic_table`、`test_insert_field_without_name_ref`、
    `test_duplicate_field`、`test_dynamic_table_size_update`、`decode_indexed_header_field`、`decode_post_base_indexed`、
    `decode_name_ref_header_field`、`decode_post_base_name_ref_header_field`、`decode_single_pass_encoded`、
    `largest_ref_greater_than_max_entries`：都要求插入成功或解码动态引用；容量 0 下它们的输入分别以 QPACK_ENCODER_STREAM_ERROR /
    QPACK_DECOMPRESSION_FAILED 拒绝，另有测试。
  - `qpack/tests.rs` 的 `blocked_header`、`codec_table_full`：动态表（阻塞流、表满）。
  - `qpack/prefix_string/decode.rs` 的 `test_read_bits`、`encode.rs` 的 `test_set_bits`：测试参考逐位编解码器的内部函数；本库用 HPACK 的
    查表实现，没有这两个函数（其行为由其余霍夫曼测试覆盖）。
  - `stream.rs` 的 `write_wt_uni_header`：WebTransport 不在首版。
  - `buf.rs` 的 `cursor_advance`：`BufList` 游标。本库收到的字节进一个 neton-io `Buffer`（传输层可直接读入），没有 `BufList`。
- ⚖️ 有意不同（均有测试）：
  - 帧：
    - 未知类型（含 GREASE）的帧按到达逐段丢弃，不缓存；参考先把整个帧（任意声明长度）缓存再跳过。
    - HEADERS 与 PUSH_PROMISE 的声明长度超过 `maxHeadersFrameSize`（默认 64 KiB）→ `FrameError.HeadersTooLarge`，读到帧头即拒绝，不读负载
      （§5"头部的三种上限"）。服务端据此回 431 还是复位由连接层决定（第 2 层）；异常所带的码为 H3_EXCESSIVE_LOAD，仅在连接层选择复位时使用。
    - SETTINGS 负载上限 16 KiB（`MAX_SETTINGS_PAYLOAD`，超出 → H3_EXCESSIVE_LOAD）；参考不设上限。
    - CANCEL_PUSH / GOAWAY / MAX_PUSH_ID 的负载必须恰为一个 varint，多余或缺少字节 → H3_FRAME_ERROR（RFC 9114 §7.1），声明长度超过 8 读到
      帧头即拒绝。**发现参考的缺陷**：参考只读出 varint，负载中多余的字节留在流中被当作下一帧解析；负载为空时返回 `Incomplete(0)`，
      解码器从此永远等待。
    - HTTP/2 专有帧类型读到帧头即以 H3_FRAME_UNEXPECTED 拒绝，不等负载到齐。
    - `WEBTRANSPORT_BI_STREAM`（0x41）没有 WebTransport 时按未知帧（带长度）跳过；`WEBTRANSPORT_UNI` 流类型按未知类型处理，不再读会话 ID。
  - `FrameStream`：收到的字节进同一个 `Buffer`，DATA 负载在读取时把已到达的字节一并交出，不保留参考 `BufList` 的分块边界。
    `poll_data_split` 与 `poll_data_eos_but_buffered_data` 因此改为断言交出的字节拼起来是 `body`（另测分块在两次读取之间到达时逐块交出）。
    流结束时 DATA 负载不足一律为 UnexpectedEnd（参考在缓冲为空时静默返回 `None`）。
  - `Settings.get` 只查已有条目；参考扫描整个定长数组，`get(0)` 会命中空槽的 0。
  - 霍夫曼（共用 HPACK 实现）执行 RFC 7541 §5.2：填充超过 7 位或含 EOS 即错误，参考不查。参考 `test_decode_single_value` 中 18 个向量以
    一整个字节的 1 作填充，`test_decode_all_code_joined` 以完整的 30 位 EOS 结尾，本库均判为错误；移植的测试断言这一点，并断言去掉
    多余填充 / EOS 后解出原符号（向量由参考源码中的表达式求值得到，逐字节一致）。
  - QPACK 解码：
    - Required Insert Count 非 0 即 QPACK_DECOMPRESSION_FAILED（`MissingRefs(编码值)`），即使字段行都不引用动态表（RFC 9204 §4.5.1.1，
      最大容量为 0 时编码值必须为 0）；参考的无状态解码忽略前缀。`largest_ref_too_big` 因此断言 `MissingRefs(9)`（参考的有状态解码器
      报 `MissingRefs(8)`）。Base 任意（§4.5.1.2）。
    - `maxFieldSectionSize`（默认 64 KiB，名 + 值 + 32 累加）与 `maxFieldCount`（默认 100）逐行检查：个数在解码该行之前检查，大小在该行
      解出后、交给接收者之前检查，超出即停止，其后的字节不再解码；参考只有前者。两者为 `isLimit` 错误（`HeaderTooLong` /
      `TooManyFields`），供连接层回 431，不是 QPACK_DECOMPRESSION_FAILED。
    - 解码逐行交给 `FieldSink`，名与值是输入、静态表或复用的临时数组中的区间，每行不分配；参考先解出 `Vec<HeaderField>`。
  - QPACK 编码器流（对端编码器 → 本端，本端通告容量 0）：Set Dynamic Table Capacity 大于 0 → QPACK_ENCODER_STREAM_ERROR；任何插入 →
    QPACK_ENCODER_STREAM_ERROR（静态索引越界报 `InvalidStaticIndex`，动态索引报 `BadRelativeIndex`，其余为 `InsertionExceedsCapacity`）；
    Duplicate → `BadRelativeIndex`。插入在名字引用（或名字长度）到齐时即拒绝，不等值字符串，避免对端声明巨长字符串让流缓存；参考解析完整
    指令。`enc_recv_buf_too_short`（`0b1000_0000` 在参考中等待、在本库立即以 `BadRelativeIndex(0)` 拒绝）与
    `enc_recv_accepts_truncated_messages`（名字长度未到齐时等待、到齐即拒绝；指令编解码本身仍接受截断输入）按此改断言。
  - QPACK 解码器流（对端解码器 → 本端，本端编码器从不插入、从不发送 Required Insert Count 非 0 的字段段）：Insert Count Increment 为 0 →
    QPACK_DECODER_STREAM_ERROR（§4.4.3）；其他增量使 Known Received Count 超过已发送的 0 次插入，同样是错误（§4.4.3）；任何 Section
    Acknowledgment 都是错误（§4.4.1）；Stream Cancellation 合法。参考的 `insert_count` 接受增量 4、`decoder_block_ack` 第一次确认成功，
    本库均为错误，按此改断言。Insert Count Increment 按 64 位解析（参考为 `u8`，大于 64 即报溢出）。
  - 头部四项校验（与 `neton.http.h2` 的解码一致，均为 H3_MESSAGE_ERROR）：重复的伪头部、普通字段之后的伪头部、连接相关字段（connection、
    keep-alive、proxy-connection、transfer-encoding、upgrade，以及值不是 `trailers` 的 te）、content-length（用 h2 的 `parseU64`，至多
    19 位；多个值必须相同；空值拒绝——h2 的 `parseU64` 把空值读作 0，RFC 9110 §8.6 要求至少一位数字）。解析出的值存于
    `Header.contentLength`，供请求流核对 DATA 长度（第 2 层）。校验随解码逐行进行，遇到第一个不合法的行即停止。
- 未决 / 留给第 2 层：HEADERS 过大与两种解码限额在服务端回 431 的具体流程；`Config`（`config.rs`）与本端 SETTINGS 的生成（通告
  SETTINGS_MAX_FIELD_SECTION_SIZE、QPACK 容量 0）；控制流上 SETTINGS 必须为第一帧、帧在各类流上的允许性等流状态规则。


**HTTP/3 阶段 B：连接层（2026-09-29，h3 0.0.8，§5 分层验收第 2 层）**
- 范围：连接层（控制流与 QPACK 流、关键流、请求流、GOAWAY、错误映射、服务端 / 客户端 API）接在一层薄的 QUIC 接口上，以内存 QUIC
  替身测试。**未做：接入 `com.netonstream:quic`（阶段 C，第 3 层）；与外部 HTTP/3 实现互通与 h3spec（阶段 D，第 4 层，需要真实 TLS）。**
  本阶段的一切结论只在内存替身上成立。
- 薄 QUIC 接口（`neton.http.h3.quic`，`quic.rs`）：
  - `Connection`（`acceptUni`、`acceptBi`、`opener`）、`OpenStreams`（`openBi`、`openUni`、`close(code, reason)`）、`SendStream`（`sendId`、
    `write(Bytes)`、`finish`、`reset`、`stopped`）、`RecvStream`（`recvId`、`read(): Bytes?`、`stopSending`）、`BidiStream`（`split`）。
  - ⚖️ 协程形态：`poll_*` 为挂起函数；"会阻塞"（流控、尚无数据）一律挂起，不抛异常。`Result` 改为异常：连接已关闭为
    `ConnectionErrorIncoming`（ApplicationClose / Timeout / InternalError / Undefined），流操作失败为 `StreamErrorIncoming`（ConnectionLost /
    StreamTerminated / Unknown），与参考的两个枚举逐项对应。参考的 `send_data` + `poll_ready`（排入一个 `WriteBuf` 再等它发完）合为一个
    `write`，写完全部字节才返回。
  - 比参考多一个 `SendStream.stopped()`（等到对端 STOP_SENDING 返回其码，数据处理完返回 null）：不写数据也能发现本端关键流被对端叫停。
  - 阶段 C 的映射（neton.quic 的 `Connection` / `SendStream` / `RecvStream` 可直接实现）：`openUni` / `openBi` / `acceptUni` / `acceptBi`
    （一个 `Pair<SendStream, RecvStream>` 即一个 `BidiStream`）；`writeChunk`、`finish`、`reset`、`stopped`；`readChunk` 取其字节（结束为
    null）、`stop`；`Connection.close(code, reason)`。错误按 h3-quinn 的 `convert_connection_error`：ApplicationClosed → ApplicationClose，
    TimedOut → Timeout，其余 → Undefined；`WriteError.Stopped` / `ReadError.Reset` → StreamTerminated；`*.ConnectionLost` → ConnectionLost；
    其他流错误 → Unknown。
- 内存 QUIC 替身（测试源集 `nativeTest/.../MemoryQuic.kt`，`memoryQuicPair`）：
  - 两个相连的端点；双向与单向流；FIN；RESET_STREAM（复位前已收到的字节仍可读，其后报复位，同 quinn）；STOP_SENDING（丢弃未读字节，写方报
    StreamTerminated）。
  - 每个方向一个有界缓冲（默认 64 KiB，可调）：写满即挂起，直到读方取走——类流控的背压。
  - 以应用错误码关闭连接（本端报 Undefined(LocallyClosed)，对端报 ApplicationClose，并记录由谁、以何码关闭）；可选空闲超时（双方报 Timeout）。
  - 与 QUIC 一致：流在打开方第一次发送（数据、FIN 或复位）时才对对端可见；同类流按 ID 顺序被接受（使用流 N 即隐式打开更小的流）。
  - 替身自身 6 个测试（`MemoryQuicTest`）。替身同步投递、即时关闭；真实 QUIC 上的时序（丢包、乱序、关闭延迟）留给第 3 层。
- 连接核心（`ConnectionInner`；`connection.rs`、`shared_state.rs`、`config.rs`）：
  - 启动时依次打开控制流（首帧 SETTINGS）、QPACK 编码器流、QPACK 解码器流并写入流类型；QPACK 两条流打开失败不算错误（同参考）。
  - 本端 SETTINGS（`Config.toFrame`）：`sendGrease` 时一个 GREASE 设置；SETTINGS_MAX_FIELD_SECTION_SIZE = `maxFieldSectionSize`（默认
    64 KiB）；ENABLE_CONNECT_PROTOCOL、ENABLE_WEBTRANSPORT、H3_DATAGRAM、WEBTRANSPORT_MAX_SESSIONS 为 0（同参考）。QPACK 最大表容量与
    阻塞流数为 0，即默认值，不发送。`Config` 另有 `maxHeadersFrameSize`（64 KiB）与 `maxFieldCount`（100），见 §5 头部的三种上限。
    WebTransport、扩展 CONNECT、Datagram 在构造器上没有开关（⛔ 首版不做），对端的这几项只记录不使用。
  - ⚖️ 驱动：参考没有驱动任务，谁轮询 `accept` / `poll_close` 谁读控制流，QPACK 两条流接受后从不读取。本库由 `run()` 驱动（与
    `neton.http.h2.server.Connection.run` 一致，需 launch）：对端每条单向流一个协程，一条流停滞不影响其他；QPACK 两条流照常读取，指令用
    阶段 A 的 `EncoderStreamReceiver` / `DecoderStreamReceiver` 校验，非法 → QPACK_ENCODER_STREAM_ERROR / QPACK_DECODER_STREAM_ERROR 连接错误。
  - 单向流：第二条控制 / 编码器 / 解码器流 → H3_STREAM_CREATION_ERROR；未知类型（含 GREASE）→ STOP_SENDING(H3_STREAM_CREATION_ERROR)，
    不算连接错误；流头到达前关闭或复位的流被容忍（RFC 9114 §6.2）。⚖️ 推送流：参考静默丢弃；首版不做推送，按 RFC：服务端收到 →
    H3_STREAM_CREATION_ERROR（§6.2.2），客户端收到（从未发送 MAX_PUSH_ID，任何推送 ID 都超限）→ H3_ID_ERROR（§4.6）。
  - 控制流：SETTINGS 必须是第一帧（否则 H3_MISSING_SETTINGS）且只有一个（第二个 → H3_FRAME_UNEXPECTED）；DATA、HEADERS、PUSH_PROMISE →
    H3_FRAME_UNEXPECTED；服务端忽略 MAX_PUSH_ID / CANCEL_PUSH，客户端收到二者 → H3_FRAME_UNEXPECTED（均同参考）；帧格式错误按阶段 A 的码
    （截断 H3_FRAME_ERROR 等）。⚖️ 超过 `maxHeadersFrameSize` 的 HEADERS 在读到帧头时即被拒绝（阶段 A 为 H3_EXCESSIVE_LOAD）；在控制流上
    HEADERS 本就不允许，故按参考对 HEADERS 的处理报：SETTINGS 之前 H3_MISSING_SETTINGS，之后 H3_FRAME_UNEXPECTED。
  - 关键流：对端控制流、编码器流、解码器流结束或复位 → H3_CLOSED_CRITICAL_STREAM。⚖️ 对端对本端这三条流发 STOP_SENDING → 同样是
    H3_CLOSED_CRITICAL_STREAM（RFC 9114 §6.2.1、RFC 9204 §4.2）；参考只在下次写该流时才发现。
  - 对端 SETTINGS 第一次到达时记录，此后发送头部按其 SETTINGS_MAX_FIELD_SECTION_SIZE 检查；到达之前按 RFC 默认值（不限）。
  - GREASE（同参考）：每个连接一条保留类型的单向流（保留帧后 FIN）；每个连接的第一条请求流在 `finish` 前发一个保留帧。
  - ⚖️ 连接错误即时生效：第一个连接错误（无论由连接还是某条流发现）记入共享状态并立即以其码关闭 QUIC 连接；参考要等驱动下次被轮询时
    （`poll_connection_error`）才关闭。此后所有操作报同一个错误（先到者为准，同参考的 `OnceLock`）。
  - 并发模型：连接与其流在所属反应器上，共享状态为普通字段、不加锁（§5）；等待用一个单线程的"通知全部"原语（`Notify`）。
- 请求流（`RequestStreamInner`；`connection.rs` 的 `RequestStream`、`server/`、`client/`）：
  - 接收：可选的 DATA 帧（到达即交出，不整体缓存），可选的 trailer（一个 HEADERS），其后只允许未知帧；其他已知帧 → H3_FRAME_UNEXPECTED
    连接错误，截断的帧 → H3_FRAME_ERROR。trailer 要等到流结束（或下一帧）才交出（同参考）。
  - 收到的头部段逐步按三种上限检查（§5）：HEADERS 帧长在帧头即检查（负载不读），解码大小与字段个数逐行检查；超限为 `StreamError.HeaderTooBig`
    （流错误，不是连接错误）；其他 QPACK 解码错误 → QPACK_DECOMPRESSION_FAILED 连接错误。
  - 服务端第一帧必须是 HEADERS（其他帧 → H3_FRAME_UNEXPECTED）；流在头部之前结束 → 复位 H3_REQUEST_INCOMPLETE；格式错误 → 以
    H3_MESSAGE_ERROR 复位并 STOP_SENDING（同参考）。客户端响应头部：流在其之前结束或首帧不是 HEADERS → H3_FRAME_UNEXPECTED；格式错误或超限
    → STOP_SENDING(H3_REQUEST_CANCELLED)（同参考）。
  - ⚖️ 比参考多的检查：content-length（阶段 A 解析）与收到的 DATA 总长核对，多出或结束时不等 → H3_MESSAGE_ERROR 流错误（RFC 9114 §4.1.2；
    HEAD 请求、1xx / 204 / 304 响应不核对）；trailer 中的伪头部 → H3_MESSAGE_ERROR（参考丢弃伪头部）；请求中的 `:status`，以及未启用扩展
    CONNECT 时的 `:protocol` → H3_MESSAGE_ERROR（RFC 9220 §3）；空的 DATA 帧不结束消息体（参考的 `poll_data` 对它返回 None）。
  - ⚖️ 客户端构造请求头失败（如缺少 authority）只让这个请求失败（`StreamError.Stream(H3_INTERNAL_ERROR)`）；参考将其作为 H3_INTERNAL_ERROR
    连接错误关闭整个连接。
  - 发送：头部 / trailer 编码后大小超过对端 SETTINGS_MAX_FIELD_SECTION_SIZE → `HeaderTooBig`，不发送（RFC 9114 §4.2.2、§7.2.4.2，同参考）；
    客户端此时流已打开，按参考（quinn 丢弃流时 FIN）结束这条空流，服务端于是看到 H3_REQUEST_INCOMPLETE。
  - 取消只影响该请求：`stopStream` 复位发送方向、`stopSending` 叫停接收方向，连接不受影响。Rust 的 `Drop` 改为显式 `close()`：未完成的发送方向
    以 H3_REQUEST_CANCELLED 复位；接收方向在响应已完成时以 H3_NO_ERROR、否则以 H3_REQUEST_CANCELLED 叫停（RFC 9114 §4.1）。
  - `split` 得到发送部分与接收部分，可在不同协程使用（接收部分保留已收到的字节）。
- 431 的具体机制（参考 `ResolvedRequest::resolve`）：请求头部超过任一上限时，服务端在该请求流上发送状态 431 的响应 HEADERS，干净地结束发送
  方向（FIN），再以 STOP_SENDING(H3_NO_ERROR) 停止读取请求（RFC 9114 §4.1：不依赖其余请求内容时可先发完整响应），`resolveRequest` 抛
  `HeaderTooBig(实际, 上限)`，该请求计为结束，连接继续。参考发送同样的 431 HEADERS，其余交给流被丢弃：quinn 丢弃 `SendStream` 即 FIN，丢弃
  `RecvStream` 即以码 0 STOP_SENDING。⚖️ 本库两步都显式进行，STOP_SENDING 用 RFC 建议的 H3_NO_ERROR 而非 0；不发 GREASE 帧。帧长超限时
  （`maxHeadersFrameSize`）负载从未读取。客户端收到超限的响应头部 → `HeaderTooBig` 并 STOP_SENDING(H3_REQUEST_CANCELLED)（同参考）。
- GOAWAY：
  - ⚖️ 服务端 `shutdown(maxRequests)` 发送的 ID 是"第一个不会处理的流 ID"：最后接受的请求流之后再放行 `maxRequests` 个，即最后接受的 ID +
    (`maxRequests` + 1) 个流；尚未接受任何请求时为第一个请求流 + `maxRequests`。ID **大于或等于** GOAWAY ID 的请求流以 H3_REQUEST_REJECTED
    STOP_SENDING 并复位（RFC 9114 §5.2，§5 的 ⚖️ 决定）；参考发送最后一个会处理的 ID 并只拒绝更大的 ID（`>`），`shutdown(0)` 在未接受请求时
    发 GOAWAY(0) 却仍接受流 0。拒绝由 `run()` 完成，不依赖是否调用 `accept()`；已排队未接受的流在 `shutdown` 时一并拒绝。
  - GOAWAY 的 ID 只减不增：新的 ID 不小于已发送的就不再发送（同参考）。收到的 GOAWAY 比上一个大 → H3_ID_ERROR；客户端收到的 GOAWAY 不是
    请求流 ID → H3_ID_ERROR（同参考）。收到或发送 GOAWAY 后连接进入 closing，客户端新请求报 `RemoteClosing`。
  - 优雅结束：`accept()` 在没有进行中的请求时返回 null——收到对端 GOAWAY 后（同参考），或 ⚖️ 本端 GOAWAY 之前的所有流 ID 都已接受后（参考只
    在拒绝某条流时才返回 None，否则一直等待）；返回前发送最后一个 GOAWAY（同参考）。请求在响应 `finish`、`stopStream` 或 `close` 后计为结束
    （参考为 `RequestEnd` 的 `Drop`）。客户端 `shutdown` 发送 GOAWAY(0)（不接受推送）。
- 错误映射（`src/error/`）：`ConnectionError`（Local(LocalError.Application(code, reason)) / Remote(ConnectionErrorIncoming) / Timeout，
  `isH3NoError`）；`StreamError`（Stream(code, reason) / RemoteTerminate(code) / Connection(ConnectionError) / HeaderTooBig / RemoteClosing /
  Undefined，`isH3NoError`）。本端发现的连接错误为 Local 并以其码关闭连接；QUIC 层报来的为 Remote（Timeout 单列），QUIC 层的
  InternalError 以 H3_INTERNAL_ERROR 关闭；流操作失败时 ConnectionLost → `StreamError.Connection`（取已记录的连接错误）、StreamTerminated
  → `RemoteTerminate`、其他 → `Undefined`；帧层错误按阶段 A 的码成为连接错误（`got_frame_error`）。
- 公共 API（Kotlin 协程形态，形如 `neton.http.h2.server` / `client`，消息用 `neton.http` 的 `Request<Unit>` / `Response<Unit>` / `HeaderMap`，
  版本 HTTP/3）：
  - 服务端 `neton.http.h3.server`：`builder()`（`maxFieldSectionSize`、`maxHeadersFrameSize`、`maxFieldCount`、`sendGrease`）、`build(quic)` /
    `newConnection(quic)`；`Connection.run()`（返回连接错误）、`accept(): RequestResolver?`、`shutdown(maxRequests)`、`close()`（H3_NO_ERROR，
    参考的 `Drop`）、`peerSettings`；`RequestResolver.resolveRequest(): Pair<Request<Unit>, RequestStream>`；`RequestStream` 的
    `sendResponse`、`sendData`、`sendTrailers`、`finish`、`stopStream`、`stopSending`、`recvData`、`recvTrailers`、`split`、`close`。
  - 客户端 `neton.http.h3.client`：`builder()`、`build(quic)` / `newClient(quic)` 返回 `Pair<Connection, SendRequest>`；`Connection.run()`
    （参考的 `poll_close` / `wait_idle`，服务端发起的双向流 → H3_STREAM_CREATION_ERROR）、`shutdown`、`isClosing`；`SendRequest.sendRequest`、
    `clone`、`close`（最后一个关闭时以 H3_NO_ERROR 关闭连接，参考的 `Drop`）；`RequestStream` 的 `recvResponse`、`recvData`、`recvTrailers`、
    `sendData`、`sendTrailers`、`finish`、`stopStream`、`stopSending`、`split`、`close`。
- 测试：`:http3` macOS 262 个全部通过（阶段 A 的 160 个 + 本阶段 102 个；`./gradlew :http3:macosArm64Test`，强制重跑共 4 次均通过），
  linuxX64、mingwX64 测试编译通过；`:http` 1,197 个（14 个忽略）、0 失败（本阶段未改 `:http`）。参考测试逐文件：
  | 参考文件 | 参考测试数 | 移植 | 不适用 | 本库另加 |
  |---|---|---|---|---|
  | `src/tests/connection.rs` | 19 | 19（`ConnectionTest`） | 0 | — |
  | `src/tests/request.rs` | 38 | 38（`RequestTest`） | 0 | — |
  | 本库另加（`ConnectionLayerTest`） | — | — | — | 39 |
  | 内存替身（`MemoryQuicTest`） | — | — | — | 6 |
  - 两个参考文件中没有涉及推送、0-RTT、WebTransport、HTTP Datagram、动态 QPACK 的测试，全部适用、全部移植。
  - 移植差异（均在测试中注明）：quinn 端点换成内存替身；Rust 的 `Drop` 换成显式 `close()`（服务端连接、`SendRequest`、请求流）或 `finish()`
    （`request_sequence_check` 中服务端请求流被丢弃即 FIN）；`tokio::join!` / `select!` 换成协程与取消；空闲超时 10–200 ms 换成替身的
    300 ms，沉默的一方等待连接结束而不是睡固定时长；固定的 `sleep`（如 `graceful_shutdown_grace_interval` 的 15 ms、`request_sequence_check`
    的 100 ms、`settings_exchange_*` 的轮询）换成等待条件成立（上限宽松，本机常有高负载）；`graceful_shutdown_closes_when_idle` 的 100 ms
    上限换成测试整体上限并断言服务端恰好处理 6 个请求；`header_too_big_client_error_trailer` 不再断言驱动以 quinn 的空闲超时结束（替身默认
    无超时），改为客户端关闭；`connect`、`server_drop_close`、`header_too_big_client_error_trailer` 因替身即时关闭而先等对端就绪再关闭。
  - 与 §5 的 ⚖️ 决定冲突而改断言的参考测试：无。GOAWAY 边界（`>=`）改变的是 GOAWAY 携带的 ID，参考的三个 `graceful_shutdown_*` 测试只
    观察接受与拒绝的请求，在两种语义下相同；边界本身由另加测试断言（见下）。
  - 另加 39 个：关键流 5（编码器流 FIN、解码器流复位、控制流复位、本端控制流被 STOP_SENDING、客户端侧控制流关闭）；QPACK 流 6（编码器流插入、
    容量超过最大值、Insert Count Increment 为 0、客户端收到 Section Acknowledgment、合法指令不影响连接、第二条编码器流）；其他单向流 3（未知
    类型被 STOP_SENDING、客户端推送流、客户端收到推送流）；控制流规则 6（第二个 SETTINGS、HEADERS、超长 HEADERS、服务端收到 PUSH_PROMISE、
    客户端收到 MAX_PUSH_ID、服务端忽略 MAX_PUSH_ID / CANCEL_PUSH）；431 共 3（字段个数、帧长，以及客户端侧的超长响应头部）；取消隔离 2（客户端、
    服务端取消其一，另一请求与连接不受影响）；阻塞 2（对端不读的响应体、服务端不读的请求体各阻塞一条请求流，另一请求照常完成，控制流上的
    GOAWAY 照常送达）；GOAWAY 6（ID 为第一个不处理的流且边界上的流被拒、未接受请求时 GOAWAY(0) 拒绝流 0、宽限与 ID 只减不增、客户端与服务端
    收到增大的 GOAWAY → H3_ID_ERROR、GOAWAY 后新请求报 RemoteClosing）；消息与流 6（格式错误只是流错误、content-length 不足 / 超出、trailer
    中的伪头部、空 DATA 帧、拆分的流在不同协程中回显）。
- ⚖️ 本阶段的有意不同（均有测试）：GOAWAY 边界 `>=` 与 GOAWAY ID 的计算（§5）；`run()` 驱动、QPACK 流照常读取并校验；本端关键流被
  STOP_SENDING 即 H3_CLOSED_CRITICAL_STREAM；推送流按 RFC 拒绝；控制流上超长 HEADERS 的错误码；第一个连接错误即时关闭连接；431 后显式 FIN 与
  STOP_SENDING(H3_NO_ERROR)；content-length 核对、trailer 伪头部、请求中的 `:status` / `:protocol`、空 DATA 帧；构造请求头失败不关闭连接；
  `accept()` 在本端 GOAWAY 之前的流都已接受后返回 null。
- 未决 / 后续：
  - 阶段 C：以 neton.quic 实现薄接口（映射见上），第 3 层端到端测试（当前 QUIC 握手为 TLS 测试替身）；阶段 D：真实 TLS 到位后与 curl
    `--http3`、quiche / nghttp3 / quinn-h3 双向互通与 h3spec（§6 中 h3spec 五项不继承跳过）。均未做。
  - 性能未测；不支持 1xx（同参考）；客户端不在本地让 ID 不小于收到的 GOAWAY ID 的进行中请求失败，而是等服务端拒绝（同参考）；替身不模拟
    流数上限（MAX_STREAMS）与乱序 / 丢包；编码前字段名小写化与 cookie 拆分仍照参考未做。

**HTTP/3 阶段 C：接入 neton.quic 与端到端测试（2026-09-29，h3 0.0.8 + h3-quinn，§5 分层验收第 3 层）**
- 范围：以 `com.netonstream:quic`（0.1.0-SNAPSHOT）实现薄 QUIC 接口（h3-quinn 的角色），HTTP/3 客户端与服务端在两个 neton.quic 端点之间经本机
  回环 UDP 做端到端测试。**握手仍是 quic-testkit 中的 TLS 测试替身（MockTls），真实 TLS 尚不存在；与外部 HTTP/3 实现互通（curl `--http3`、
  quiche / nghttp3 / quinn-h3）与 h3spec（阶段 D，第 4 层）均未做。HTTP/3 在阶段 D 完成之前不算验收。** 本阶段的结论只说明本库的客户端与
  服务端在 neton.quic 上彼此一致，不构成互通证据。
- 依赖：`:http3` 的 nativeMain 依赖 `com.netonstream:quic`（其驱动只有 native 实现）；commonMain（协议核心与连接层）仍只依赖 `:http`，不含
  QUIC。`com.netonstream:quic-testkit` 只进 nativeTest，生产源集不依赖它。
- 适配（`nativeMain/.../quic/NetonQuic.kt`，h3-quinn `lib.rs`）：`QuicConnection`（`neton.quic.Connection.asH3()`）、`QuicSendStream`、
  `QuicRecvStream`、`QuicBidiStream`，逐调用对应 h3-quinn：
  - `acceptUni` / `acceptBi` / `openUni` / `openBi` 直接转调；`opener()` 返回自身（h3-quinn 克隆连接句柄，这里的句柄无状态）；`close(code,
    reason)` → `Connection.close(VarInt(code), reason)`（类型 0x1d 的 CONNECTION_CLOSE）。
  - `write` → `writeChunk`（不复制，流控 / 拥塞阻塞时挂起，即背压）；空数据不触碰流（同 h3-quinn 的 `poll_ready` 循环）。`finish` →
    `finish`，已结束或已复位 → `Unknown`（h3-quinn 把 `finish` 的错误一律映射为 Unknown）。⚖️ 连接已关闭时 `finish` 报 `ConnectionLost`
    （neton.quic 的 `finish` 不查连接状态，这样 HTTP/3 拿到的是连接错误而非 Unknown）。`reset` / `stopSending` 忽略 ClosedStream（同
    h3-quinn 的 `let _ =` / `.ok()`）。`stopped` → `stopped()`（`StoppedError.ConnectionLost` → ConnectionLost）。`read` →
    `readChunk(Int.MAX_VALUE, ordered = true)` 的字节（同 h3-quinn 的 `read_chunk(usize::MAX, true)`）。
  - 错误映射同 h3-quinn：`ConnectionError.ApplicationClosed` → `ApplicationClose(code)`，`TimedOut` → `Timeout`，其余（Transport、
    ConnectionClosed、Reset、LocallyClosed、VersionMismatch、CidsExhausted）→ `Undefined`；`WriteError.Stopped` / `ReadError.Reset` →
    `StreamTerminated(code)`；`*.ConnectionLost` → `ConnectionLost`（其连接错误同上映射）；ClosedStream、ZeroRttRejected、IllegalOrderedRead →
    `Unknown`（h3-quinn 对 IllegalOrderedRead panic；本适配只做有序读，不会出现）。
  - ALPN：HTTP/3 连接是 TLS 协商出 "h3" 的 QUIC 连接（RFC 9114 §3.1）。ALPN 属于 TLS 配置而非 QUIC 配置：应用把 `ALPN_H3` 设到交给
    `ServerConfig` / `ClientConfig` 的加密配置上（同 h3 示例在给 quinn 的 rustls 配置上设 `alpn_protocols = [b"h3"]`），双方无共同协议时握手以
    no_application_protocol 失败。真实 TLS 到位后即如此设置；目前只有测试替身支持：`MockServerCrypto(alpn = listOf(ALPN_H3))` /
    `MockClientCrypto(alpn = ...)`，测试在握手后核对双方协商出的协议为 "h3"。适配本身不检查 ALPN（同 h3-quinn）。
- 测试的参数化（同 `:http` 用 `testStreamPair` 在内存流与 TCP 上跑 h1 / h2 的做法）：阶段 B 的 `ConnectionTest`（19）、`RequestTest`（38）、
  `ConnectionLayerTest`（39）改为抽象类，经 `QuicPairFactory` 取得一对连接，各有两个子类：`…Memory`（内存替身，第 2 层）与 `…Quic`（两个
  neton.quic 端点、回环 UDP、测试替身握手、ALPN h3，第 3 层）。阶段 B 的全部 96 个连接层场景因此也在真实 QUIC 上运行，包括关键流（控制 /
  编码器 / 解码器流结束或复位、本端控制流被 STOP_SENDING——以原始对端在 neton.quic 上触发）、QPACK 流校验、431、取消隔离、阻塞的请求流
  不阻塞其他流、GOAWAY 的 `>=` 边界、空闲超时（QUIC 的 `maxIdleTimeout` 300 ms）。替身的 `streamCapacity` 在 neton.quic 上是流接收窗口，
  "由谁以何码关闭" 在 neton.quic 上取 `closeReason()`（对端的关闭异步到达，测试等到其出现）。
  - 在真实 QUIC 上暴露、原本依赖替身同步投递的测试假设（均为测试问题，已改测试、未改库）：
    - `request_sequence_check`（10 个非法帧序列）：客户端读到连接错误后立即关闭唯一的 `SendRequest`，此时客户端驱动尚未被恢复、还没看到对端的
      CONNECTION_CLOSE，于是先记下本端的 H3_NO_ERROR（连接错误先到者为准），驱动返回 Local 而非对端的码。改为：读失败时不先关闭，等驱动结束
      后再关闭。
    - 解码器流 / 控制流复位即关键流关闭：写入流类型后立刻 RESET_STREAM，在 QUIC 上复位会放弃尚未发出的数据，对端收到的是一条类型未到即被复位的
      流，按 RFC 9114 §6.2 忽略，连接直到空闲超时才结束。改为：等服务端收到客户端的 SETTINGS（`peerSettings`；解码器流的类型在其之前写入）后
      再复位。
- 端到端测试（`EndToEndTest`，11 个，本库 HTTP/3 客户端对本库 HTTP/3 服务端，全部在 neton.quic 上）：简单 GET（版本为 HTTP/3，客户端关闭
  唯一的发送者 → 服务端看到 H3_NO_ERROR）；POST 双向流式消息体（64 × 16 KiB，每块回显后才发下一块，响应头部先于请求体结束到达）；双向
  trailer；一条连接上 300 个并发请求（QUIC 默认并发双向流上限 100 的三倍，第 101 个等对端的 MAX_STREAMS；服务端同时进行中的请求峰值恰为
  100）；各 8 MiB 的上传与下载，流接收窗口 64 KiB：读方不读时写方被流控挡住（上传与下载都停在 32 KiB，即不超过一个窗口），读方开始读后逐字节
  核对；三个流式请求中客户端取消一个（`stopStream` + `stopSending`，服务端读报 RemoteTerminate(H3_REQUEST_CANCELLED)、写被叫停），另两个与连接
  不受影响、其后新请求照常；GOAWAY 优雅关闭（请求 0、4、8 进行中时 `shutdown(0)`，GOAWAY 为 12，恰在边界上的流 12 被 H3_REQUEST_REJECTED
  拒绝，其后新请求报 RemoteClosing，进行中的三个正常完成，`accept()` 返回 null，服务端关闭 → 客户端 Remote(H3_NO_ERROR)）；HTTP/3 层关闭的
  传播（等待响应时服务端 `close()`：客户端流报 Connection(Remote(H3_NO_ERROR))，驱动同，新请求失败）；QUIC 层关闭的传播（关闭整个服务端
  端点，码 H3_INTERNAL_ERROR：客户端 Remote(ApplicationClose)，服务端自身为 Undefined(LocallyClosed)）；150 个字段的请求 → 431，同一连接上
  下一个请求照常；ALPN 不一致（客户端只提供 h2）→ 双方握手失败。
- 结果：
  - macOS（`./gradlew :http3:macosArm64Test`）369 个全部通过（阶段 B 的 262 + 在 neton.quic 上再跑的 96 + 端到端 11），强制重跑共 4 次均通过；
    `:http` 1,197 个（14 个忽略）、0 失败（本阶段未改 `:http`）。
  - Linux x64（153，Rocky 9，`./gradlew :http3:linuxX64Test --rerun-tasks`）：`NETON_IO_DRIVER=epoll` 与 `iouring` 各 3 次，每次 369 个全部通过。
  - 在 153 上最慢的是 `ConnectionLayerTestQuic.oversizedResponseHeadersFrameFailsOnClient`（epoll 17 s，io_uring 9–11 s；macOS 3–7 s），原因
    见下方 quic 的第 1 条；其余第 3 层测试都在 2 s 内（大消息体测试的 2 s 主要是判定"已停住"的等待）。
- http3 本身：在真实 QUIC 上没有发现需要修改协议核心或连接层的问题；上面两处都是测试对替身同步投递的假设。记录一个行为（未改，同参考的
  先到者为准）：对端的 CONNECTION_CLOSE 已到 QUIC 层、而 HTTP/3 驱动尚未观察到时，本端关闭（如最后一个 `SendRequest.close()`）记下的是本端
  的 H3_NO_ERROR。
- 记给 quic（未改 quic 仓库）：
  1. **写流不让出反应器**：`SendStream.write*` 在有流 / 连接信用时从不挂起，连接驱动（发包）与端点的收包循环在同一个单线程反应器上，因此一个
     连续写小块的协程在信用耗尽之前，既不会让任何数据发出，也不会处理任何收到的包（STOP_SENDING、ACK 等）。复现：上述测试中服务端以 4 字节的
     DATA 帧循环 `sendData`，客户端在收到响应头部后立即 STOP_SENDING；服务端在写满整个流窗口后才发现被叫停——208,316 次写、每次 6 字节，
     共 1,249,896 字节，恰好是默认流接收窗口 1,250,000 以内（macOS 上约 2.9 s）。期望：写方在让出前的工作量有界（如 tokio 的 coop 预算），
     响应头部应在第一次让出前发出、STOP_SENDING 在几毫秒内生效。quinn 在 tokio 多线程运行时上驱动在其他工作线程，不显现；current_thread 上
     情形相同。影响有界（至多一个流窗口），HTTP/3 未绕开，待 quic 决定。
  2. 在 153 上按所给命令发布时 `:quic:publishKotlinMultiplatformPublicationToMavenLocal` 失败（其元数据编译需要 `io-iosarm64`，Linux 上没有）；
     本次在 153 上发布 `quic` / `quic-testkit` 的 linuxX64 产物，根模块（`quic`、`quic-testkit` 的 .module 与元数据 jar）从 macOS 的 mavenLocal
     复制（同一 quic 提交 164e922）。
  3. 已在 HTTP/3 下验证的 neton.quic 行为：MAX_STREAMS 限流与续发、流接收窗口的背压、RESET_STREAM / STOP_SENDING 的码、应用关闭码与空闲超时
     的传播、ALPN 协商（替身）。
- 示例 / 压测：**未给 `http-bench` 加 HTTP/3 hello 服务端与客户端。** 它们只能用 quic-testkit 的 TLS 替身握手，除了彼此之外连不上任何 HTTP/3
  实现（curl `--http3`、h2load 等都连不上），与 `http-bench` 中其他可对外对照的可执行文件性质不同，且会让一个可执行文件依赖测试专用产物；
  真实 TLS 到位后再加。性能未测。
- 未决 / 后续：阶段 D（真实 TLS、与外部 HTTP/3 实现双向互通、h3spec，§6 中 h3spec 五项不继承跳过）；上面 quic 的第 1 条；替身与 neton.quic
  都未覆盖丢包与乱序下的 HTTP/3 行为（回环不丢包）；其余同阶段 B 的未决项。
- 阶段 C 报告的 quic 写入不让出问题已在 quic 修复（quic SPEC §11.8 补记，提交 d9b8186：连续 32 次未挂起的写入后让出一次）。
  `oversizedResponseHeadersFrameFailsOnClient` 在真实 QUIC 上由 macOS 3–7 s（153 上 9–17 s）降到约 1 s；http3 369 个测试全过。
- HTTP/3 评审修复（2026-09-29，详见仓库根目录 `REVIEW-2026-09-29.md`）：
  - 控制流首帧在通用解码器跳过未知帧之前按线上类型检查，非 SETTINGS 立即 H3_MISSING_SETTINGS（RFC 9114 §6.2.1）；此前未知帧被跳过，
    首帧不是 SETTINGS 也能通过。
  - QPACK：Required Insert Count 为 0 时符号位为 1 即 Base 为负，判 QPACK_DECOMPRESSION_FAILED（RFC 9204 §4.5.1.2）；原先明确接受负 Base
    的测试改为测正 Base。
  - `recvTrailers()` 丢弃正文时同样按 content-length 检查超出，立即报流错误，不再等对端结束。
  - 客户端 `sendRequest` 在返回句柄前负责已打开的流：失败或取消时复位发送半边、停止接收半边；`openBi` 挂起后再次检查 GOAWAY 与发送器关闭。
  - 新增测试先在旧实现上确认失败。macOS：http3 378、http 1197（14 忽略）；153 两种驱动：http3 378、http 1198（14 忽略）全过。

**HTTP/3 阶段 D：真实 TLS、与外部 HTTP/3 实现双向互通、h3spec（2026-09-29，h3 0.0.8，§5 分层验收第 4 层）**
- 依赖：`com.netonstream:quic` / `quic-testkit` 0.1.0-SNAPSHOT，带真实 TLS 1.3 会话（`TlsClientConfig` / `TlsServerConfig`，OpenSSL 4.0.2，
  quic SPEC §4、§11.9）。153 上的 quic 取自 quic 提交 138fcc9：发布其 linuxX64 产物（`:quic:publishLinuxX64PublicationToMavenLocal
  :quic-testkit:publishLinuxX64PublicationToMavenLocal`），根模块从 macOS 的 mavenLocal 复制（同阶段 C 的第 2 条）。
- **测试框架接入真实 TLS**：`NetonQuicSupport` 的 `quicLoopback(tls)` 可选 `TestTls.REAL`（真实会话：进程内由 quic-testkit 的 `TestPki`
  生成测试 CA 及其为 localhost / 127.0.0.1 / ::1 签发的服务端证书；服务端配证书链与私钥、ALPN h3；客户端**只**信任该 CA、ALPN h3）或
  `TestTls.MOCK`（原 TLS 替身）。两种都保留：`EndToEndTestTls` / `EndToEndTestMock`，`ConnectionTestQuicTls`、`RequestTestQuicTls`、
  `ConnectionLayerTestQuicTls` 与原 `…Quic`（替身）、`…Memory`（内存替身）并存。握手后按 `TlsHandshakeData.protocol` 核对 ALPN 为 h3。
  新增：只信任另一 CA 的客户端 → 握手失败，CRYPTO_ERROR 0x130（unknown_ca）。阶段 C 的全部场景在真实 TLS 上直接通过，无需改库。
- **应用文档**：`ALPN_H3` 的 KDoc 与 README 给出 HTTP/3 的 TLS 配置（服务端证书链 + 私钥 + ALPN h3；客户端显式信任锚 + ALPN h3；
  无系统信任库；服务器名按证书的 DNS 名 / IP 核对）。
- **互通用可执行文件**（不进入生产产物）：新模块 `http3-interop`（`h3interop`，linuxX64 / macosArm64）：`server <ip:port> <cert> <key>`
  （PEM 或 DER，文件读入），`client <ip:port> <server name> <ca> <peer|basic|example>`。路由：`GET /`、`GET /size/<n>`（按
  `(i*31+7)%251` 的模式字节）、`POST /echo`（边收边回显；请求带 trailer 时响应 trailer 为 `x-echo-<name>` 与 `x-body-length`）、
  `GET /goaway`（`shutdown(0)` 后回 200）、其余 404。客户端场景：get、not-found、post（1,152 字节回显）、large-upload-echo（16 MiB
  上传同时读回显、逐字节核对）、large-download（16 MiB）、同一连接 100 并发 + 50 顺序请求、trailers（双向）、server-goaway（进行中的
  请求完成、之后的新请求被拒 RemoteClosing、连接以 H3_NO_ERROR 结束）、第二条连接上的 client-goaway-and-close。`NETON_H3_GREASE=0`
  关闭 GREASE（`sendGrease(false)`）。
  - 参考一侧：h3 0.0.8 源码树本身的 `examples/server.rs` 与 `examples/client.rs`（h3-quinn 0.0.9、quinn 0.11.12 / quinn-proto 0.11.18、
    rustls 0.23.45 ring、tokio 1.53.1），只做 GET（服务端按目录提供文件，客户端每次一个请求）；其余场景用 `http3-interop/h3-peer`
    （同样的 h3 / h3-quinn 源码，路由与场景与本库一致，`Cargo.lock` 已提交）。
  - 独立实现：aioquic 1.2.0（Python，自带 QUIC、TLS 与 HTTP/3，QPACK 用 pylsqpack 0.3.22），`pip` 装入 `/root/bench/h3-interop/venv`；
    用其源码包中的 `examples/http3_server.py`（配本库的 ASGI 应用 `http3-interop/aioquic/neton_routes.py`，无法表达 trailer 与 GOAWAY，
    本库客户端用 `basic` 场景）与 `examples/http3_client.py`。
  - quiche（cloudflare）未做：其 BoringSSL 构建需要 cmake 与 C++ 编译器，153 上两者都没有，安装即系统改动；curl `--http3` 需要自建
    ngtcp2 / nghttp3 与支持 QUIC 的 TLS 库，同样超出"不改系统"的范围。第二个独立实现以 aioquic 代替。
- 命令（153，Rocky 9.8，`kernel.io_uring_disabled = 0`；目录 `/root/bench/h3-interop`，h3 源码树在 `h3-0.0.8/`、`h3-peer/` 与之并列）：
  `./gen-certs.sh`（openssl 命令行生成一次性 CA 与 localhost / 127.0.0.1 服务端证书，另生成 DER 供 h3 示例、另一无关 CA 供拒绝用例）；
  `CARGO_TARGET_DIR=../target cargo build -j 2 --release --example server --example client`（在 `h3-0.0.8/`）与 `cargo build -j 2 --release`
  （在 `h3-peer/`）；`./gradlew --no-daemon --max-workers=1 -Dorg.gradle.jvmargs=-Xmx1g :http3-interop:linkH3interopDebugExecutableLinuxX64`；
  `./run-interop.sh <h3interop.kexe> 24900`；`./run-interop-aioquic.sh <h3interop.kexe> 24920`；
  `./h3spec-linux-x86_64 127.0.0.1 24940 -n -t 3000`（本库服务端默认配置、GREASE 开、不跳过任何用例；`-n` 是 h3spec 不校验证书，
  它没有指定 CA 的选项）。153 上始终只运行一个重型任务（构建与 cargo 串行，`systemd-run` 与登录会话脱离），结束后无残留进程。
- 结果（153，最后一次完整运行；此前各轮结果相同，除下文修复之前的失败）：

  | 方向 | 对端 | 结果 |
  |---|---|---|
  | 本库客户端 → h3 `examples/server.rs` | h3 0.0.8 + h3-quinn | 通过：GET 小文件、404、16 MiB 文件逐字节核对、同一连接 100 并发 + 50 顺序、POST（示例服务端不读请求体，照常 200）、关闭（H3_NO_ERROR） |
  | h3 `examples/client.rs` → 本库服务端 | h3 0.0.8 + h3-quinn | 通过：`GET /` 200、`GET /size/16777216` 与模式文件一致（cmp）、`GET /missing` 404，每次以 H3_NO_ERROR 关闭；只信任另一 CA 时客户端报 UnknownIssuer，本库服务端记录握手失败后继续服务 |
  | 本库客户端 → h3-peer 服务端 | h3 0.0.8 + h3-quinn | 9 / 9 通过：get、not-found、post、16 MiB 上传回显、16 MiB 下载、100 + 50 请求、双向 trailer、服务端 GOAWAY（见下）、客户端 GOAWAY 与关闭 |
  | h3-peer 客户端 → 本库服务端 | h3 0.0.8 + h3-quinn | 9 / 9 通过（同上；服务端 GOAWAY 后本库服务端在最后一个请求完成时以 H3_NO_ERROR 关闭连接） |
  | 本库客户端（只信任另一 CA）→ h3-peer 服务端 | h3-quinn / rustls | 按预期失败：本库 Transport(CRYPTO_ERROR 0x130，certificate verify failed)，h3-peer 看到对端以告警 48 中止 |
  | 本库客户端 → aioquic `http3_server.py` | aioquic 1.2.0 | 7 / 7 通过（basic：get、not-found、post、16 MiB 上传回显、16 MiB 下载、100 + 50 请求、关闭） |
  | aioquic `http3_client.py` → 本库服务端，GREASE 开（默认，同 h3） | aioquic 1.2.0 | **不通过**：每条连接上第一个完成的响应永远等不到结束（`timeout 60` 退出 124）；同连接的其他请求正常（16 MiB 一致、404） |
  | aioquic `http3_client.py` → 本库服务端，`NETON_H3_GREASE=0` | aioquic 1.2.0 | 通过：同一连接 GET / + 16 MiB + 404、POST 回显、100 KiB POST 回显 |

  - 服务端 GOAWAY 的结束方式两边不同，均合 RFC 9114 §5.2：h3 0.0.8 的 `accept()` 只在**收到**对端 GOAWAY（`recv_closing`）且无进行中请求
    时返回 None，自己发出 GOAWAY 后不主动关闭，等客户端关闭；本库服务端在自己的 GOAWAY 之后、最后一个请求完成时关闭（H3_NO_ERROR）。
    两个客户端的场景因此都接受"服务端关闭"或"3 s 内未关闭则客户端以 H3_NO_ERROR 关闭"，并记录是哪一种。
  - **aioquic 与 GREASE 的问题在 aioquic**：`H3Connection._handle_request_or_push_frame` 对未知（保留）类型的帧不产生事件，而流结束
    （`stream_ended`）只随 DATA / HEADERS 事件报告；当 GREASE 帧是 FIN 之前的最后一帧并与 FIN 同到时，结束被丢掉，客户端一直等待。
    h3 0.0.8 在每条连接第一次 `finish` 前发一个 GREASE 帧，本库照做（§5）。交叉验证：aioquic 客户端对 h3-peer（h3 本身）服务端的
    `GET /` 同样挂住（15 s 超时），第二个请求正常。RFC 9114 §9 要求忽略未知帧类型，故不改本库；关闭 GREASE 即可互通。是否为这类对端
    改变 GREASE 帧的位置（例如放在 HEADERS 之后而非紧贴 FIN），留作待决。
- **h3spec v0.1.13**（Linux 二进制，SHA-256 3664209a…e255）对本库服务端：**49 / 49 通过，连续 3 次**；不跳过任何用例。§6 表中参考跳过的
  五项全部通过：请求流上的 CANCEL_PUSH → H3_FRAME_UNEXPECTED（修复后，见下）、重复伪头部 → H3_MESSAGE_ERROR、动态表容量超限 →
  QPACK_ENCODER_STREAM_ERROR、Insert Count Increment 为 0 → QPACK_DECODER_STREAM_ERROR、缺 quic_transport_parameters 扩展 →
  missing_extension 告警（quic 的真实 TLS，两项）。其余：QUIC 服务端 34 项（流量控制、流数上限、传输参数、帧编码、保留位、各帧的流状态、
  KeyUpdate、no_application_protocol、EndOfEarlyData 等）与 HTTP/3 服务端 15 项全部通过。说明：`CRYPTO in 0-RTT` 一项 h3spec 标为通过，
  但同时提示 "0-RTT is not possible. Skipping this test"——quic 不做 0-RTT（quic SPEC §11.9），该项实际未被检验。
- **修复**（均先在旧实现上确认新测试失败）：
  1. h3spec 第一次运行 48 / 49：请求流上的 CANCEL_PUSH，本库回 H3_FRAME_ERROR（0x106，"frame 0x3 is malformed"），应为 H3_FRAME_UNEXPECTED。
     原因：帧解码器先按布局校验 CANCEL_PUSH 的负载（必须恰为一个 varint），请求流对帧类型的检查在其后；h3spec 发来的 CANCEL_PUSH 负载
     不合布局，于是先报 FRAME_ERROR。⚖️ 改为：请求流的解码器（`FrameDecoder(requestStream = true)`）读到帧头即拒绝控制流专用类型
     SETTINGS、CANCEL_PUSH、GOAWAY、MAX_PUSH_ID（`FrameError.Unexpected`，H3_FRAME_UNEXPECTED，RFC 9114 §7.2.3–7.2.7），不看负载。
     参考先解码再查类型（且对空负载一直等待），它在 CI 中跳过此项。新增测试：`FrameStreamTest.requestStreamRejectsControlFramesFromTheHeader`、
     `RequestTest.request_malformed_control_frame_is_unexpected` 与 `request_malformed_cancel_push_first_is_unexpected`（内存、替身 QUIC、
     真实 TLS 各一份）。移植的 `request_invalid_data_frame_length_too_large` 随之改变：DATA 声明 5 字节只带 4 字节，吞掉 trailer 帧的类型字节，
     trailer 帧的长度字节 0x0d 被当作下一帧类型（MAX_PUSH_ID）；参考因解不出其空负载得 H3_FRAME_ERROR，本库按上面的规则得
     H3_FRAME_UNEXPECTED。测试断言该字节确为 0x0d 并期望 H3_FRAME_UNEXPECTED。
  2. 互通发现的用法问题（不是协议错误，已写入文档与测试）：本库互通服务端最初处理 GET 后不 `close()` 服务端 `RequestStream`，请求的 FIN
     始终未读，QUIC 流不释放，客户端的双向流额度（默认 100）用完后同一连接上的请求全部停住（本机 95 个请求后空闲超时）。服务端
     `RequestStream.close()` 就是参考的 `Drop`（停止未读完的接收半边，释放流）；Rust 在丢弃时自动发生，Kotlin 须显式 `close()` / `use {}`。
     互通服务端改用 `use {}`；`RequestStream.close` 的 KDoc 与 README 的服务端示例写明；新增端到端测试
     `request_streams_hold_their_quic_streams_until_closed`（真实 TLS 与替身各一份）：100 个未关闭的请求之后第 101 个等待流额度，关闭后继续，
     其后逐个关闭的 150 个请求全部完成。
- 测试数：macOS（`./gradlew :http3:macosArm64Test`）498 个全部通过（阶段 C 评审修复后的 378 + 真实 TLS 上再跑的连接层 99 与端到端 11 +
  不受信任证书 1 + 请求流上的控制帧 2 × 3 种传输 + 帧解码 1 + 流释放 2 × 2 种 TLS；另有 `request_invalid_data_frame_length_too_large` 的期望改变）；153
  （`http3/build/bin/linuxX64/debugTest/test.kexe`）`NETON_IO_DRIVER=epoll` 与 `iouring` 各 498 个全部通过。`:http` 未改。
- 验收结论：按 §5 第 4 层与本节的判据，**HTTP/3 首版验收通过**：真实 TLS 上与参考 h3 0.0.8（含其自带示例）双向互通全部通过，h3spec
  必须通过的各项（含 §6 表中五项）全部通过。**仍未完成 / 限制**：aioquic 客户端在 GREASE 开启时挂住（aioquic 的缺陷，见上；关闭 GREASE
  可互通）；quiche 与 curl `--http3` 未测（153 无 C++ 编译器与 cmake）；h3spec 的 0-RTT 项未实际检验（无 0-RTT）；互通均在本机回环上，
  无丢包与乱序；未做性能对照与 quic-interop-runner；`http-bench` 仍无 HTTP/3 压测程序；互通可执行文件只构建了 linuxX64 与 macosArm64。
- GREASE 帧位置调整 ⚖️（2026-09-29，所有者决定）：每个连接一次的请求流 GREASE 帧由"FIN 之前"（参考 h3 的位置）改为"首个消息头之后"
  （服务端在响应头后、客户端在请求头后；trailer 与 431 不带）。原因：aioquic 1.2.0 客户端在保留类型帧紧挨 FIN 时丢失流结束信号而挂起（它对
  h3 自己的服务端同样挂起）；RFC 9114 §7.2.8 允许保留帧出现在任何位置，GREASE 仍照常演练。测试 `serverGreaseFrameFollowsTheResponseHeadNotTheEnd`、
  `clientGreaseFrameFollowsTheRequestHeadNotTheEnd`（内存替身、neton.quic 测试替身、真实 TLS 三种变体）断言线上帧序为 HEADERS、GREASE、…、DATA，
  去掉改动时失败。153 复验（真实 TLS）：aioquic 客户端开启 GREASE 三项全过（此前挂起），关闭 GREASE 三项、本库客户端对 aioquic 服务端 7 项全过；
  与 h3 examples 及 h3-peer 双向互通全过；h3spec 49 / 49。

**发布准备（2026-10-08）**
- 依赖改为 http 0.1.1、quic 0.1.0、quic-testkit 0.1.0（测试）；版本 0.1.0；POM、签名、javadoc jar 与本地暂存仓库，与 http 相同（`http3-interop`
  不发布）。（mingwX64 曾因 io 在 Windows 上没有 UDP 而去掉；io 补上 Windows UDP（io SPEC §29.7）后恢复，CI 在 Windows 的 IOCP 与 WSAPoll 上运行全量测试。）quic 0.1.0 发布到 Maven Central 之前仍以 `includeBuild("../quic")`
  取得，发布后去掉。macOS arm64 测试 504 个，全部通过。
- 服务端不关闭请求流会占住 QUIC 流额度（约 100 个请求后连接停顿，§4 阶段 D 互通时发现）：Kotlin 没有 Rust 的自动 drop，`RequestStream.close()`
  （或 `use {}`）是其对应物，属用法要求而非协议缺陷；KDoc、README 示例与测试已写明，本版不改。

**0-RTT（2026-10-08）**
- quic 在真实 TLS 上支持会话恢复与 0-RTT（quic SPEC §11.14）后，HTTP/3 无需改动即可在 0-RTT 连接上工作：客户端以 `Connecting.into0Rtt()`
  得到连接、在其上建立 HTTP/3 客户端并立即发请求。服务端设置：RFC 9114 §7.2.4.2 要求客户端遵守记住的设置或其默认值；本库客户端用默认值
  （与参考相同，参考的"0-RTT 保存设置"是 TODO），服务端设置不随会话变化，符合"兼容"的要求。
- **早期数据的标记** ⚖️：服务端 `RequestStream.isEarlyData()`——请求是否由 0-RTT 送达（可被重放，RFC 9001 §9.2；服务端应只对可重复执行的
  请求照常处理，否则可答 425 Too Early，RFC 8470、RFC 9114 §10.9）。参考没有。经薄接口 `RecvStream.isEarlyData`（默认 false）取自 quic 的
  `RecvStream.isEarlyData()`：quinn 的 `is_0rtt` 只表示"应用在握手期间接受了该流"，等握手完成再接受流的服务端（本库服务端的常规写法）永远
  看不到；quic 另记"对端在 0-RTT 包中打开的流"（quic SPEC §11.18）。
- **0-RTT 被拒绝**：客户端在 0-RTT 中打开的控制流与请求流随之作废，HTTP/3 客户端不可再用、请求失败；QUIC 连接以 1-RTT 继续。应用在同一
  QUIC 连接上新建 HTTP/3 客户端重发（与 quinn 的 0-RTT 流须重新打开一致）；注意不要关闭旧的 `SendRequest`——关闭最后一个会按 h3 的规则关闭
  QUIC 连接。
- 测试 `ZeroRttTest`（真实 TLS）：第二个连接在 0-RTT 中发出请求、服务端以 `isEarlyData()` 认出、0-RTT 被接受；服务端换用不接受早期数据的
  配置时 0-RTT 被拒绝、请求失败，新的 HTTP/3 客户端在同一连接上重发成功。服务端延后 200 ms 才接受连接，使回环上的请求一定在握手完成前发出。
- **依赖**：需要含 0-RTT 与 `isEarlyData` 的 quic（main，未发布；0.1.0 没有）。本地以 `--include-build ../quic` 验证：macOS 506 个全部通过。

