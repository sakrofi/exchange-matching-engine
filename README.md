
# Java Matching Engine

A Java 25 exchange matching engine with a market-data replay pipeline and multiple order-book implementations.

## Overview

This project implements a FIFO matching engine backed by interchangeable order-book implementations.

The replay pipeline currently uses a single-symbol AAPL ITCH 5.0 sample as its market-data source. The source file is the UDP-formatted ITCH feed captured for the replay study.

The raw feed is converted into a compact fixed-width binary representation before being replayed through the matching engine.

The order-book implementations share a common interface, allowing the same matching engine and replay pipeline to operate against different underlying data structures.

---

## Architecture

```text
                  ITCH 5.0 / UDP Feed
                         │
                         ▼
                 ┌───────────────┐
                 │  ITCH Parser  │
                 └───────┬───────┘
                         │
                         ▼
                 ┌───────────────┐
                 │ Binary Replay │
                 │    Records    │
                 └───────┬───────┘
                         │
                         ▼
                 ┌───────────────┐
                 │   Replayer    │
                 └───────┬───────┘
                         │
                         ▼
                 ┌───────────────┐
                 │ FifoMatcher   │
                 └───────┬───────┘
                         │
                         ▼
                 ┌───────────────┐
                 │  OrderBook    │
                 └───────┬───────┘
                         │
      ┌──────────────┬──────────────┬──────────────┐
      ▼              ▼              ▼              ▼
  Linear Array    TreeMap/RB      Bitmap       Pooled Tree
````

The replay path is intentionally synchronous.

---

# Order Book Implementations

The project implements four order-book architectures behind a common interface. They deliberately represent different approaches to the same problem.

## 1. Linear Array

Orders are stored in primitive arrays, providing compact contiguous storage and avoiding object-heavy data structures.

Operations that require searching through active orders use linear traversal.

### Characteristics

- Primitive array storage
- Contiguous memory access
- Minimal object allocation
- O(n) search for operations requiring traversal
- Useful baseline for measuring the benefit of indexing

---

## 2. Red-Black Tree / `TreeMap`

The baseline tree implementation uses Java's `TreeMap` to maintain sorted price levels.

Each price level contains FIFO resting orders, preserving time priority within that level.

```
| Offset | Size | Field     |
|-------:|-----:|-----------|
| 0      | 8    | Timestamp |
| 8      | 8    | Order ID  |
| 16     | 4    | Price     |
| 20     | 4    | Quantity  |
| 24     | 1    | Event     |
| 25     | 1    | Side      |
| 26     | 6    | Padding   |
```

The tree provides ordered price-level access while the per-level FIFO structure preserves time priority.

---

## 3. Object-Pooled Red-Black Tree

The pooled implementation retains the tree-based price-level structure while reusing order objects through an object pool.

The purpose is to isolate the effect of object allocation and reuse from the underlying tree representation.

Pooling reduces allocation associated with order objects, although the surrounding tree structure still has its own object and allocation costs.

---

## 4. Hierarchical Bitmap

The bitmap implementation replaces tree traversal for price-level discovery with a hierarchical bitmap over the supported price domain.

The bitmap tracks occupied price positions and uses hierarchical levels to skip groups of empty positions.

The implementation uses primitive arrays for order storage and a primitive order-ID-to-slot mapping, keeping the hot data structures compact.

The supported price domain is bounded because the bitmap represents price positions explicitly.

---

# Replay Pipeline

## ITCH 5.0 Market Data

The replay application currently targets a single AAPL instrument.

The test input is an ITCH 5.0 market-data sample captured in UDP format. The parser extracts the subset of message types required to reconstruct the order flow used by the replay:

- Add Order
- Delete Order
- Cancel / quantity reduction
- Other ITCH message types are currently ignored.

The current replay therefore reconstructs a partial order-book history rather than a complete exchange feed.
Market data is framed using MoldUDP64-style length-prefixed messages, with message payloads conforming to the Nasdaq TotalView-ITCH 5.0 specification.

---

## Binary Replay Format

The raw ITCH messages are converted into fixed-width 32-byte binary records before replay.

Each record contains:

```
+----------------+----------------+
| Timestamp (8)  | Order ID (8)  |
+----------------+----------------+
| Price (4)      | Quantity (4)  |
+----------------+----------------+
| Event (1)      | Side (1)      |
+----------------+----------------+
|     Padding (6 bytes)            |
+----------------------------------+
```

The binary representation separates feed parsing from matching-engine replay.

Once converted, the same deterministic event stream can be replayed repeatedly without reparsing the original ITCH input on every run.

---

## Replay Price Domain

The current replay uses a bounded price:

```
MIN_PRICE <= price <= MAX_PRICE
```

Orders outside the configured range are skipped before reaching the matching engine.

This is required because the order-book implementations operate over bounded price domains, particularly the array- and bitmap-based implementations.

Out-of-range records are not silently discarded. The replayer tracks and reports the number skipped during the replay.

For the current AAPL replay, this also excludes certain special-price orders present in the source feed whose encoded prices lie outside the configured domain.

The replay should therefore be treated as a bounded reconstruction of the historical order flow rather than a complete reconstruction of every source order.

---

## Replay Results

The replay reports:

- processed records
- skipped out-of-range records
- successful cancels
- successful quantity reductions
- rejected operations

The diagnostic replay can additionally use a list-backed trade sink to retain the chronological sink events for inspection.

The normal lightweight replay path can use a null sink when individual events do not need to be retained.

---

# Matching Engine

The `FifoMatcher` applies price-time priority matching against the selected order book.

For a new order:

1. Inspect the best order on the opposite side.
2. Check whether the incoming price crosses the resting price.
3. Execute the smaller of the remaining quantities.
4. Remove fully consumed resting orders or reduce partially filled orders.
5. Add any remaining quantity as a resting order.

The matcher publishes trade, cancel, modification and rejection events through a `TradeSink`.

---

# Correctness

The order-book implementations are tested through a common set of behavioural tests covering operations such as:

- Add and inspect
- Cancel
- Quantity reduction
- Best-price polling
- FIFO priority
- Bid/ask isolation
- Clearing the book

The matcher is also tested independently across the supported order-book implementations.

---

# Limitations

- The current replay configuration is single-symbol and currently targets AAPL.
- The input parser supports only the subset of ITCH messages required by the current replay.
- The replay uses a bounded price domain; out-of-range source orders are skipped and counted.
- The replay reconstructs a partial historical book rather than the complete exchange state.
- There is no persistence layer.
- Market data is framed using MoldUDP64-style length-prefixed messages.
- No risk-management or pre-trade risk checks are implemented.
- Advanced order types are not implemented.
- The market-data input is currently based on the MoldUDP64 format from a public itch Sample

---

# How to Run

## Build and Test

```
./gradlew test
```

## Convert ITCH Data to Binary

```
./gradlew preprocessItch \
  -Pinput=/path/to/ITCH_FILE \
  -Poutput=/path/to/output.bin
```

## Replay

```
./gradlew replay \
  -Pinput=/path/to/output.bin \
  -Pbook=LinearSearchOrderBook \
  -PSink=NULL
```****