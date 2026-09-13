#!/usr/bin/env bash
# burst.sh — one-command probe script for the three invariants
# Usage: ./burst.sh [BASE_URL]
# Default BASE_URL: http://localhost:8080
#
# Auth: bearer token = userId (positive integer).
#   Authorization: Bearer 42  →  authenticated as userId=42
set -euo pipefail

BASE="${1:-http://localhost:8080}"
PASS=0; FAIL=0

green() { printf '\033[32m✓ %s\033[0m\n' "$*"; }
red()   { printf '\033[31m✗ %s\033[0m\n' "$*"; }

check() {
  local label="$1" got="$2" want="$3"
  if [ "$got" = "$want" ]; then
    green "$label (got: $got)"
    PASS=$((PASS+1))
  else
    red   "$label (want: $want, got: $got)"
    FAIL=$((FAIL+1))
  fi
}

# post PATH BODY TOKEN  — POST with auth header
post() { curl -sf -X POST -H 'Content-Type: application/json' -H "Authorization: Bearer $3" -d "$2" "$BASE$1"; }
# get  PATH TOKEN       — GET with auth header
get()  { curl -sf -H "Authorization: Bearer $2" "$BASE$1"; }

echo "============================================================"
echo " Target: $BASE"
echo "============================================================"

# ── 1. RACE-FREE GET-OR-CREATE ────────────────────────────────────────────────
echo
echo "── Test 1: Race-free get-or-create (N=15 concurrent POST /wallets) ──"
# Token IS the userId: use a large random integer so it's unlikely to pre-exist
UID1=$((RANDOM * RANDOM + 100000))

TMPDIR_GC=$(mktemp -d)
for i in $(seq 1 15); do
  post /wallets "{\"initialBalance\":500}" "$UID1" \
    > "$TMPDIR_GC/r$i.json" 2>/dev/null &
done
wait

IDS=$(grep -h '"id"' "$TMPDIR_GC"/*.json | grep -o '"id":[0-9]*' | cut -d: -f2 | sort -u)
ID_COUNT=$(echo "$IDS" | wc -l | tr -d ' ')
WALLET_ID=$(echo "$IDS" | head -1)
check "Exactly 1 wallet created for userId=$UID1" "$ID_COUNT" "1"
rm -rf "$TMPDIR_GC"

# ── 2. IDEMPOTENT RETRY STORM ─────────────────────────────────────────────────
echo
echo "── Test 2: Idempotent retry storm (K=15 concurrent same-key transfers) ──"

# Two fresh users: sender (UID2A) and receiver (UID2B)
UID2A=$((RANDOM * RANDOM + 200000))
UID2B=$((RANDOM * RANDOM + 200000))
SENDER=$(post /wallets   "{\"initialBalance\":10000}" "$UID2A")
RECEIVER=$(post /wallets "{\"initialBalance\":0}"     "$UID2B")
SID=$(echo "$SENDER"   | grep -o '"id":[0-9]*' | head -1 | cut -d: -f2)
RID=$(echo "$RECEIVER" | grep -o '"id":[0-9]*' | head -1 | cut -d: -f2)
IKEY="burst-idem-$(date +%s)-$RANDOM$RANDOM"

TMPDIR_IDEM=$(mktemp -d)
for i in $(seq 1 15); do
  post /transfers \
    "{\"fromWalletId\":$SID,\"toWalletId\":$RID,\"amountPaise\":200,\"idempotencyKey\":\"$IKEY\"}" \
    "$UID2A" \
    > "$TMPDIR_IDEM/r$i.json" 2>/dev/null &
done
wait

TXN_IDS=$(grep -h '"id"' "$TMPDIR_IDEM"/*.json | grep -o '"id":[0-9]*' | cut -d: -f2 | sort -u)
TXN_COUNT=$(echo "$TXN_IDS" | wc -l | tr -d ' ')
check "All 15 replies carry the same transfer id" "$TXN_COUNT" "1"

SENDER_BAL=$(get "/wallets/$SID" "$UID2A" | grep -o '"balance":[0-9]*' | cut -d: -f2)
check "Sender debited exactly once (balance=9800)" "$SENDER_BAL" "9800"
rm -rf "$TMPDIR_IDEM"

# ── 3. CONSERVATION UNDER CONTENTION ─────────────────────────────────────────
echo
echo "── Test 3: Conservation under contention (30 concurrent transfers, 3 wallets) ──"

# Three fresh users, one wallet each
UID3A=$((RANDOM * RANDOM + 300000))
UID3B=$((RANDOM * RANDOM + 300000))
UID3C=$((RANDOM * RANDOM + 300000))
W1=$(post /wallets "{\"initialBalance\":10000}" "$UID3A" | grep -o '"id":[0-9]*' | head -1 | cut -d: -f2)
W2=$(post /wallets "{\"initialBalance\":10000}" "$UID3B" | grep -o '"id":[0-9]*' | head -1 | cut -d: -f2)
W3=$(post /wallets "{\"initialBalance\":10000}" "$UID3C" | grep -o '"id":[0-9]*' | head -1 | cut -d: -f2)
TOTAL_INIT=30000

# Pairs: "from_wallet to_wallet amount auth_token(=owner_userId)"
PAIRS=(
  "$W1 $W2 100 $UID3A"
  "$W2 $W3 150 $UID3B"
  "$W3 $W1 200 $UID3C"
  "$W2 $W1 50 $UID3B"
  "$W1 $W3 120 $UID3A"
  "$W3 $W2 80 $UID3C"
)

TMPDIR_CON=$(mktemp -d)
IDX=0
for i in $(seq 1 30); do
  PAIR="${PAIRS[$((IDX % ${#PAIRS[@]}))]}"
  read -r F T A AUTH <<< "$PAIR"
  post /transfers \
    "{\"fromWalletId\":$F,\"toWalletId\":$T,\"amountPaise\":$A,\"idempotencyKey\":\"con-$(date +%s)-$RANDOM$RANDOM-$i\"}" \
    "$AUTH" \
    > "$TMPDIR_CON/r$i.json" 2>/dev/null &
  IDX=$((IDX+1))
done
wait

B1=$(get "/wallets/$W1" "$UID3A" | grep -o '"balance":[0-9]*' | cut -d: -f2)
B2=$(get "/wallets/$W2" "$UID3B" | grep -o '"balance":[0-9]*' | cut -d: -f2)
B3=$(get "/wallets/$W3" "$UID3C" | grep -o '"balance":[0-9]*' | cut -d: -f2)
TOTAL_FINAL=$((B1 + B2 + B3))

check "Total balance conserved ($B1 + $B2 + $B3 = $TOTAL_FINAL)" "$TOTAL_FINAL" "$TOTAL_INIT"

# Verify no negative balances
NEG=0
[ "$B1" -lt 0 ] && NEG=$((NEG+1))
[ "$B2" -lt 0 ] && NEG=$((NEG+1))
[ "$B3" -lt 0 ] && NEG=$((NEG+1))
check "No wallet has a negative balance" "$NEG" "0"
rm -rf "$TMPDIR_CON"

# ── SUMMARY ───────────────────────────────────────────────────────────────────
echo
echo "============================================================"
echo " Results: $PASS passed, $FAIL failed"
echo "============================================================"
[ "$FAIL" -eq 0 ] && exit 0 || exit 1
