package com.cisco.josouthe;

import com.appdynamics.agent.api.AppdynamicsAgent;
import com.appdynamics.agent.api.EntryTypes;
import com.appdynamics.agent.api.ServletContext;
import com.appdynamics.agent.api.Transaction;
import com.appdynamics.agent.api.impl.NoOpTransaction;
import com.appdynamics.instrumentation.sdk.Rule;
import com.appdynamics.instrumentation.sdk.SDKClassMatchType;
import com.appdynamics.instrumentation.sdk.SDKStringMatchType;
import com.appdynamics.instrumentation.sdk.toolbox.reflection.IReflector;
import com.appdynamics.instrumentation.sdk.toolbox.reflection.ReflectorException;

import java.net.MalformedURLException;
import java.util.ArrayList;
import java.util.Iterator;
import java.util.List;

public class FilterRequestHandlerInterceptor extends MyBaseInterceptor {

    private IReflector getRequestId, getClientKey, getMethod, getURL, getQuery, getStatus;
    private IReflector getHeader;

    public FilterRequestHandlerInterceptor() {
        super();

        getRequestId = getNewReflectionBuilder().accessFieldValue("requestId", true).invokeInstanceMethod("toString", true).build(); //String
        getClientKey = getNewReflectionBuilder().accessFieldValue("clientKey", true).invokeInstanceMethod("asString", true).build(); //String
        getMethod = getNewReflectionBuilder().accessFieldValue("request", true).invokeInstanceMethod("getMethod", true).build(); //String
        getURL = getNewReflectionBuilder().accessFieldValue("request", true).invokeInstanceMethod("getRequestURL", true).invokeInstanceMethod("toString", true).build(); //String
        getQuery = getNewReflectionBuilder().accessFieldValue("request", true).invokeInstanceMethod("getQueryString", true).build(); //String
        getStatus = getNewReflectionBuilder().accessFieldValue("response", true).invokeInstanceMethod("getStatus", true).build(); //Integer

        getHeader = getNewReflectionBuilder().accessFieldValue("request", true).invokeInstanceMethod("getHeader", true, new String[]{ String.class.getCanonicalName()}).build(); //String
    }

    @Override
    public Object onMethodBegin(Object objectIntercepted, String className, String methodName, Object[] params) {
        this.getLogger().debug("McWorkspaceApiInterceptor.onMethodBegin() start: "+ className +"."+ methodName +"()");

        Transaction transaction = AppdynamicsAgent.getTransaction();
        if( transaction instanceof NoOpTransaction ) {
            transaction = AppdynamicsAgent.startServletTransaction( buildServletContext(objectIntercepted), getCorrelationHeader(objectIntercepted), EntryTypes.HTTP, false);
        }
        transaction.collectData("RequestID", getReflectiveString(objectIntercepted, getRequestId, "UNKNOWN-ID"), snapshotDatascopeOnly );
        transaction.collectData("Client", getReflectiveString(objectIntercepted, getClientKey, "UNKNOWN-CLIENT"), dataScopes );
        this.getLogger().debug("McWorkspaceApiInterceptor.onMethodBegin() finish: "+ className +"."+ methodName +"()");

        return transaction;
    }

    private ServletContext buildServletContext(Object objectIntercepted) {
        ServletContext.ServletContextBuilder builder = new ServletContext.ServletContextBuilder();
        try {
            builder.withURL( getReflectiveString(objectIntercepted, getURL, "/unknown-url") );
        } catch (MalformedURLException e) {
            getLogger().info(String.format("Warning: URL exception: %s", e.getMessage()));
        }

        builder.withRequestMethod( getReflectiveString(objectIntercepted,getMethod,"GET"));

        getLogger().debug(String.format("Need to figure out how to parse parameters: %s", getReflectiveString(objectIntercepted, getQuery, "UNKNOWN-QUERY")));

        return builder.build();
    }

    private String getCorrelationHeader(Object request) {
        if( request == null ) return null;
        try {
            return (String) getHeader.execute(request.getClass().getClassLoader(), request, new Object[]{ AppdynamicsAgent.TRANSACTION_CORRELATION_HEADER });
        } catch (ReflectorException e) {
            getLogger().info(String.format("Exception trying to read transaction correlation header: %s", e ));
        }
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
        int status = getReflectiveInteger(object, getStatus, -1);
        if( status == -1 ) getLogger().info("HTTP Response status is not set?");
        if( status > 399 ) {
            transaction.markAsError(String.format("HTTP Response Status is %d", status));
        }
        transaction.end();
        this.getLogger().debug("McWorkspaceApiInterceptor.onMethodEnd() finish: "+ className +"."+ methodName +"()");
    }

    @Override
    public List<Rule> initializeRules() {
        List<Rule> rules = new ArrayList<Rule>();
        rules.add(new Rule.Builder(
                "com.maconomy.webservices.common.mcontext.McMaconomyContextFilter$FilterRequestHandler")
                .classMatchType(SDKClassMatchType.MATCHES_CLASS)
                .methodMatchString("handleRequest")
                .methodStringMatchType( SDKStringMatchType.EQUALS)
                .build()
        );

        return rules;
    }
}
