#!/usr/bin/env bash
set -e

SCRIPT_DIR="$(cd "$(dirname "$0")" && pwd)"

# ── defaults ──────────────────────────────────────────────────
ENGINE_JAR="target/matching-engine-1.0-SNAPSHOT-jar-with-dependencies.jar"
AGENT_JAR="agent/target/matching-agent-1.0-SNAPSHOT.jar"
RUNS=10
MODE="interleaved"   # interleaved | sequential
NUM_ORDERS=""
INPUT=""
SNAPSHOT_LEVELS=""
SNAPSHOT_INTERVAL=""
AGENT_OUTPUT=""
AGENT_EMIT=""

# ── usage ─────────────────────────────────────────────────────
usage() {
    cat <<EOF
Usage: $0 -n <num_orders> [options]
       $0 -i <input.csv> [options]

Options:
  -n N      Number of orders (generates benchmarks/orders_N.csv if needed)
  -i FILE   Input orders CSV (use existing file directly)
  -r N      Number of runs per variant (default: 10)
  -m MODE   Run mode: interleaved | sequential (default: interleaved)
  -e JAR    Engine JAR path (default: $ENGINE_JAR)
  -a JAR    Agent JAR path (default: $AGENT_JAR)
  -h        Show this help

Agent options:
  -s N      Snapshot levels per side (default: 5)
  -S N      Snapshot interval — emit every N orders (default: 1)
  -o MODE   Agent output mode: file | none (default: file)
  -E BOOL   Emit events: true | false (default: true)

Examples:
  $0 -n 10000
  $0 -n 1000000 -r 5 -m sequential
  $0 -i benchmarks/orders_10000.csv -r 3
  $0 -n 500000 -s 10 -S 100 -E false
EOF
    exit 1
}

# ── parse args ────────────────────────────────────────────────
while getopts "n:i:r:m:e:a:s:S:o:E:h" opt; do
    case $opt in
        n) NUM_ORDERS="$OPTARG" ;;
        i) INPUT="$OPTARG" ;;
        r) RUNS="$OPTARG" ;;
        m) MODE="$OPTARG" ;;
        e) ENGINE_JAR="$OPTARG" ;;
        a) AGENT_JAR="$OPTARG" ;;
        s) SNAPSHOT_LEVELS="$OPTARG" ;;
        S) SNAPSHOT_INTERVAL="$OPTARG" ;;
        o) AGENT_OUTPUT="$OPTARG" ;;
        E) AGENT_EMIT="$OPTARG" ;;
        h) usage ;;
        *) usage ;;
    esac
done

if [ -z "$NUM_ORDERS" ] && [ -z "$INPUT" ]; then
    echo "Error: either -n <num_orders> or -i <input.csv> is required"
    echo ""
    usage
fi

# ── generate orders if -n was used ────────────────────────────
if [ -n "$NUM_ORDERS" ]; then
    INPUT="$SCRIPT_DIR/orders_${NUM_ORDERS}.csv"
    if [ ! -f "$INPUT" ]; then
        echo "Generating $NUM_ORDERS orders..."
        python3 "$SCRIPT_DIR/generate_orders.py" "$NUM_ORDERS" -o "$INPUT"
        echo ""
    fi
fi

if [ ! -f "$INPUT" ]; then
    echo "Error: input file not found: $INPUT"
    exit 1
fi

if [ ! -f "$ENGINE_JAR" ]; then
    echo "Error: engine JAR not found: $ENGINE_JAR"
    echo "Run: mvn clean package"
    exit 1
fi

if [ ! -f "$AGENT_JAR" ]; then
    echo "Error: agent JAR not found: $AGENT_JAR"
    echo "Run: cd agent && mvn clean package"
    exit 1
fi

ORDER_COUNT=$(($(wc -l < "$INPUT") - 1))

# ── output paths (all inside benchmarks/, postfixed with order count) ──
OUT_BASE="$SCRIPT_DIR/exec_baseline_${ORDER_COUNT}.csv"
OUT_AGENT="$SCRIPT_DIR/exec_agent_${ORDER_COUNT}.csv"
OUT_LOG="$SCRIPT_DIR/instrumentation_${ORDER_COUNT}.log"
AGENT_OPTS="-Dmatching.agent.logfile=$OUT_LOG"
[ -n "$SNAPSHOT_LEVELS" ]   && AGENT_OPTS="$AGENT_OPTS -Dmatching.agent.snapshot.levels=$SNAPSHOT_LEVELS"
[ -n "$SNAPSHOT_INTERVAL" ] && AGENT_OPTS="$AGENT_OPTS -Dmatching.agent.snapshot.interval=$SNAPSHOT_INTERVAL"
[ -n "$AGENT_OUTPUT" ]      && AGENT_OPTS="$AGENT_OPTS -Dmatching.agent.output=$AGENT_OUTPUT"
[ -n "$AGENT_EMIT" ]        && AGENT_OPTS="$AGENT_OPTS -Dmatching.agent.emit=$AGENT_EMIT"

echo "================================================================="
echo "  Benchmark — $ORDER_COUNT orders, $RUNS runs ($MODE)"
echo "================================================================="
echo ""
echo "  Engine JAR : $ENGINE_JAR"
echo "  Agent JAR  : $AGENT_JAR"
echo "  Input      : $INPUT"
echo ""

# ── helper: run once and return elapsed ms ────────────────────
run_once() {
    local start end
    start=$(python3 -c "import time; print(int(time.time()*1000))")
    "$@" > /dev/null 2>&1
    end=$(python3 -c "import time; print(int(time.time()*1000))")
    echo $((end - start))
}

# ── warmup (discarded) ────────────────────────────────────────
echo "Warming up JVM..."
run_once java -jar "$ENGINE_JAR" "$INPUT" "$OUT_BASE" > /dev/null
run_once java $AGENT_OPTS -javaagent:"$AGENT_JAR" -jar "$ENGINE_JAR" "$INPUT" "$OUT_AGENT" > /dev/null
echo ""

# ── benchmark runs ────────────────────────────────────────────
NA_TIMES=()
AG_TIMES=()

if [ "$MODE" = "interleaved" ]; then
    echo "Running interleaved (baseline, agent, baseline, agent, ...)..."
    for i in $(seq 1 $RUNS); do
        t1=$(run_once java -jar "$ENGINE_JAR" "$INPUT" "$OUT_BASE")
        t2=$(run_once java $AGENT_OPTS -javaagent:"$AGENT_JAR" -jar "$ENGINE_JAR" "$INPUT" "$OUT_AGENT")
        NA_TIMES+=($t1)
        AG_TIMES+=($t2)
        printf "  Run %2d: baseline=%dms  agent=%dms\n" "$i" "$t1" "$t2"
    done
else
    echo "Running WITHOUT agent..."
    for i in $(seq 1 $RUNS); do
        t=$(run_once java -jar "$ENGINE_JAR" "$INPUT" "$OUT_BASE")
        NA_TIMES+=($t)
        printf "  Run %2d: %dms\n" "$i" "$t"
    done
    echo ""
    echo "Running WITH agent..."
    for i in $(seq 1 $RUNS); do
        t=$(run_once java $AGENT_OPTS -javaagent:"$AGENT_JAR" -jar "$ENGINE_JAR" "$INPUT" "$OUT_AGENT")
        AG_TIMES+=($t)
        printf "  Run %2d: %dms\n" "$i" "$t"
    done
fi

# ── compute and display stats ─────────────────────────────────
python3 - "${NA_TIMES[@]}" "---" "${AG_TIMES[@]}" << 'PYEOF'
import sys, statistics

args = sys.argv[1:]
sep = args.index("---")
na = [int(x) for x in args[:sep]]
ag = [int(x) for x in args[sep+1:]]

def stats(times):
    return {
        "min":    min(times),
        "max":    max(times),
        "mean":   statistics.mean(times),
        "median": statistics.median(times),
        "stdev":  statistics.stdev(times),
    }

s_na = stats(na)
s_ag = stats(ag)

overhead_median = s_ag["median"] - s_na["median"]
ratio_median    = s_ag["median"] / s_na["median"]

print()
print("=================================================================")
print("  Results")
print("=================================================================")
print(f"  {'Metric':<18} {'Without Agent':>15} {'With Agent':>15}")
print(f"  {'-'*50}")
for k in ("min", "max", "mean", "median", "stdev"):
    print(f"  {k:<18} {s_na[k]:>13.1f}ms {s_ag[k]:>13.1f}ms")
print(f"  {'-'*50}")
print(f"  {'Overhead (median)':<18} {'+' + f'{overhead_median:.0f}' + 'ms':>31}")
print(f"  {'Ratio (median)':<18} {ratio_median:>30.2f}x")
print()
PYEOF

# ── verify output consistency ─────────────────────────────────
echo "Verifying output consistency..."
java -jar "$ENGINE_JAR" "$INPUT" "$OUT_BASE" > /dev/null 2>&1
java $AGENT_OPTS -javaagent:"$AGENT_JAR" -jar "$ENGINE_JAR" "$INPUT" "$OUT_AGENT" > /dev/null 2>&1
EXEC_NO=$(wc -l < "$OUT_BASE")
EXEC_AG=$(wc -l < "$OUT_AGENT")
if [ "$EXEC_NO" = "$EXEC_AG" ]; then
    echo "  ✓ Both produced $EXEC_NO lines"
else
    echo "  ✗ Counts differ: $EXEC_NO vs $EXEC_AG"
fi
