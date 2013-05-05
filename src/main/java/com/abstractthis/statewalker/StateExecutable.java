package com.abstractthis.statewalker;

/**
*
* @author dshmif
*/
public interface StateExecutable<T> {
   public StateExecutable<T> newExecutableWithExecuteOrderSet();
   public int getExecuteOrder();
   public void setExecuteTarget(T target);
   public void execute(Object params);
   public void finalize(boolean noErr);
}
