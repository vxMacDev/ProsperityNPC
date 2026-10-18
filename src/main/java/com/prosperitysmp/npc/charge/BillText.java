package com.prosperitysmp.npc.charge;

import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import com.prosperitysmp.npc.economy.BillPayments;

/** Frozen bill amounts and their payment state. */
public final class BillText {
    private BillText() { }

    public static final String HOW_BILLS_WORK = "How bills work: once a day, after rent, each home can get one bill. "
            + "The amounts are fixed when it is issued. Pay it with /property bills pay <bill> <maximum-dollars>. "
            + "If this server holds rent for unpaid bills, a home's rent is paused while a bill from an earlier day "
            + "is unpaid, until every such bill is paid or expires.";

    /**
     * Whether this bill is pausing the home's rent today under {@code on-unpaid: hold-rent}: still unpaid, from an
     * earlier day and still payable. The rent batch uses the same rule.
     */
    public static boolean holdsRent(ChargeInvoiceRepository.Invoice invoice, LocalDate today) {
        return invoice.state() == ChargeInvoiceRepository.State.ISSUED && invoice.day().isBefore(today)
                && !invoice.payableUntil().isBefore(today);
    }

    /** The owner line for a bill that is pausing rent. The bill expires the day after its last payable day. */
    public static String rentPaused(ChargeInvoiceRepository.Invoice invoice) {
        return rentPaused(invoice, false);
    }

    /**
     * As above. {@code shared} is true when another bill may be pausing the same home's rent, so paying this one
     * alone may not bring rent back.
     */
    public static String rentPaused(ChargeInvoiceRepository.Invoice invoice, boolean shared) {
        LocalDate expires = invoice.payableUntil().plusDays(1);
        return shared ? "Rent is paused for this home until every unpaid bill from an earlier day is paid or expires. "
                + "This bill expires on " + expires + "."
                : "Rent is paused for this home until this bill is paid or expires on " + expires + ".";
    }

    /**
     * Whether more than one bill may be pausing this home's rent: two or more on this page, or one here while
     * older pages exist (an older bill may still be unpaid and payable).
     */
    public static boolean pauseShared(ChargeInvoiceRepository.OwnerView view, LocalDate today) {
        long holding = view.invoices().stream().filter(invoice -> holdsRent(invoice, today)).count();
        return holding > 1 || (holding == 1 && view.hasOlder());
    }

    /** One line per bill in the view that is pausing rent, naming its day; none unless the view holds rent. */
    public static List<String> rentPausedNotes(ChargeInvoiceRepository.OwnerView view, LocalDate today) {
        if (!view.holdRent()) return List.of();
        boolean shared = pauseShared(view, today);
        List<String> out = new ArrayList<>();
        for (var invoice : view.invoices())
            if (holdsRent(invoice, today)) out.add("Bill from " + invoice.day() + ": " + rentPaused(invoice, shared));
        return List.copyOf(out);
    }

    /** One page for a home, in the repository's newest-first order. */
    public static List<String> list(List<ChargeInvoiceRepository.Invoice> invoices, Map<String, String> titles, LocalDate today) {
        return list(new ChargeInvoiceRepository.OwnerView(invoices, titles), today);
    }

    public static List<String> list(ChargeInvoiceRepository.OwnerView view, LocalDate today) {
        var invoices = view.invoices();
        var titles = view.titles();
        List<String> out = new ArrayList<>();
        if (invoices.isEmpty()) {
            out.add("This home has no bills.");
            return out;
        }
        boolean shared = pauseShared(view, today);
        for (var invoice : invoices) {
            out.add(shortId(invoice) + "  " + invoice.day() + "  " + BillingRuntime.dollars(invoice.totalCents()) + "  "
                    + state(invoice, view.payments().get(invoice.invoiceId()), today));
            for (var line : invoice.lines())
                out.add("    " + titles.getOrDefault(line.policySha(), line.policyId() + " v" + line.version()) + ": "
                        + BillingRuntime.dollars(line.finalCents()) + (line.capped() ? " (capped at its maximum)" : ""));
            if (view.holdRent() && holdsRent(invoice, today)) out.add("    " + rentPaused(invoice, shared));
        }
        return out;
    }

    public static String state(ChargeInvoiceRepository.Invoice invoice, LocalDate today) {
        return switch (invoice.state()) {
            case PAID -> "paid";
            case EXPIRED -> "expired, nothing owed";
            case ISSUED -> today.isAfter(invoice.payableUntil()) ? "expired, nothing owed"
                    : "unpaid, can be paid until " + invoice.payableUntil();
        };
    }

    public static String state(ChargeInvoiceRepository.Invoice invoice, ChargeInvoiceRepository.PaymentRef payment, LocalDate today) {
        if (payment != null && payment.state() == BillPayments.State.WAITING) return "payment pending";
        if (payment != null && payment.state() == BillPayments.State.REVIEW_REQUIRED) return "held for a staff check";
        return state(invoice, today);
    }

    public static boolean payable(ChargeInvoiceRepository.Invoice invoice, ChargeInvoiceRepository.PaymentRef payment,
                                  LocalDate today) {
        return invoice.state() == ChargeInvoiceRepository.State.ISSUED && !today.isBefore(invoice.day())
                && !today.isAfter(invoice.payableUntil()) && (payment == null || payment.state() == BillPayments.State.DECLINED);
    }

    public static String payCommand(ChargeInvoiceRepository.Invoice invoice) {
        return "/property bills pay " + invoice.invoiceId() + " " + java.math.BigDecimal.valueOf(invoice.totalCents(), 2).toPlainString();
    }

    public static List<String> detail(ChargeInvoiceRepository.Invoice invoice, Map<String, String> titles,
                                      ChargeInvoiceRepository.PaymentRef payment, LocalDate today) {
        return detail(invoice, titles, payment, today, false);
    }

    /** As above; with {@code holdRent} on, a bill that is pausing rent says so. */
    public static List<String> detail(ChargeInvoiceRepository.Invoice invoice, Map<String, String> titles,
                                      ChargeInvoiceRepository.PaymentRef payment, LocalDate today, boolean holdRent) {
        return detail(invoice, titles, payment, today, holdRent, false);
    }

    /** As above; {@code shared} as in {@link #rentPaused(ChargeInvoiceRepository.Invoice, boolean)}. */
    public static List<String> detail(ChargeInvoiceRepository.Invoice invoice, Map<String, String> titles,
                                      ChargeInvoiceRepository.PaymentRef payment, LocalDate today, boolean holdRent,
                                      boolean shared) {
        var out = new ArrayList<String>();
        out.add("Bill: " + invoice.invoiceId());
        out.add("Economy day: " + invoice.day());
        out.add("Total: " + BillingRuntime.dollars(invoice.totalCents()));
        out.add(state(invoice, payment, today));
        for (var line : invoice.lines()) out.add(titles.getOrDefault(line.policySha(), line.policyId() + " v" + line.version())
                + ": " + BillingRuntime.dollars(line.finalCents()) + (line.capped() ? " (capped at its maximum)" : ""));
        if (payment != null) {
            if (payment.state() == BillPayments.State.WAITING)
                out.add("Payment is still being checked. Do not pay again yet.");
            else if (payment.state() == BillPayments.State.REVIEW_REQUIRED)
                out.add("Payment needs a staff check. Do not pay again.");
            out.add("/property receipt " + payment.requestId());
        }
        if (holdRent && holdsRent(invoice, today)) out.add(rentPaused(invoice, shared));
        return List.copyOf(out);
    }

    /** The first 8 characters of the bill id, which owners type to pay it. */
    public static String shortId(ChargeInvoiceRepository.Invoice invoice) {
        return invoice.invoiceId().toString().substring(0, 8);
    }

    /** The bill whose id starts with {@code text} (at least 8 characters), when exactly one matches. */
    public static java.util.Optional<ChargeInvoiceRepository.Invoice> match(List<ChargeInvoiceRepository.Invoice> invoices, String text) {
        if (text == null || text.length() < 8) return java.util.Optional.empty();
        String prefix = text.toLowerCase(java.util.Locale.ROOT);
        var found = invoices.stream().filter(i -> i.invoiceId().toString().startsWith(prefix)).toList();
        return found.size() == 1 ? java.util.Optional.of(found.getFirst()) : java.util.Optional.empty();
    }
}
