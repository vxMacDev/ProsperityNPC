package com.prosperitysmp.npc.charge;

/** The one error a bill formula can raise, at load or while working out a line. Its message is safe to show staff. */
public final class FormulaException extends RuntimeException {
    private static final long serialVersionUID = 1L;

    public FormulaException(String message) {
        super(message);
    }
}
