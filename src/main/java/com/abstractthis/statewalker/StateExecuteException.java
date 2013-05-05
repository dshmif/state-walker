package com.abstractthis.statewalker;

/**
 * This unchecked exception represents trouble during state machine execution.
 *
 * @author dshmif
 */
public class StateExecuteException extends RuntimeException {
	private static final long serialVersionUID = -4390551082080142424L;

	public StateExecuteException() {
        super();
    }

    public StateExecuteException(String msg) {
        super(msg);
    }

    public StateExecuteException(Throwable cause) {
        super(cause);
    }

    public StateExecuteException(String msg, Throwable cause) {
        super(msg, cause);
    }

}
