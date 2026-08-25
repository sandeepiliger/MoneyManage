# Database schema

Room, SQLite, schema version **1**. Schemas are exported to `app/schemas/` (`room.schemaLocation`),
so every future version has a diffable record of what changed.

## Tables

| Table | Holds |
| --- | --- |
| `user_profile` | Display name, currency, language, month-start day, demo flag. One row. |
| `accounts` | Bank, cash, wallet, card, loan and investment accounts. Opening balance only — never a running balance. |
| `categories` | Two-level tree. Seeded set plus anything the user adds. |
| `transactions` | The ledger. Everything else derives from this. |
| `budgets` | Rules, not materialised periods. |
| `recurring_rules` | Rules plus a `lastPostedOn` watermark. |
| `subscriptions` | Services with a billing cycle and a next-payment date. |
| `credit_cards` | Card terms, linked to an account by id. No outstanding amount. |
| `loans` | Principal, rate, tenure. No stored balance. |
| `investments` | Holdings with a manually-entered current value and its date. |
| `goals` | Target, current, optional target date. |
| `tags` | Free-form labels. |
| `receipts` | Image references. The images themselves are files, not blobs. |
| `merchant_rules` | Merchant → category, seeded and learned. |
| `insight_state` | Which insights have been shown or dismissed. |
| `notification_log` | Dedupe keys, so a reminder is never posted twice. |
| `app_state` | Small key/value store — ad frequency counters, first-use date. |

Seventeen tables, all in `core/database/entity/Entities.kt`.

## How money is stored

Every amount is two columns, embedded with a prefix:

```kotlin
data class MoneyColumns(
    @ColumnInfo(name = "minor_units") val minorUnits: Long,
    @ColumnInfo(name = "currency") val currency: String,
)
```

An `@Embedded(prefix = "amount_")` gives `amount_minor_units` and `amount_currency`.

`Long` minor units, not `REAL`. SQLite has no decimal type, and storing ₹1,234.50 as a float
reintroduces binary floating point at the persistence layer after every effort above it to avoid
exactly that. The currency travels with the amount, so a row can never be read back into the wrong
one.

## Enums are stored by name

`Converters` writes `TransactionType.EXPENSE` as the string `"EXPENSE"`, not as ordinal `0`.
Ordinals are positional: inserting a new constant in the middle of an enum silently reinterprets
every existing row. The cost is a few bytes per row; the alternative is a data corruption that
looks like a feature working.

## Tags

Stored as one delimited string, wrapped on both sides with `TAG_DELIMITER` (`U+001F`, the ASCII
unit separator):

```
<US>work<US>urgent<US>
```

The wrapping is what makes `LIKE '%<US>work<US>%'` an exact match rather than a prefix match —
without it, a search for `work` would also match `workshop`. `U+001F` is used because it cannot
appear in a tag anyone typed.

## Indexes

On `transactions`, the only table that grows without bound:

| Index | Serves |
| --- | --- |
| `(deletedAt, occurredOn)` | The main list and every date-ranged query |
| `(accountId, occurredOn)` | Account detail, balance aggregates |
| `(categoryId, occurredOn)` | Category reports, budget evaluation |
| `merchantKey` | Merchant matching on import and on entry |

The leading `deletedAt` matters: every user-facing query filters soft-deleted rows first, so an
index that does not start there would not be used for them.

## Foreign keys

- `transactions.accountId` → `accounts.id`, **RESTRICT**. An account with transactions cannot be
  deleted; `AccountRepository.delete` returns `HasTransactions` with a count, and the UI offers
  archiving instead. Deleting it would take the history with it and silently change every past
  total.
- `transactions.categoryId` → `categories.id`, **SET NULL**. A deleted category leaves its
  transactions uncategorised rather than deleting them, and the confirmation dialog states how
  many rows that affects *before* asking.
- `transactions.transferAccountId` → `accounts.id`, RESTRICT, nullable.

## Soft deletion

`transactions.deletedAt` is a timestamp, not a boolean. A deleted transaction stays for 30 days
(Recently deleted), then `purgeOldDeleted` removes it. Every user-facing query and both balance
implementations filter on `deletedAt IS NULL`.

## Pending rows

`transactions.isPending` marks a row that was imported but not confirmed — an SMS parse, or a
recurring occurrence the user has not acknowledged. Pending rows are **excluded from every total
and every balance**. A pending row is a claim about the world; a balance built on claims is one the
user cannot reconcile against their bank.

## Migration policy

`KhaataDatabase.MIGRATIONS` is currently empty — version 1 has no predecessor.

**`fallbackToDestructiveMigration` is deliberately not set.** On a schema mismatch the app fails
loudly rather than deleting the user's financial history. A crash on upgrade is a bug report; a
silent wipe is an uninstall and a one-star review.

`MigrationExample` is kept in the file as a worked reference for the first real migration: it shows
the create-copy-drop-rename pattern SQLite needs for a column change, with the `PRAGMA
foreign_keys` handling that goes with it.

Adding a migration:

1. Bump `version` on `@Database`.
2. Add a `Migration(n, n + 1)` to `MIGRATIONS`.
3. Commit the newly exported JSON under `app/schemas/`.
4. Write a `MigrationTest` that opens at the old version, inserts a row, migrates, and asserts the
   row survived with the right values. `room-testing` is already a dependency for this.
