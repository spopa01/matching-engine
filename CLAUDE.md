# CLAUDE.md

This file provides guidance to Claude Code (claude.ai/code) when working with code in this repository.

## Build

```bash
mvn clean package              # Engine uber-JAR
cd agent && mvn clean package  # Agent shaded JAR (separate Maven project)
```

## Run

```bash
# Without instrumentation
java -jar target/matching-engine-1.0-SNAPSHOT-jar-with-dependencies.jar orders.csv executions.csv

# With instrumentation (generates instrumentation.log)
java -javaagent:agent/target/matching-agent-1.0-SNAPSHOT.jar \
     -jar target/matching-engine-1.0-SNAPSHOT-jar-with-dependencies.jar \
     orders.csv executions.csv
```

## Benchmark

All benchmark files live in `benchmarks/` — never use `/tmp` or project root for benchmark artifacts.

```bash
bash benchmarks/bench.sh -n 10000                          # auto-generates orders if needed
bash benchmarks/bench.sh -n 1000000 -r 5 -m sequential     # 1M orders, 5 runs, sequential mode
bash benchmarks/bench.sh -i benchmarks/orders_10000.csv    # use existing orders file
bash benchmarks/bench.sh -n 500000 -s 10 -S 100            # 10 snapshot levels, every 100 orders
bash benchmarks/bench.sh -n 1000000 -E false               # disable event emission (profiling)
python3 benchmarks/generate_orders.py 500000               # generate orders only
```

- `bench.sh` flags:
  - `-n` order count, `-i` input file, `-r` runs (default 10), `-m` interleaved|sequential
  - `-e`/`-a` JAR overrides
  - `-s` snapshot levels (default 5), `-S` snapshot interval (default 1)
  - `-o` agent output mode: file|none, `-E` emit events: true|false
- Output files are postfixed with order count (e.g. `exec_baseline_1000000.csv`, `instrumentation_1000000.log`)
- All outputs kept after each run; overwritten on next run with same order count

## RAG Pipeline

```bash
pip3 install -r rag/requirements.txt
export ANTHROPIC_API_KEY='...' OPENAI_API_KEY='...'
python3 rag/rag_query.py   # interactive CLI: /instr, /code, /agent, /all, /quit
```

## Architecture

Price-time priority (FIFO) order matching engine in Java 17 with a Byte Buddy instrumentation agent and a Python RAG pipeline.

**Two separate Maven projects** (`com.matching` group, built independently):
- Root `pom.xml` — matching engine (uber-JAR via maven-assembly-plugin)
- `agent/pom.xml` — instrumentation agent (shaded JAR via maven-shade-plugin)

### Engine (`src/main/java/com/matching/`)
- `Main.java` — entry point: CSV read, engine run, CSV write
- `engine/OrderBook.java` — dual TreeMap (descending buys, ascending sells) with LinkedList queues per price level
- `engine/MatchingEngine.java` — matching logic, trade execution, ExecutionReport generation
- `model/` — `Order`, `ExecutionReport`, enums (`Side`, `OrderType`, `ExecutionType`)
- `io/CSVHandler.java` — CSV parsing/writing; order IDs are Base64-encoded UUIDs
- `annotation/FunctionMetadata.java` — runtime annotation with UUID and description, used by agent for tracing

### Agent (`agent/src/main/java/com/matching/agent/`)
- `MatchingAgent.java` — premain entry; Byte Buddy instruments `@FunctionMetadata`-annotated methods with custom UUID mapping via `Advice.withCustomMapping().bind(Factory<>)`
- `MethodInterceptor.java` — advice class; emits ORDER_IN, CALL, EXEC_REPORT, BOOK_ADD, SNAPSHOT events to `instrumentation.log`; uses `@Advice.Local` for context passing, SPSC ring buffer for async drain-thread writes
- `SpscRingBuffer.java` — lock-free ring buffer with VarHandle release/acquire, cache-line padding, batched publish
- `TraceEvent.java` — event carrier for the ring buffer

### RAG (`rag/`)
- `rag_query.py` — interactive CLI; paths resolve relative to project root automatically
- Three LlamaIndex vector indices: instrumentation log, engine source, agent source
- Commands: `/instr`, `/code`, `/agent`, `/all` (synthesizes all three)
- OpenAI embeddings + Claude for answering

## Key Design Decisions

- Orders processed sequentially to preserve time-priority semantics
- Market orders have no price constraint; limit orders match at their price or better
- Execution reports emitted for both sides of each trade
- `@FunctionMetadata` serves dual purpose: instrumentation targeting and human-readable business logic documentation
- Agent UUIDs are inlined as compile-time constants via Byte Buddy custom offset mapping (no runtime HashMap lookup)
- Interned UUID strings enable reference equality (`==`) checks on the hot path
