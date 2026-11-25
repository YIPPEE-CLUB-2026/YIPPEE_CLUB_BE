package club.yippee.api.order;

/** The payment company, reduced to the two questions this server asks it. Tests replace it. */
public interface PaymentGateway {

    enum State {
        /** The money was taken. */
        DONE,
        /** Refused, aborted or expired: no money moved and none will. */
        FAILED,
        /** Paid earlier, since fully refunded. */
        CANCELED,
        /** Not finished yet (for example, waiting for a bank transfer). */
        PENDING,
        /** Something a person must look at, such as a partial refund. */
        REVIEW,
        /** No answer, or one that settles nothing: timeout, outage, key problem. Ask again later. */
        UNKNOWN
    }

    record Payment(State state, long totalAmount, String paymentKey, String receiptUrl, String code) {

        public static Payment of(State state, String code) {
            return new Payment(state, 0, null, null, code);
        }
    }

    /** Whether a key is configured, that is, whether a payment can be taken at all. */
    boolean ready();

    /** Asks the company to take the money for an authenticated payment. Safe to repeat. */
    Payment confirm(String paymentKey, String orderId, long amount);

    /**
     * Asks the company to return the whole amount. Safe to repeat. The answer says only whether
     * the request was refused; what the payment now is must be read with lookup.
     */
    Payment cancel(String paymentKey, String reason);

    /** Asks what became of the payment for one of our orders. */
    Payment lookup(String orderId);
}
