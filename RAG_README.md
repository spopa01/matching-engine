# RAG Pipeline for Matching Engine Analysis

A Retrieval Augmented Generation (RAG) system using LlamaIndex and Claude (Anthropic) to answer questions about:
1. **Order execution** - from instrumentation logs
2. **Code implementation** - from Java source files

## Setup

### 1. Install Python Dependencies

```bash
pip install -r requirements.txt
```

### 2. Set API Keys

You need API keys from both Anthropic and OpenAI:

```bash
export ANTHROPIC_API_KEY='your-anthropic-api-key'
export OPENAI_API_KEY='your-openai-api-key'
```

**Note:** OpenAI API key is used only for embeddings (text-embedding-3-small), while Anthropic Claude is used as the LLM.

### 3. Run the Pipeline

```bash
python rag_query.py
```

## Usage

The RAG pipeline provides an interactive CLI with three query modes:

### Commands

- **`/instr <query>`** - Query instrumentation log only
- **`/code <query>`** - Query source code only
- **`/both <query>`** - Query both and synthesize answer (recommended)
- **`/quit`** - Exit

### Example Queries

#### Instrumentation Log Queries

```
/instr What orders were executed and at what prices?
/instr How many orders were added to the order book?
/instr What was the order book state after the first order?
/instr Which orders had partial fills?
/instr What functions were called to process order VQ6EAOKbQdSnFkRmVUQAAA?
```

#### Source Code Queries

```
/code How does the matching algorithm work?
/code What is the order book data structure?
/code Explain the executeMatch function
/code What validation is performed on incoming orders?
/code How are execution reports generated?
```

#### Combined Queries (Recommended)

```
/both How did the matching engine process market orders?
/both Explain what happened when order VQ6EAOKbQdSnFkRmVUQABA was processed
/both What is price-time priority and how is it implemented?
/both How does the system handle partial fills?
```

## Architecture

### Components

1. **LlamaIndex** - RAG framework for indexing and retrieval
2. **Claude Opus 4** - Anthropic's LLM for query answering
3. **OpenAI Embeddings** - text-embedding-3-small for vector search
4. **Two Indices:**
   - **Instrumentation Index** - Execution traces from `instrumentation.log`
   - **Code Index** - Java source files from `src/` and `agent/`

### Query Flow

```
User Query
    ↓
[Query Type: /instr, /code, or /both]
    ↓
Vector Search (OpenAI embeddings)
    ↓
Retrieve Relevant Context
    ↓
Claude Opus (Anthropic LLM)
    ↓
Generate Answer
```

### Indexed Data

**Instrumentation Log:**
- Function metadata (UUID → function name/description mappings)
- ORDER_IN events (incoming order details)
- CALL events (function execution traces)
- EXEC_REPORT events (trade executions)
- BOOK_ADD events (orders added to book)
- SNAPSHOT events (order book states)

**Source Code:**
- All Java files in `src/main/java/com/matching`
- All Java files in `agent/src/main/java/com/matching`
- Model classes, engine logic, I/O handlers, agent code

## Features

- **Semantic Search** - Uses vector embeddings for intelligent retrieval
- **Context-Aware** - Understands domain-specific terminology
- **Multi-Source** - Combines runtime execution data with code implementation
- **Interactive** - Real-time queries via CLI
- **Powered by Claude** - Uses Anthropic's state-of-the-art LLM

## Tips

1. **Use `/both` for best results** - Combines execution data with code understanding
2. **Be specific** - Include order IDs, function names, or specific events
3. **Ask "why" questions** - The RAG can explain both what happened and why
4. **Reference UUIDs** - Function UUIDs from instrumentation link to code descriptions

## Example Session

```bash
$ python rag_query.py

======================================================================
Matching Engine RAG Pipeline
======================================================================

📚 Indexing data...
Indexing instrumentation log: instrumentation.log
✓ Indexed instrumentation log (15234 characters)
Indexing source code...
  ✓ Indexed 5 files from src/main/java/com/matching
  ✓ Indexed 2 files from agent/src/main/java/com/matching
✓ Indexed 7 source code files

✅ RAG pipeline ready!

Available commands:
  /instr <query>  - Query instrumentation log only
  /code <query>   - Query source code only
  /both <query>   - Query both (synthesized answer)
  /quit           - Exit

💬 Query: /both What happened when order VQ6EAOKbQdSnFkRmVUQAAw was processed?

🔄 Querying both sources...

[RAG provides detailed answer combining execution trace and code explanation]
```

## Troubleshooting

### Missing API Keys

```
❌ Error: ANTHROPIC_API_KEY must be set
```

**Solution:** Set environment variables as shown in Setup section

### No Files Found

```
❌ Error indexing: No source code files found to index
```

**Solution:** Ensure you're running from the project root directory

### Rate Limits

If you hit API rate limits, the queries will fail. Wait a moment and try again.

## Cost Considerations

- **Embeddings:** OpenAI text-embedding-3-small (~$0.00002 per 1K tokens)
- **LLM:** Anthropic Claude Opus 4 (~$15 per 1M input tokens, ~$75 per 1M output tokens)

Initial indexing costs are minimal. Query costs depend on retrieved context size and response length.
