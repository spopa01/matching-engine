#!/usr/bin/env python3
"""
RAG Pipeline for Matching Engine Analysis
Indexes instrumentation logs and source code to answer questions about order execution and code.
"""

import os
from pathlib import Path
from typing import Optional

from llama_index.core import (
    VectorStoreIndex,
    SimpleDirectoryReader,
    Settings,
    Document,
)
from llama_index.llms.anthropic import Anthropic
from llama_index.embeddings.openai import OpenAIEmbedding
from llama_index.core.node_parser import SimpleNodeParser


class MatchingEngineRAG:
    """RAG pipeline for matching engine instrumentation and code analysis."""

    def __init__(self, anthropic_api_key: Optional[str] = None, openai_api_key: Optional[str] = None):
        """Initialize RAG pipeline with Claude LLM and OpenAI embeddings."""
        # Set API keys
        self.anthropic_api_key = anthropic_api_key or os.getenv("ANTHROPIC_API_KEY")
        self.openai_api_key = openai_api_key or os.getenv("OPENAI_API_KEY")

        if not self.anthropic_api_key:
            raise ValueError("ANTHROPIC_API_KEY must be set")
        if not self.openai_api_key:
            raise ValueError("OPENAI_API_KEY must be set for embeddings")

        # Configure LlamaIndex with Claude
        Settings.llm = Anthropic(
            api_key=self.anthropic_api_key,
            model="claude-opus-4-20250514",  # Latest Claude Opus
            temperature=0.1,
            max_tokens=4096,
        )

        # Configure embeddings
        Settings.embed_model = OpenAIEmbedding(
            api_key=self.openai_api_key,
            model="text-embedding-3-small"
        )

        # Configure node parser
        Settings.node_parser = SimpleNodeParser.from_defaults(
            chunk_size=1024,
            chunk_overlap=20
        )

        self.instrumentation_index = None
        self.code_index = None

    def index_instrumentation_log(self, log_path: str = "instrumentation.log"):
        """Index the instrumentation log file."""
        print(f"Indexing instrumentation log: {log_path}")

        if not os.path.exists(log_path):
            raise FileNotFoundError(f"Instrumentation log not found: {log_path}")

        # Read the log file
        with open(log_path, 'r') as f:
            log_content = f.read()

        # Create a document with metadata
        doc = Document(
            text=log_content,
            metadata={
                "source": "instrumentation_log",
                "file_path": log_path,
                "description": "Execution trace of matching engine with function calls, order events, and snapshots"
            }
        )

        # Create index
        self.instrumentation_index = VectorStoreIndex.from_documents([doc])
        print(f"✓ Indexed instrumentation log ({len(log_content)} characters)")

    def index_source_code(self, source_dirs: list[str] = None):
        """Index source code files."""
        if source_dirs is None:
            source_dirs = [
                "src/main/java/com/matching",
                "agent/src/main/java/com/matching"
            ]

        print("Indexing source code...")
        documents = []

        for source_dir in source_dirs:
            if not os.path.exists(source_dir):
                print(f"  Warning: Directory not found: {source_dir}")
                continue

            # Read all Java files
            reader = SimpleDirectoryReader(
                input_dir=source_dir,
                required_exts=[".java"],
                recursive=True
            )

            docs = reader.load_data()

            # Add metadata to each document
            for doc in docs:
                doc.metadata["source"] = "source_code"
                doc.metadata["language"] = "java"

            documents.extend(docs)
            print(f"  ✓ Indexed {len(docs)} files from {source_dir}")

        if not documents:
            raise ValueError("No source code files found to index")

        # Create index
        self.code_index = VectorStoreIndex.from_documents(documents)
        print(f"✓ Indexed {len(documents)} source code files")

    def query_instrumentation(self, query: str) -> str:
        """Query the instrumentation log index."""
        if self.instrumentation_index is None:
            raise ValueError("Instrumentation log not indexed. Call index_instrumentation_log() first.")

        query_engine = self.instrumentation_index.as_query_engine(
            similarity_top_k=5,
            response_mode="tree_summarize"
        )

        # Enhance query with context
        enhanced_query = f"""Based on the instrumentation log data, answer the following question.
The log contains execution traces with:
- Function metadata (UUID mappings to function names and descriptions)
- ORDER_IN events (incoming orders with orderId, side, orderType, qty, price)
- CALL events (function calls with UUIDs)
- EXEC_REPORT events (execution reports with qty, lastQty, cumQty, price)
- BOOK_ADD events (orders added to book with remainingQty, cumQty)
- SNAPSHOT events (order book state after each order)

Question: {query}"""

        response = query_engine.query(enhanced_query)
        return str(response)

    def query_code(self, query: str) -> str:
        """Query the source code index."""
        if self.code_index is None:
            raise ValueError("Source code not indexed. Call index_source_code() first.")

        query_engine = self.code_index.as_query_engine(
            similarity_top_k=5,
            response_mode="tree_summarize"
        )

        # Enhance query with context
        enhanced_query = f"""Based on the Java source code for the matching engine, answer the following question.
The codebase contains:
- Matching engine core logic (order matching, execution)
- Order book implementation (price-time priority)
- Model classes (Order, ExecutionReport, Side, OrderType, ExecutionType)
- CSV I/O handlers
- Java agent for instrumentation

Question: {query}"""

        response = query_engine.query(enhanced_query)
        return str(response)

    def query_both(self, query: str) -> str:
        """Query both instrumentation and code indices, combining results."""
        if self.instrumentation_index is None or self.code_index is None:
            raise ValueError("Both indices must be created first")

        # Query both indices
        print("\n🔍 Querying instrumentation log...")
        instr_response = self.query_instrumentation(query)

        print("\n🔍 Querying source code...")
        code_response = self.query_code(query)

        # Combine responses
        combined_query = f"""I have information from two sources about a matching engine:

1. From the execution instrumentation log:
{instr_response}

2. From the source code:
{code_response}

Based on both sources, provide a comprehensive answer to: {query}

Synthesize the information from both the runtime execution data and the code implementation."""

        # Use Claude to synthesize
        from llama_index.core.llms import ChatMessage

        messages = [
            ChatMessage(role="user", content=combined_query)
        ]

        response = Settings.llm.chat(messages)
        return str(response.message.content)


def main():
    """Interactive CLI for querying the RAG pipeline."""
    import sys

    print("=" * 70)
    print("Matching Engine RAG Pipeline")
    print("=" * 70)

    # Initialize RAG
    try:
        rag = MatchingEngineRAG()
    except ValueError as e:
        print(f"\n❌ Error: {e}")
        print("\nPlease set the following environment variables:")
        print("  export ANTHROPIC_API_KEY='your-key'")
        print("  export OPENAI_API_KEY='your-key'")
        sys.exit(1)

    # Index data
    print("\n📚 Indexing data...")
    try:
        rag.index_instrumentation_log()
        rag.index_source_code()
    except Exception as e:
        print(f"\n❌ Error indexing: {e}")
        sys.exit(1)

    print("\n✅ RAG pipeline ready!")
    print("\nAvailable commands:")
    print("  /instr <query>  - Query instrumentation log only")
    print("  /code <query>   - Query source code only")
    print("  /both <query>   - Query both (synthesized answer)")
    print("  /quit           - Exit")
    print()

    # Interactive loop
    while True:
        try:
            user_input = input("\n💬 Query: ").strip()

            if not user_input:
                continue

            if user_input == "/quit":
                print("\n👋 Goodbye!")
                break

            # Parse command
            if user_input.startswith("/instr "):
                query = user_input[7:].strip()
                print("\n📊 Querying instrumentation log...")
                response = rag.query_instrumentation(query)
                print(f"\n{response}")

            elif user_input.startswith("/code "):
                query = user_input[6:].strip()
                print("\n💻 Querying source code...")
                response = rag.query_code(query)
                print(f"\n{response}")

            elif user_input.startswith("/both "):
                query = user_input[6:].strip()
                print("\n🔄 Querying both sources...")
                response = rag.query_both(query)
                print(f"\n{response}")

            else:
                # Default to querying both
                print("\n🔄 Querying both sources...")
                response = rag.query_both(user_input)
                print(f"\n{response}")

        except KeyboardInterrupt:
            print("\n\n👋 Goodbye!")
            break
        except Exception as e:
            print(f"\n❌ Error: {e}")


if __name__ == "__main__":
    main()
