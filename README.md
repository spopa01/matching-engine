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
├── rag_query.py                     # RAG pipeline for log analysis
├── RAG_README.md                    # RAG pipeline documentation
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

## RAG Pipeline

Query both execution logs and source code using AI:

```bash
# Install dependencies
pip3 install -r requirements.txt

# Set API keys
export ANTHROPIC_API_KEY='your-key'
export OPENAI_API_KEY='your-key'

# Run interactive query interface
python3 rag_query.py
```

See [RAG_README.md](RAG_README.md) for detailed usage.

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

## Instrumentation Events

The Java agent captures:
- **ORDER_IN**: Incoming orders
- **CALL**: Function calls with UUIDs
- **EXEC_REPORT**: Trade executions
- **BOOK_ADD**: Orders added to book
- **SNAPSHOT**: Order book state after each order

## Requirements

- Java 17+
- Maven 3.6+
- Python 3.12+ (for RAG pipeline)
- Anthropic and OpenAI API keys (for RAG queries)

## License

MIT
