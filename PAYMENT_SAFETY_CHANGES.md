# PaboNaki additive update: payment safety + advanced features

## Preservation guarantee
This archive is based on the exact original `PaboNaki_Complete_final (3).zip`.
All original archive entries are preserved. Existing source files changed only where required:
- `SecurityConfig.java`: permits the new `/advanced.html` page.
- `IntegrationService.java`: hardens SSLCOMMERZ server-side validation and idempotent paid-state update.
- `OrderRepository.java`: adds a transaction-ID lookup used by validation.
- `IntegrationController.java`: blocks payment initiation for non-pending orders.

No original files were intentionally removed. The existing `.idea` and `backend/target/classes` entries are preserved as they were in the supplied archive. `target/classes` is build output and may be stale; Maven should rebuild it from source.

## Payment safety changes
- Never trusts the browser callback alone; requires a successful server-to-server SSLCOMMERZ validation response.
- Requires matching transaction ID, order, ONLINE payment method, BDT currency and amount.
- Rejects invalid callback status, cancelled orders and orders not pending.
- Uses a MongoDB atomic conditional update so concurrent duplicate callbacks cannot both transition an unpaid order to PAID.
- Repeated verified callbacks for an already-paid order are idempotent and do not send a duplicate order email.
- Payment initiation is rejected for cancelled/completed/non-pending orders.

## Important limits
- These changes do not implement a full stock-reservation ledger or guarantee database transactions across multiple product documents.
- No Maven executable/wrapper was available in the build environment, so compilation and end-to-end gateway tests could not be run here.
- Before production, test SSLCOMMERZ sandbox success/fail/cancel/IPN flows, and rotate any credentials that were previously shared or committed.
