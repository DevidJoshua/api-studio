package io.apistudio.engine.expr;

/** Kesalahan sintaks atau evaluasi ekspresi flow. */
public class ExpressionException extends RuntimeException {

    public ExpressionException(String message) {
        super(message);
    }

    public ExpressionException(String message, Throwable cause) {
        super(message, cause);
    }
}
