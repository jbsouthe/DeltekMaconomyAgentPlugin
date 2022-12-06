package com.cisco.josouthe;

import com.appdynamics.agent.api.AppdynamicsAgent;
import com.appdynamics.agent.api.EntryTypes;
import com.appdynamics.agent.api.Transaction;
import com.appdynamics.agent.api.impl.NoOpTransaction;
import com.appdynamics.instrumentation.sdk.Rule;
import com.appdynamics.instrumentation.sdk.SDKClassMatchType;
import com.appdynamics.instrumentation.sdk.SDKStringMatchType;
import com.appdynamics.instrumentation.sdk.toolbox.reflection.IReflector;

import java.util.ArrayList;
import java.util.Iterator;
import java.util.List;

public class McWorkspaceApiInterceptor extends MyBaseInterceptor {

    private IReflector getRequestId, getWorkspaceName, getPaneRequest, getPaneNames, getRequestTreeIterator, getContentPaneName, asString;
    private IReflector getContainerPaneName;

    public McWorkspaceApiInterceptor() {
        super();

        getRequestId = getNewReflectionBuilder().invokeInstanceMethod("getIdentity", true).invokeInstanceMethod("toString", true).build(); //String
        getWorkspaceName = getNewReflectionBuilder().invokeInstanceMethod("getWorkspaceName", true).invokeInstanceMethod("asString", true).build(); //String
        getPaneRequest = getNewReflectionBuilder().invokeInstanceMethod("getPaneRequest", true).invokeInstanceMethod("toString", true).build(); //String
        getPaneNames = getNewReflectionBuilder().invokeInstanceMethod("getRequestTree", true).invokeInstanceMethod("toString", true).build(); //String
        getRequestTreeIterator = getNewReflectionBuilder().invokeInstanceMethod("getRequestTree", true).invokeInstanceMethod("iterator", true).build(); //Iterator<com.maconomy.api.workspace.request.MiWorkspaceRequestTreeNode>
        getContentPaneName = getNewReflectionBuilder().invokeInstanceMethod("getContent", true).invokeInstanceMethod("getContainerPaneName", true).invokeInstanceMethod("asString", true).build(); //String
        asString = getNewReflectionBuilder().invokeInstanceMethod("asString", true).build(); //String

        getContainerPaneName = getNewReflectionBuilder().invokeInstanceMethod("getContainerPaneName", true).invokeInstanceMethod("asString", true).build();
    }

    @Override
    public Object onMethodBegin(Object objectIntercepted, String className, String methodName, Object[] params) {
        this.getLogger().debug("McWorkspaceApiInterceptor.onMethodBegin() start: "+ className +"."+ methodName +"()");
        Transaction transaction = AppdynamicsAgent.getTransaction();
        switch (methodName) {
            case "handleDataRequest": {
                Object request = params[0];
                String workspaceName = getReflectiveString(request, getWorkspaceName, "UNKNOWN-WORKSPACE");
                String btName = String.format("McWorkspace:Data:%s", workspaceName);

                if( transaction instanceof NoOpTransaction ) {
                    transaction = AppdynamicsAgent.startTransaction(btName, getCorrelationHeader(request), EntryTypes.POJO, false);
                } else {
                    AppdynamicsAgent.setCurrentTransactionName(btName);
                }
                transaction.collectData("RequestID", getReflectiveString(request, getRequestId, "UNKNOWN-ID"), snapshotDatascopeOnly );
                transaction.collectData("PaneRequest", getReflectiveString(request, getPaneRequest, "UNKNOWN-PANE"), dataScopes);
                transaction.collectData("RootPane", getReflectiveString(request, getPaneNames, "UNKNOWN-PANE"), dataScopes);
                Iterator<Object> it = (Iterator<Object>) getReflectiveObject(request, getRequestTreeIterator);
                if( it != null ) {
                    for(int i=0; it.hasNext(); i++) {
                        Object next = it.next();
                        transaction.collectData(String.format("Pane.%d",i), getReflectiveString(next, getContentPaneName, "UNKNOWN-PANE"), snapshotDatascopeOnly);
                    }
                }
                break;
            }
            case "doStaticDataRequest": {
                String btName = String.format("McWorkspace:Spec:%s", getReflectiveString(params[0], asString, "UNKNOWN-WORKSPACE"));
                if( transaction instanceof NoOpTransaction ) {
                    transaction = AppdynamicsAgent.startTransaction(btName, getCorrelationHeader(null), EntryTypes.POJO, false);
                } else {
                    AppdynamicsAgent.setCurrentTransactionName(btName);
                }
                break;
            }
            case "doWorkspacePaneSpecRequest": {
                Object request = params[0];
                String workspaceName = getReflectiveString(request, getWorkspaceName, "UNKNOWN-WORKSPACE");
                String btName = String.format("McWorkspace:PaneSpec:%s", workspaceName);

                if( transaction instanceof NoOpTransaction ) {
                    transaction = AppdynamicsAgent.startTransaction(btName, getCorrelationHeader(request), EntryTypes.POJO, false);
                } else {
                    AppdynamicsAgent.setCurrentTransactionName(btName);
                }
                transaction.collectData("PaneName", getReflectiveString(request, getContainerPaneName, "UNKNOWN-PANE"), dataScopes);
                break;
            }
        }
        this.getLogger().debug("McWorkspaceApiInterceptor.onMethodBegin() finish: "+ className +"."+ methodName +"()");
        return transaction;
    }

    private String getCorrelationHeader(Object request) {
        if( request == null ) return null;
        //TODO need to figure out how to read an optional correlation header message from the upstream transaction exit point :) JBS
        return null;
    }

    @Override
    public void onMethodEnd(Object state, Object object, String className, String methodName, Object[] params, Throwable exception, Object returnVal) {
        this.getLogger().debug("McWorkspaceApiInterceptor.onMethodEnd() start: "+ className +"."+ methodName +"()");

        Transaction transaction = (Transaction) state;
        if( transaction == null ) return;
        if( exception != null ) {
            transaction.markAsError(String.format("Error: %s", exception.getMessage()));
        }
        transaction.end();
        this.getLogger().debug("McWorkspaceApiInterceptor.onMethodEnd() finish: "+ className +"."+ methodName +"()");
    }

    @Override
    public List<Rule> initializeRules() {
        List<Rule> rules = new ArrayList<Rule>();
        for( String methodName : new String[] {"doStaticDataRequest", "handleDataRequest", "doWorkspacePaneSpecRequest"})
            rules.add(new Rule.Builder(
                    "com.maconomy.coupling.workspace.McWorkspaceApi")
                    .classMatchType(SDKClassMatchType.MATCHES_CLASS)
                    .methodMatchString(methodName)
                    .methodStringMatchType( SDKStringMatchType.EQUALS)
                    .build()
            );

        return rules;
    }
}
