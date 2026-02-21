#!/usr/bin/env python3
"""Generate sample orders CSV with realistic market data.

Usage:
    python3 benchmarks/generate_orders.py [NUM_ORDERS]

    NUM_ORDERS defaults to 10000. Output file is written to
    benchmarks/orders_<NUM_ORDERS>.csv (e.g. benchmarks/orders_500000.csv).
"""

import argparse
import random
import struct
import base64
import uuid
import csv
import os

random.seed(42)

MID_PRICE = 100.00
BID_RANGE = (round(MID_PRICE - 0.50, 2), round(MID_PRICE - 0.01, 2))
ASK_RANGE = (round(MID_PRICE + 0.01, 2), round(MID_PRICE + 0.50, 2))
MARKETABLE_CHANCE = 0.35   # 35% marketable limit (will match)
MARKET_ORDER_CHANCE = 0.15 # 15% pure market orders


def uuid_to_base64(u: uuid.UUID) -> str:
    b = struct.pack('>QQ', u.int >> 64, u.int & 0xFFFFFFFFFFFFFFFF)
    return base64.urlsafe_b64encode(b).decode().rstrip('=')


def generate(num_orders: int, output_path: str) -> None:
    rows = [["orderId", "side", "orderType", "quantity", "price"]]

    for _ in range(num_orders):
        order_id = uuid_to_base64(uuid.uuid4())
        side = "BUY" if random.random() < 0.5 else "SELL"
        qty = random.randint(1, 50)

        r = random.random()
        if r < MARKET_ORDER_CHANCE:
            order_type = "MARKET"
            price = ""
        elif r < MARKET_ORDER_CHANCE + MARKETABLE_CHANCE:
            order_type = "LIMIT"
            if side == "BUY":
                price = round(random.uniform(ASK_RANGE[0], ASK_RANGE[1] + 0.20), 2)
            else:
                price = round(random.uniform(BID_RANGE[0] - 0.20, BID_RANGE[1]), 2)
        else:
            order_type = "LIMIT"
            if side == "BUY":
                price = round(random.uniform(BID_RANGE[0], BID_RANGE[1]), 2)
            else:
                price = round(random.uniform(ASK_RANGE[0], ASK_RANGE[1]), 2)

        rows.append([order_id, side, order_type, qty, price])

    with open(output_path, "w", newline="") as f:
        csv.writer(f).writerows(rows)

    limit_count  = sum(1 for r in rows[1:] if r[2] == "LIMIT")
    market_count = sum(1 for r in rows[1:] if r[2] == "MARKET")
    buy_count    = sum(1 for r in rows[1:] if r[1] == "BUY")
    sell_count   = sum(1 for r in rows[1:] if r[1] == "SELL")

    print(f"Generated {num_orders} orders -> {output_path}")
    print(f"  BUY: {buy_count}   SELL: {sell_count}")
    print(f"  LIMIT: {limit_count}   MARKET: {market_count}")


if __name__ == "__main__":
    parser = argparse.ArgumentParser(description="Generate benchmark order data")
    parser.add_argument("num_orders", nargs="?", type=int, default=10_000,
                        help="Number of orders to generate (default: 10000)")
    parser.add_argument("-o", "--output", type=str, default=None,
                        help="Output file path (default: benchmarks/orders_<N>.csv)")
    args = parser.parse_args()

    script_dir = os.path.dirname(os.path.abspath(__file__))
    if args.output:
        output_path = args.output
    else:
        output_path = os.path.join(script_dir, f"orders_{args.num_orders}.csv")

    generate(args.num_orders, output_path)
