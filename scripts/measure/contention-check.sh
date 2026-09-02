#!/usr/bin/env bash
# THE INVARIANTS AFTER A CONTENTION RUN (`B-117`, measurement 3), read from the database and not
# from the responses: no account below zero, every account's balance equal to the sum of its
# ledger (hold −, release +, top-up +, reversal −, capture and decline 0 — so the sum IS the
# balance), no order held or captured twice. Anything but zeros is the finding.
set -euo pipefail
PROJECT=${PROJECT:-konekt}
q() { docker exec "${PROJECT}-postgres-1" psql -U konekt -d konekt -Atc "$1"; }
echo "accounts below zero:          $(q "select count(*) from account where balance_minor < 0")"
echo "accounts off their ledger:    $(q "select count(*) from account a where a.balance_minor <> coalesce((select sum(amount_minor) from ledger_entry l where l.account_id = a.id), 0)")"
echo "orders captured more than once: $(q "select count(*) from (select order_id from ledger_entry where kind = 'capture' group by order_id having count(*) > 1) d")"
echo "orders held more than once:     $(q "select count(*) from (select order_id from ledger_entry where kind = 'hold' group by order_id having count(*) > 1) d")"
echo "--- movements by kind:"
q "select kind, count(*), sum(amount_minor) from ledger_entry group by kind order by kind"
