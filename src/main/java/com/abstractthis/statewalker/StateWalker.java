package com.abstractthis.statewalker;

//The MIT License (MIT)
//
//Copyright (c) 2013 David Smith <www.abstractthis.com>
//
//Permission is hereby granted, free of charge, to any person obtaining a copy
//of this software and associated documentation files (the "Software"), to deal
//in the Software without restriction, including without limitation the rights
//to use, copy, modify, merge, publish, distribute, sublicense, and/or sell
//copies of the Software, and to permit persons to whom the Software is
//furnished to do so, subject to the following conditions:
//
//The above copyright notice and this permission notice shall be included in
//all copies or substantial portions of the Software.
//
//THE SOFTWARE IS PROVIDED "AS IS", WITHOUT WARRANTY OF ANY KIND, EXPRESS OR
//IMPLIED, INCLUDING BUT NOT LIMITED TO THE WARRANTIES OF MERCHANTABILITY,
//FITNESS FOR A PARTICULAR PURPOSE AND NONINFRINGEMENT. IN NO EVENT SHALL THE
//AUTHORS OR COPYRIGHT HOLDERS BE LIABLE FOR ANY CLAIM, DAMAGES OR OTHER
//LIABILITY, WHETHER IN AN ACTION OF CONTRACT, TORT OR OTHERWISE, ARISING FROM,
//OUT OF OR IN CONNECTION WITH THE SOFTWARE OR THE USE OR OTHER DEALINGS IN
//THE SOFTWARE.

import java.io.IOException;
import java.io.InputStream;
import java.lang.reflect.Constructor;
import java.lang.reflect.InvocationTargetException;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import javax.xml.parsers.ParserConfigurationException;
import javax.xml.parsers.SAXParserFactory;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.xml.sax.Attributes;
import org.xml.sax.InputSource;
import org.xml.sax.SAXException;
import org.xml.sax.XMLReader;
import org.xml.sax.helpers.XMLFilterImpl;

public abstract class StateWalker<T> {
	private static final Logger log = LoggerFactory.getLogger(StateWalker.class);
	private static final String NO_TRANSITION_STATE = "no-transition";
    private static final String DEFAULT_STATE = "default";

    private Map<String, List<StateExecutable<T>>> executables;
    private final List<StateExecutable<T>> NOP_EXES;
    private boolean walkerInitialized = false;

    protected StateWalker() {
        this.executables = new HashMap<String, List<StateExecutable<T>>>();
        this.NOP_EXES = new ArrayList<StateExecutable<T>>(1);
        this.NOP_EXES.add(new NoOpExecutor());
    }

    public void initialize(InputStream is) throws IOException {
        if( walkerInitialized ) {
            throw new IllegalStateException("StateWalker already initialized.");
        }
        this.parseConfigAndInitWalker(is);
        walkerInitialized = true;
    }

    private void parseConfigAndInitWalker(InputStream inStream) throws IOException {
        InputSource is = null;
        try {
            SAXParserFactory spf = SAXParserFactory.newInstance();
            spf.setNamespaceAware(true);
            XMLReader xmlReader = spf.newSAXParser().getXMLReader();
            StateWalkerConfigReader configReader = new StateWalkerConfigReader();
            xmlReader.setContentHandler(configReader);
            is = new InputSource(inStream);
            xmlReader.parse(is);
            configReader = null;
        }
        catch(IOException e) {
            throw e;
        }
        catch(SAXException e) {
            throw new IOException(e);
        }
        catch(ParserConfigurationException e) {
             throw new IOException(e);
        }
    }

    public static <T> int execute(List<StateExecutable<T>> exes, Object params) {
        int executeDepth = 0;
        try {
            int exeCount = exes.size();
            while( executeDepth < exeCount ) {
                exes.get(executeDepth).execute(params);
                executeDepth++;
            }
            // Must subtract one on successful completion because
            // loop termination occurs at List size so using the
            // depth value without decrement will cause an out of
            // bounds exception during finalize calls.
            executeDepth--;
        }
        catch(Exception e) {
            log.warn("StateExecutable problem: ", e);
            // To maintain expected state walker state we must
            // decrement the execution path.
            executeDepth--;
        }
        return executeDepth;
    }
    
    public static <T> boolean hasError(List<StateExecutable<T>> exes, int executeDepth) {
    	return executeDepth != (exes.size()-1);
    }
    
    public static <T> void finalize(List<StateExecutable<T>> exes, boolean noErr, int executeDepth) {
        // Walk backwards finalizing the state executables.
        while( executeDepth >= 0 ) {
            StateExecutable<T> exe = exes.get(executeDepth);
            exe.finalize(noErr);
            exe = null;
            executeDepth--;
        }
        if( !noErr ) {
            throw new StateExecuteException("Issue executing StateExecutables.");
        }
    }

    protected List<StateExecutable<T>> getStateExecutables(T target, String state, String prevState) {
        List<StateExecutable<T>> exeTemplates= null;
        List<StateExecutable<T>> exes = null;
        if( state.equals(prevState) ) {
            exeTemplates = this.executables.get(NO_TRANSITION_STATE);
            exes = this.createInitializedExecutableListCopy(exeTemplates, target);
        }
        else {
            exeTemplates = this.executables.get(state);
            if( exeTemplates == null ) {
                exeTemplates = this.executables.get(DEFAULT_STATE);
            }
            exes = this.createInitializedExecutableListCopy(exeTemplates, target);
        }
        return exes;
    }

    private List<StateExecutable<T>> createInitializedExecutableListCopy(List<StateExecutable<T>> origExes, T target) {
        if( origExes == null ) return this.NOP_EXES;
        List<StateExecutable<T>> copyExes = new ArrayList<StateExecutable<T>>(origExes.size());
        for(StateExecutable<T> exe : origExes) {
            StateExecutable<T> newExe = exe.newExecutableWithExecuteOrderSet();
            newExe.setExecuteTarget(target);
            copyExes.add(newExe);
        }
        return copyExes;
    }

    public abstract String getCurrentState(T target);
    public abstract String getPreviousState(T target);
    public abstract List<StateExecutable<T>> getStateExecutables(T target);

    /**
     * Defines how to process the order state configuration file.
     */
    private class StateWalkerConfigReader extends XMLFilterImpl {
        private final Comparator<StateExecutable<?>> executeOrder = new Comparator<StateExecutable<?>>() {
            @Override
            public int compare(StateExecutable<?> e1, StateExecutable<?> e2) {
                return e1.getExecuteOrder() - e2.getExecuteOrder();
            }
        };
        private boolean docOpen = false;
        private boolean processStates = false;
        private boolean processExe = false;
        private boolean processingExe = false;

        private List<StateExecutable<T>> configExes;
        private String stateName;

        @Override
        public void endDocument() {
            if( docOpen ) {
                executables.clear();
                log.warn("StateWalker config file not closed properly! No state executables initialized!");
            }
        }

        @Override
        public void endElement(String uri, String localName, String qName) {
            if( "states".equals(localName) ) {
                docOpen = false;
                processStates = false;
            }
            else if( "state".equals(localName) ) {
                this.handleStateClose();
            }
            else if( "exe".equals(localName) ) {
                this.handleExeClose();
            }
        }

        @Override
        public void startElement(String uri, String localName, String qName, Attributes atts) {
            if( "states".equals(localName) ) {
                docOpen = true;
                processStates = true;
            }
            else if( "state".equals(localName) ) {
                this.handleStateOpen(uri, atts);
            }
            else if( "exe".equals(localName) ) {
                this.handleExeOpen(uri, atts);
            }
        }

        private void handleStateOpen(String uri, Attributes atts) {
            if( processStates ) {
                stateName = atts.getValue(uri, "name");
                if( stateName != null && !"".equals(stateName.trim()) ) {
                    stateName = stateName.toLowerCase();
                    processExe = true;
                    configExes = new ArrayList<StateExecutable<T>>();
                }
            }
        }

        private void handleStateClose() {
            Collections.sort(configExes, executeOrder);
            executables.put(stateName, configExes);
            processExe = false;
            configExes = null;
            stateName = null;
        }

        private void handleExeOpen(String uri, Attributes atts) {
            if( processExe && !processingExe ) {
                processingExe = true;
                this.addExecutableToList(uri, atts);
            }
        }

        private void handleExeClose() {
            processingExe = false;
        }

        private void addExecutableToList(String uri, Attributes atts) {
            final String ERR_MSG = String.format("A executable for state '%s' is improperly defined. Executable ignored.", this.stateName);
            String exeClass = atts.getValue(uri, "class");
            String exeOrderAttr = atts.getValue(uri, "order");
            if( (exeClass == null || "".equals(exeClass.trim())) ||
                (exeOrderAttr == null || "".equals(exeOrderAttr.trim())) ) {
                log.warn(ERR_MSG);
                return;
            }
            try {
                Integer exeOrder = Integer.valueOf(exeOrderAttr);
                StateExecutable<T> exe = this.createStateExecutable(exeClass, exeOrder);
                if( exe != null ) {
                    configExes.add(exe);
                    log.warn("StateExecutable "+exeClass+" added to list for state "+stateName);
                }
            }
            catch(NumberFormatException e) {
                log.warn(ERR_MSG+" 'order' attribute must be an integer.");
                return;
            }
        }

        private StateExecutable<T> createStateExecutable(String className, Integer exeOrder) {
            try {
                Class<?> handlerClass = Class.forName(className);
                Constructor<?> handlerCtr =
                        handlerClass.getDeclaredConstructor(Integer.class);
                // Can't get around the fact that we have to cast here.
                // Reflection doesn't play nice with generics. The class
                // that implements StateExecutable must specify the correct
                // type in the class declaration.
                @SuppressWarnings("unchecked")
                StateExecutable<T> handler =
                        (StateExecutable<T>)handlerCtr.newInstance(exeOrder);
                return handler;
            }
            catch(NoSuchMethodException nsmEx) {
                String msg = "Constructor that takes an Integer priority missing.";
                log.warn(msg+" StateExecutable implementor class "+className+" ignored.");
            }
            catch(ClassNotFoundException cnfEx) {
                String msg = "StateExecutable implementing class name couldn't be reflected.";
                log.warn(msg+" StateExecutable implementor class "+className+" ignored.");
            }
            catch(IllegalAccessException iaEx) {
                String msg = "System isn't allowing access to the Handler class.";
                log.warn(msg+" StateExecutable implementor class "+className+" ignored.");
            }
            catch(InstantiationException iEx) {
                String msg = "System couldn't create the StateExecutable implementing class.";
                log.warn(msg+" StateExecutable implementor class "+className+" ignored.");
            }
            catch(InvocationTargetException itEx) {
                String msg = "System couldn't call StateExecutable implementing class constructor.";
                log.warn(msg+" StateExecutable implementor class "+className+" ignored.");
            }
            return null;
        }
    }

    /**
     * If no state executable is configured to handle the state the state target
     * is in a <code>NoOpExecutor</code> is provided.
     */
    private class NoOpExecutor implements StateExecutable<T> {

        @Override
        public NoOpExecutor newExecutableWithExecuteOrderSet() {
            // Only want one of the NoOpExecutor
            return this;
        }

        @Override
        public int getExecuteOrder() {
            return 1;
        }

        @Override
        public void execute(Object params) {
        	log.warn("NOP StateExecutable executing...");
            /* NOP */
        }

        @Override
        public void finalize(boolean noErr) {
            /* NOP */
        }

        @Override
        public void setExecuteTarget(T target) {
            /* NOP */
        }

    }
}
