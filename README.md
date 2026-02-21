# Matching Engine

A high-performance order matching engine implementation in Java with comprehensive instrumentation and RAG-based analysis capabilities.

## Features

- **Price-Time Priority Matching**: FIFO order matching algorithm
- **Order Types**: Support for both LIMIT and MARKET orders
- **Execution Reports**: Detailed execution reporting with partial fill support
- **Instrumentation**: Java agent for execution tracing using Byte Buddy
- **RAG Analysis**: Query execution logs and source code using LlamaIndex and Claude

## Project Structure

```
engine/
├── src/main/java/com/matching/     # Core matching engine
│   ├── model/                       # Domain models (Order, ExecutionReport, etc.)
│   ├── engine/                      # Matching logic (MatchingEngine, OrderBook)
│   ├── io/                          # CSV input/output handlers
│   └── Main.java                    # Application entry point
├── agent/                           # Java instrumentation agent
│   └── src/main/java/com/matching/
│       └── agent/                   # Byte Buddy instrumentation
├── benchmarks/                      # Benchmark scripts and order generation
├── rag/                             # RAG pipeline for log and code analysis
│   ├── rag_query.py                 # Interactive RAG CLI
│   └── requirements.txt             # Python dependencies
└── orders.csv                       # Sample order input file
```

## Building

### Matching Engine

```bash
mvn clean package
```

### Java Agent

```bash
cd agent
mvn clean package
```

## Running

### Without Instrumentation

```bash
java -jar target/matching-engine-1.0-SNAPSHOT-jar-with-dependencies.jar orders.csv executions.csv
```

### With Instrumentation

```bash
java -javaagent:agent/target/matching-agent-1.0-SNAPSHOT.jar \
     -jar target/matching-engine-1.0-SNAPSHOT-jar-with-dependencies.jar \
     orders.csv executions.csv
```

This generates `instrumentation.log` with detailed execution traces.

Agent system properties:
- `-Dmatching.agent.logfile=<path>` — log output path (default: `instrumentation.log`)
- `-Dmatching.agent.output=none` — disable file output
- `-Dmatching.agent.snapshot.levels=N` — price levels per side in snapshots (default: 5)
- `-Dmatching.agent.snapshot.interval=N` — emit snapshot every N orders (default: 1)
- `-Dmatching.agent.emit=false` — skip all event emission (profiling mode)

## CSV Format

### Input (orders.csv)
```csv
orderId,side,orderType,quantity,price
VQ6EAOKbQdSnFkRmVUQAAA,BUY,LIMIT,10,100.50
VQ6EAOKbQdSnFkRmVUQABA,BUY,MARKET,12,
```

### Output (executions.csv)
```csv
orderId,side,executionType,orderSize,lastQuantity,cumulativeQuantity,price
VQ6EAOKbQdSnFkRmVUQAAw,SELL,FULL_FILL,8,8,8,100.60
```

## Benchmarking

```bash
bash benchmarks/bench.sh -n 10000                   # auto-generates orders, 10 interleaved runs
bash benchmarks/bench.sh -n 1000000 -r 5             # 1M orders, 5 runs
bash benchmarks/bench.sh -n 500000 -s 10 -S 100      # custom snapshot settings
bash benchmarks/bench.sh -n 1000000 -E false          # disable event emission (profiling)
python3 benchmarks/generate_orders.py 500000          # generate orders only
```

All benchmark artifacts (orders, executions, instrumentation logs) are generated in `benchmarks/` and postfixed with the order count.

## Instrumentation Events

The Java agent captures:
- **ORDER_IN**: Incoming orders
- **CALL**: Function calls with UUIDs
- **EXEC_REPORT**: Trade executions
- **BOOK_ADD**: Orders added to book
- **SNAPSHOT**: Order book state after each order

## RAG Pipeline

A Retrieval Augmented Generation (RAG) system using LlamaIndex and Claude to answer questions about order execution (from instrumentation logs) and code implementation (from Java source files).

### Setup

```bash
pip install -r rag/requirements.txt

export ANTHROPIC_API_KEY='your-anthropic-api-key'
export OPENAI_API_KEY='your-openai-api-key'

python3 rag/rag_query.py
```

**Note:** OpenAI API key is used only for embeddings (text-embedding-3-small), while Anthropic Claude is used as the LLM.

### Commands

- **`/instr <query>`** - Query instrumentation log only
- **`/code <query>`** - Query source code only
- **`/both <query>`** - Query both and synthesize answer (recommended)
- **`/quit`** - Exit

### Example Queries

```
/instr What orders were executed and at what prices?
/instr Which orders had partial fills?
/code How does the matching algorithm work?
/code What is the order book data structure?
/both How did the matching engine process market orders?
/both Explain what happened when order VQ6EAOKbQdSnFkRmVUQABA was processed
```

### Architecture

```
User Query
    ↓
[Query Type: /instr, /code, or /both]
    ↓
Vector Search (OpenAI embeddings)
    ↓
Retrieve Relevant Context
    ↓
Claude (Anthropic LLM)
    ↓
Generate Answer
```

Two indices are built:
- **Instrumentation Index** — execution traces from `instrumentation.log` (ORDER_IN, CALL, EXEC_REPORT, BOOK_ADD, SNAPSHOT events and function metadata)
- **Code Index** — all Java source files from `src/` and `agent/`

### Tips

1. **Use `/both` for best results** — combines execution data with code understanding
2. **Be specific** — include order IDs, function names, or specific events
3. **Ask "why" questions** — the RAG can explain both what happened and why
4. **Reference UUIDs** — function UUIDs from instrumentation link to code descriptions

### Troubleshooting

- **Missing API Keys**: Set `ANTHROPIC_API_KEY` and `OPENAI_API_KEY` environment variables
- **No Files Found**: Can be run from any directory; paths resolve relative to the project root automatically
- **Rate Limits**: Wait a moment and retry

### Cost Considerations

- **Embeddings:** OpenAI text-embedding-3-small (~$0.00002 per 1K tokens)
- **LLM:** Anthropic Claude (~$15 per 1M input tokens, ~$75 per 1M output tokens)

## Requirements

- Java 17+
- Maven 3.6+
- Python 3.12+ (for RAG pipeline)
- Anthropic and OpenAI API keys (for RAG queries)

## License

MIT
