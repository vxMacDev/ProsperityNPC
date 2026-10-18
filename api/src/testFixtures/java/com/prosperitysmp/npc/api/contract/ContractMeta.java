package com.prosperitysmp.npc.api.contract;

import org.junit.platform.engine.TestExecutionResult;
import org.junit.platform.engine.support.descriptor.MethodSource;
import org.junit.platform.launcher.TestExecutionListener;
import org.junit.platform.launcher.TestIdentifier;
import org.junit.platform.launcher.core.LauncherFactory;

import java.lang.reflect.Method;
import java.util.Set;
import java.util.TreeSet;
import java.util.function.UnaryOperator;

import static org.junit.platform.engine.discovery.DiscoverySelectors.selectClass;
import static org.junit.platform.launcher.core.LauncherDiscoveryRequestBuilder.request;

/**
 * Proves a contract suite can fail. It runs a concrete suite through the JUnit Platform launcher, first as written
 * and then with each deliberately broken subject, and reports which case ids failed. A suite that only ever passes
 * proves nothing.
 */
public final class ContractMeta {
    /** While a mutant run is in progress, every subject a contract builds is passed through this. */
    private static volatile UnaryOperator<Object> active;

    private ContractMeta() { }

    /** Outcome of one suite run: cases that ran, cases that failed, and the ids of the failed ones. */
    public record Result(int found, int failed, Set<String> failedCases) { }

    /** A mutant: the id of the case it must break, and how it breaks a subject. */
    public interface Mutant {
        String caseId();

        Object mutate(Object subject);

        /**
         * Other cases this mutant cannot help breaking, because they check the same behaviour from another side
         * (for example a case that needs a bump to count bumps). The meta-test expects exactly
         * {@code caseId()} plus these.
         */
        default java.util.Set<String> alsoBreaks() { return java.util.Set.of(); }
    }

    @SuppressWarnings("unchecked")
    static <S> S wrap(S subject) {
        var mutant = active;
        return mutant == null ? subject : (S) mutant.apply(subject);
    }

    /** Runs the suite as written. */
    public static Result run(Class<?> suite) {
        return runWith(suite, null);
    }

    /** The failed case ids of a plain run. */
    public static Set<String> failedCases(Class<?> suite) {
        return run(suite).failedCases();
    }

    /** Runs the suite with every subject broken by {@code mutant}. Not reentrant; one run at a time. */
    public static synchronized Result runWith(Class<?> suite, Mutant mutant) {
        active = mutant == null ? null : mutant::mutate;
        try {
            var failed = new TreeSet<String>();
            int[] counts = new int[2];
            var listener = new TestExecutionListener() {
                @Override
                public void executionFinished(TestIdentifier id, TestExecutionResult result) {
                    if (!id.isTest()) return;
                    counts[0]++;
                    // A case skipped by an assumption (a backend without that feature) neither passes nor fails.
                    if (result.getStatus() != TestExecutionResult.Status.FAILED) return;
                    counts[1]++;
                    failed.add(caseId(id));
                }
            };
            LauncherFactory.create().execute(request().selectors(selectClass(suite)).build(), listener);
            return new Result(counts[0], counts[1], failed);
        } finally {
            active = null;
        }
    }

    /** Every case id of a contract class marked critical. */
    public static Set<String> criticalCases(Class<?> contract) {
        var out = new TreeSet<String>();
        for (Class<?> type = contract; type != null; type = type.getSuperclass())
            for (Method method : type.getDeclaredMethods()) {
                CaseId id = method.getAnnotation(CaseId.class);
                if (id != null && id.critical()) out.add(id.value());
            }
        return out;
    }

    private static String caseId(TestIdentifier id) {
        if (id.getSource().orElse(null) instanceof MethodSource source) {
            try {
                Method method = source.getJavaMethod();
                CaseId caseId = method.getAnnotation(CaseId.class);
                if (caseId != null) return caseId.value();
            } catch (RuntimeException unresolved) {
                // Fall through to the display name.
            }
        }
        return id.getDisplayName();
    }
}
